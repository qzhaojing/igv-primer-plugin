#!/usr/bin/env python3
"""从已知良好的 jar 底座重建插件 jar：注入 primer class + 确保 builtin_plugin_list.txt 注册 + 去签名。
避免 jar uf 无法更新 Python 重打包 jar 的问题。"""
import zipfile, os, sys

SRC = sys.argv[1]    # 底座 jar（含注册项与既有 class）
OUT = sys.argv[2]    # 输出 jar
CLASSES = sys.argv[3]  # 编译产物 classes 目录
PLUGIN = "org/broad/igv/primer/"
REG = "org/broad/igv/ui/resources/builtin_plugin_list.txt"
MARK = "org.broad.igv.primer.PrimerPlugin"

zr = zipfile.ZipFile(SRC, "r")
names = zr.namelist()

# 更新注册项
bl = zr.read(REG).decode("utf-8", "replace")
if MARK not in bl:
    if not bl.endswith("\n"):
        bl += "\n"
    bl += MARK + "\n"
    print("registered", MARK)
else:
    print("already registered", MARK)

# 收集 class 字节
cls = {}
for root, _, files in os.walk(CLASSES):
    for f in files:
        if f.endswith(".class"):
            cls[PLUGIN + f] = open(os.path.join(root, f), "rb").read()

zw = zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED)
order = sorted(names, key=lambda n: (n != "META-INF/MANIFEST.MF", n))
for n in order:
    if n.endswith(".SF") or n.endswith(".RSA") or n.endswith(".DSA"):
        continue  # 去签名
    if n == REG:
        zw.writestr(n, bl)
    elif n in cls:
        zw.writestr(n, cls.pop(n))
    else:
        zw.writestr(n, zr.read(n))
for n, b in cls.items():  # 兜底：原 jar 中没有的新 class
    zw.writestr(n, b)
zw.close()
zr.close()
print("deployed ->", OUT, " (", os.path.getsize(OUT), "bytes )")
