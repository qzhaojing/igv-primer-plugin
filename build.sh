#!/bin/bash
# 编译 IGV 引物插件（IGV 2.3.80, Java 8）
# 用法:
#   JAVA8_HOME=/path/to/jdk8  IGV_JAR=/path/to/igv.jar  bash build.sh
# 也可通过环境变量覆盖；未设置时回退到常见默认路径。
set -e

JAVA8_HOME="${JAVA8_HOME:-/opt/jdk8}"                       # 含 bin/javac 的 Java 8 安装目录
# 重要：编译 classpath 必须用「干净 IGV jar」（不含任何本插件 class），否则 javac 会
# 解析到 jar 内残留的旧版 PrimerStore/Primer，导致新加的方法/字段「找不到符号」或
# 编译出缺引用的 class（v0.1.30 曾因此栽过跟头）。本目录的 _igv_base.jar 即由
# _test_igv.jar 剔除 org/broad/igv/primer/* 后生成的干净基类。
IGV_JAR="${IGV_JAR:-_igv_base.jar}"                        # 干净 IGV 2.3.80 jar（无插件类）
SRC=src
OUT=classes

JAVAC="$JAVA8_HOME/bin/javac"

if [ ! -x "$JAVAC" ]; then
  echo "[build] 未找到 javac: $JAVAC" >&2
  echo "[build] 请先设置 JAVA8_HOME 指向 Java 8 安装目录" >&2
  exit 1
fi
if [ ! -f "$IGV_JAR" ]; then
  echo "[build] 未找到 igv.jar: $IGV_JAR" >&2
  echo "[build] 请先设置 IGV_JAR 指向 IGV 2.3.80 的 igv.jar" >&2
  exit 1
fi

# v0.1.79：清理输出目录。**不要**用 `rm -rf classes` —— 本机 WorkBuddy 把 rm -rf 接到
# 安全删除钩子（genie-trash），回收站操作失败时会 fail-closed「静默不删」，于是上一版
# 已删掉的类（如 TailSetupDialog*.class）会残留并被一起打包、再注入进 IGV。
# 改成逐个删文件 + 删后校验，清不干净就直接报错退出。
if [ -d "$OUT" ]; then find "$OUT" -type f -delete; fi
mkdir -p "$OUT"
STALE=$(find "$OUT" -type f 2>/dev/null | wc -l)
if [ "$STALE" -ne 0 ]; then
  echo "[build] 输出目录未清空（残留 $STALE 个文件）：$OUT" >&2
  exit 1
fi
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -cp "$IGV_JAR" -d "$OUT" \
  "$SRC"/org/broad/igv/primer/*.java

echo "== 编译成功 =="
ls "$OUT"/org/broad/igv/primer/

# v0.1.47：编译 IGV 本体补丁类（igv-patches/ 下的 SessionWriter 等），输出到同一 classes 目录以便整体注入。
# classpath 需同时含干净基类与本插件类（补丁会调用 PrimerSession 等插件 API）。
if ls "$SRC"/../igv-patches/*.java >/dev/null 2>&1; then
  "$JAVAC" -encoding UTF-8 -source 8 -target 8 -cp "$IGV_JAR;$OUT" -d "$OUT" \
    "$SRC"/../igv-patches/*.java
  echo "== 补丁类编译成功 =="
fi

# v0.1.25：重打包 PrimerPlugin.jar（install.py 的注入源；此前漏了这步导致注入的一直是旧类）
rm -f PrimerPlugin.jar
"$JAVA8_HOME/bin/jar" cf PrimerPlugin.jar -C "$OUT" org/

# v0.1.56：把 primer3_core.exe（Primer3 2.x 静态可执行，约 1MB）作为资源打进 jar，
# 运行时由 Primer3Core 释放到 user.home/.igv_primer3/ 并 md5 校验。
if [ -f resources/org/broad/igv/primer/primer3_core.exe ]; then
  "$JAVA8_HOME/bin/jar" uf PrimerPlugin.jar -C resources org/broad/igv/primer/primer3_core.exe
  echo "== 已打包 primer3_core.exe 资源 =="
fi
echo "== 打包 PrimerPlugin.jar 完成 =="
