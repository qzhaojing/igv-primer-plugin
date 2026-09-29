#!/bin/bash
# 编译 + 部署 IGV 引物插件（IGV 2.3.80, Java 8）
set -e
JDK=/f/zhaojing/jdk8/jdk8u504-b01/bin
IGV_DIR="/f/zhaojing/IGV_2.3.80/IGV_2.3.80_jre"
IGV_JAR="$IGV_DIR/lib/igv.jar"
SRC=src
OUT=classes

rm -rf "$OUT" && mkdir -p "$OUT"
"$JDK/javac" -encoding UTF-8 -source 8 -target 8 -cp "$IGV_JAR" -d "$OUT" \
  "$SRC"/org/broad/igv/primer/*.java

echo "== 编译成功 =="
ls "$OUT"/org/broad/igv/primer/
