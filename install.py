#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""IGV 引物插件 —— 通用安装器 (B 模式: 独立 jar + patch 脚本)

把本目录的 PrimerPlugin.jar（仅含插件类，不含任何 IGV/Broad 类）注入到
用户任意 IGV 2.3.80 的 igv.jar 中：
    1) 自动备份原 igv.jar（带时间戳，可一键回退）
    2) 注入插件所有 class
    3) 去除 Broad CODESIGN 签名（改 jar 内部必须，否则 IGV 启动报签名错）
    4) 确保 builtin_plugin_list.txt 注册项存在

用法:
    python install.py --package          # 仅打包本项目的 PrimerPlugin.jar（分发用）
    python install.py <igv.jar路径>      # 给指定的 igv.jar 打补丁
    python install.py                    # 自动探测常见位置 / 当前目录的 igv.jar

合规说明:
    本脚本只把"我们自己编写的类"写入目标 jar，不重新分发被改过签名的
    Broad igv.jar 本身；分发物仅为 PrimerPlugin.jar + 本脚本。用户用自己
    合法取得的 IGV 打补丁，符合 IGV 的 MIT 许可。发布时请随包附 IGV 的
    MIT license 声明（见 README「许可」一节）。
"""
import zipfile
import os
import sys
import shutil
import glob
import datetime

PLUGIN = "org/broad/igv/primer/"
REG = "org/broad/igv/ui/resources/builtin_plugin_list.txt"
MARK = "org.broad.igv.primer.PrimerPlugin"

HERE = os.path.dirname(os.path.abspath(__file__))


def log(msg):
    print("[install] " + msg)


def load_plugin_bytes():
    """优先读 PrimerPlugin.jar，否则回退到 classes/ 目录。返回 {name: bytes}。"""
    jar = os.path.join(HERE, "PrimerPlugin.jar")
    cls = {}
    if os.path.exists(jar):
        z = zipfile.ZipFile(jar)
        for n in z.namelist():
            if n.startswith(PLUGIN) and n.endswith(".class"):
                cls[n] = z.read(n)
        z.close()
        log("从 PrimerPlugin.jar 读取 %d 个类" % len(cls))
        return cls
    clsdir = os.path.join(HERE, "classes")
    if os.path.isdir(clsdir):
        for root, _, files in os.walk(clsdir):
            for f in files:
                if f.endswith(".class"):
                    full = os.path.join(root, f)
                    rel = os.path.relpath(full, clsdir).replace("\\", "/")
                    cls[rel] = open(full, "rb").read()
        log("从 classes/ 读取 %d 个类" % len(cls))
        return cls
    sys.exit("[install] 错误: 找不到 PrimerPlugin.jar 或 classes/，无法安装")


def package():
    """把 classes/ 里的插件类打包成独立 PrimerPlugin.jar（只含本插件类）。"""
    src = os.path.join(HERE, "classes")
    if not os.path.isdir(src):
        sys.exit("[install] 错误: classes/ 不存在，请先运行 build.sh 编译")
    out = os.path.join(HERE, "PrimerPlugin.jar")
    zw = zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED)
    cnt = 0
    for root, _, files in os.walk(src):
        for f in files:
            if f.endswith(".class"):
                full = os.path.join(root, f)
                arc = os.path.relpath(full, src).replace("\\", "/")
                zw.write(full, arc)
                cnt += 1
    zw.close()
    log("已打包 PrimerPlugin.jar (%d 个类, %d bytes)" % (cnt, os.path.getsize(out)))
    log("分发时连同本 install.py 一起发给用户即可。")


def find_igv_jar():
    cands = []
    for p in glob.glob(os.path.join(HERE, "**", "igv.jar"), recursive=True):
        cands.append(p)
    for base in [
        r"C:\Program Files\IGV\lib",
        r"C:\IGV\lib",
        r"/opt/igv/lib",
    ]:
        c = os.path.join(base, "igv.jar")
        if os.path.exists(c):
            cands.append(c)
    return cands


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--package":
        package()
        return

    if len(sys.argv) > 1:
        target = os.path.abspath(sys.argv[1])
    else:
        cands = find_igv_jar()
        if not cands:
            sys.exit("[install] 未指定 igv.jar 且自动探测失败。\n"
                     "          请运行: python install.py <igv.jar路径>")
        target = os.path.abspath(cands[0])
        log("自动探测到 igv.jar: %s" % target)

    if not os.path.exists(target):
        sys.exit("[install] 错误: 目标不存在: %s" % target)

    # 基本校验: 必须是 IGV 的 jar（含 builtin_plugin_list.txt）
    zr0 = zipfile.ZipFile(target)
    if REG not in zr0.namelist():
        zr0.close()
        sys.exit("[install] 错误: %s 不是 IGV 的 igv.jar（缺少 %s），中止以免误改其他文件" % (target, REG))
    zr0.close()

    # 1) 备份
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    bak = target + ".bak-" + stamp
    shutil.copy2(target, bak)
    log("已备份原 jar -> %s" % bak)

    # 2) 读取插件类
    cls = load_plugin_bytes()

    # 3) 重写 jar: 注入类 + 去签名 + 确保注册
    #    注意: 必须先写临时文件再原子替换, 不能同路径既读又写,
    #    否则在某些平台/Python 版本下写操作会截断正在读取的文件 (Truncated file header).
    zr = zipfile.ZipFile(target, "r")
    names = zr.namelist()
    bl = zr.read(REG).decode("utf-8", "replace")
    if MARK not in bl:
        if not bl.endswith("\n"):
            bl += "\n"
        bl += MARK + "\n"
        log("已写入注册项: %s" % MARK)
    else:
        log("注册项已存在，跳过")

    import tempfile
    tmpfd, tmpname = tempfile.mkstemp(
        dir=os.path.dirname(os.path.abspath(target)), suffix=".tmp")
    os.close(tmpfd)
    try:
        zw = zipfile.ZipFile(tmpname, "w", zipfile.ZIP_DEFLATED)
        order = sorted(names, key=lambda n: (n != "META-INF/MANIFEST.MF", n))
        for n in order:
            up = n.upper()
            if up.endswith((".SF", ".RSA", ".DSA")) or "CODESIGN" in up:
                continue  # 去签名
            if n == REG:
                zw.writestr(n, bl)
            elif n in cls:
                zw.writestr(n, cls.pop(n))
            else:
                zw.writestr(n, zr.read(n))
        for n, b in cls.items():  # 兜底: 原 jar 没有的新 class
            zw.writestr(n, b)
        zw.close()
        zr.close()
        os.replace(tmpname, target)  # 原子替换, 失败则保留原文件
    except Exception:
        if os.path.exists(tmpname):
            os.remove(tmpname)
        raise

    log("安装完成 -> %s (%d bytes)" % (target, os.path.getsize(target)))
    log("重启 IGV 即可使用引物插件（Primer 轨自动加载，无需额外操作）。")
    log("如需卸载: 用备份 %s 覆盖回 igv.jar 即可。" % bak)


if __name__ == "__main__":
    main()
