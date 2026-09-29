#!/bin/bash
# 编译 IGV 引物插件（IGV 2.3.80, Java 8）
# 用法:
#   JAVA8_HOME=/path/to/jdk8  IGV_JAR=/path/to/igv.jar  bash build.sh
# 也可通过环境变量覆盖；未设置时回退到常见默认路径。
set -e

JAVA8_HOME="${JAVA8_HOME:-/opt/jdk8}"                       # 含 bin/javac 的 Java 8 安装目录
IGV_JAR="${IGV_JAR:-/opt/igv/lib/igv.jar}"                 # 你合法取得的 IGV 2.3.80 igv.jar
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

rm -rf "$OUT" && mkdir -p "$OUT"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -cp "$IGV_JAR" -d "$OUT" \
  "$SRC"/org/broad/igv/primer/*.java

echo "== 编译成功 =="
ls "$OUT"/org/broad/igv/primer/
