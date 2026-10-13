#!/usr/bin/env bash
# ============================================================================
# sync-from-source.sh —— A-01：三副本同步（取代纯手工 cp）
#
#!! 单一事实源已改为「本仓库自身」（2026-10-13）
#
#   * **java 源码 + manifest + res** 真源 = 本仓库 android-java/
#   * ② 构建树 termux-app **一律是接收方**，不直接编辑
#   * manifest / res 仍由 01-modify-termux.sh 注入（本仓库不含 AndroidManifest.xml，
#     它在 termux-app 里被脚本改造，所以不能反向拷贝覆盖）
#
# 改动理由：原默认值 DSH_SRC_ROOT 指向 `D:/Program AI/DSH Phone/dsh-android-app`
#（旧副本），而①→③ 那条 cp 会把旧副本**写回本仓库**——真相源被覆盖，
# 且失败时报错信息指向一个早已不是真源的地方。真相源必须唯一且自洽。
#
# 用法
#   bash scripts/sync-from-source.sh
#   DSH_BUILD_TREE=/other/path bash scripts/sync-from-source.sh   # 覆盖构建树
# ============================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC_ROOT="${DSH_SRC_ROOT:-$REPO_ROOT/android-java}"
BUILD_TREE="${DSH_BUILD_TREE:-D:/work/termux-app}"

SRC_JAVA="$SRC_ROOT/com/dsh/harness"
DST_JAVA="$BUILD_TREE/app/src/main/java/com/dsh/harness"

[ -d "$SRC_JAVA" ]  || { echo "✗ 找不到 java 真源：$SRC_JAVA"; exit 1; }
[ -d "$DST_JAVA" ] || { echo "✗ 找不到构建树：$DST_JAVA"; exit 1; }

echo "==> 真源→构建树   java 源码"
echo "    真源: $SRC_JAVA"
echo "    目标: $DST_JAVA"
cp -f "$SRC_JAVA"/*.java "$DST_JAVA"/
echo "    ✓ $(ls -1 "$SRC_JAVA"/*.java | wc -l | tr -d ' ') 个 java 文件"

# ⚠️ manifest **不**从本仓库拷过去：它不在本仓库，而在 termux-app 里由
#01-modify-termux.sh 幂等改造。反向拷贝会把包名/LAUNCHER/provider 注册
#    全部冲掉 —— 那正是 A-01 同步要解决的问题，不能自己再犯一遍。

# res/xml（network_security_config.xml、dsh_file_paths.xml）
# 由 01-modify-termux.sh 负责拷贝（那里有"必须成对出现"的校验），
# 这里只做同步确认，不重复拷。
echo "==> res/xml 同步确认"
for f in "$SRC_ROOT"/res/xml/*.xml; do
  [ -e "$f" ] || continue
  name="$(basename "$f")"
  dst="$BUILD_TREE/app/src/main/res/xml/$name"
  if [ -f "$dst" ] && cmp -s "$f" "$dst"; then
    echo "    ✓ $name（已一致）"
  else
    echo "    ✗ $name 缺失或不一致 → 请跑 bash scripts/01-modify-termux.sh"
  fi
done

# assets（A-04 起：注入的 CSS/JS 真源在本仓库）
if [ -d "$SRC_ROOT/assets" ]; then
    mkdir -p "$BUILD_TREE/app/src/main/assets"
    cp -rf "$SRC_ROOT/assets/." "$BUILD_TREE/app/src/main/assets/"
    echo "==> ✓ assets/ ($(find "$BUILD_TREE/app/src/main/assets" -type f | wc -l | tr -d ' ') 个文件)"
fi

echo "==> 校验副本一致性"
if [ -f "$REPO_ROOT/scripts/verify-sync.sh" ]; then
  bash "$REPO_ROOT/scripts/verify-sync.sh"
fi