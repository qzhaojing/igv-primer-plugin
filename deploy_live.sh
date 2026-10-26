#!/bin/bash
# v0.1.73：把注入好的 _inject_target.jar 部署到 IGV 实际安装目录（live）。
# ⚠️ 教训：install.py 只注入项目内的 _inject_target.jar，不等于生效！
# IGV 真正加载的是 F:\zhaojing\IGV_2.3.80\IGV_2.3.80_jre\lib\igv.jar，
# v0.1.72/73 就是漏了这步导致"注入成功但界面没变化"。
# 用法：bash deploy_live.sh   （build.sh + install.py 之后运行）
set -e
LIVE_IGV="${LIVE_IGV:-/f/zhaojing/IGV_2.3.80/IGV_2.3.80_jre/lib/igv.jar}"
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/_inject_target.jar"

if [ ! -f "$SRC" ]; then echo "[deploy] 找不到 $SRC，先跑 build.sh + install.py" >&2; exit 1; fi
if [ ! -f "$LIVE_IGV" ]; then echo "[deploy] 找不到 live igv.jar: $LIVE_IGV" >&2; exit 1; fi

BAK="$HERE/igv-live.jar.bak-$(date +%Y%m%d-%H%M%S)"
cp "$LIVE_IGV" "$BAK"
cp "$SRC" "$LIVE_IGV"
echo "[deploy] 已备份旧 live jar -> $BAK"
echo "[deploy] 已部署: $SRC -> $LIVE_IGV"
md5sum "$SRC" "$LIVE_IGV"
echo "[deploy] 重启 IGV 后生效。"
