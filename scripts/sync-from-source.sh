#!/usr/bin/env bash
# ============================================================================
# sync-from-source.sh —— A-01：三副本同步（取代纯手工 cp）
#
# 单一事实源（Single Source of Truth）约定
# ----------------------------------------
#   * **java 源码** 真源 = ①  DSH_SRC_ROOT
#         —— ① 只有 harness 的 java 包，是唯一编辑点。
#   * **manifest / res** 真源 = ③  DSH_PUB_REPO
#         —— 因为 ① 历史上就不含 AndroidManifest.xml 与 res/，
#            而 ③ 是 git 仓库、能随 clone 分发，放这里当源最合理。
#   * ② 构建树 DSH_BUILD_TREE **一律是接收方**，不直接编辑。
#
# 为什么要脚本化：过去三处手工 cp，漏同步不会报错，只会在真机上表现成
# 「改了没生效 / 装出来的还是旧版」，是最难查的一类问题（A-01 的根因）。
#
# 用法
#   bash scripts/sync-from-source.sh
#   DSH_BUILD_TREE=/other/path bash scripts/sync-from-source.sh   # 覆盖路径
# ============================================================================
set -euo pipefail

SRC_ROOT="${DSH_SRC_ROOT:-D:/Program AI/DSH Phone/dsh-android-app/android-java}"
BUILD_TREE="${DSH_BUILD_TREE:-D:/work/termux-app}"
PUB_REPO="${DSH_PUB_REPO:-D:/work/dsh-harness-android}"

SRC_JAVA="$SRC_ROOT/com/dsh/harness"
DST_JAVA_2="$BUILD_TREE/app/src/main/java/com/dsh/harness"
DST_JAVA_3="$PUB_REPO/android-java/com/dsh/harness"

[ -d "$SRC_JAVA" ]  || { echo "✗ 找不到 java 真源：$SRC_JAVA"; exit 1; }
[ -d "$DST_JAVA_2" ] || { echo "✗ 找不到构建树：$DST_JAVA_2"; exit 1; }
[ -d "$DST_JAVA_3" ] || { echo "✗ 找不到发布仓库：$DST_JAVA_3"; exit 1; }

echo "==> ①→②③   java 源码"
cp -f "$SRC_JAVA"/*.java "$DST_JAVA_2"/
cp -f "$SRC_JAVA"/*.java "$DST_JAVA_3"/
echo "    ✓ $(ls -1 "$SRC_JAVA"/*.java | wc -l | tr -d ' ') 个 java 文件"

echo "==> ③→②    manifest + res"
cp -f "$PUB_REPO/android-java/AndroidManifest.xml" \
      "$BUILD_TREE/app/src/main/AndroidManifest.xml"
mkdir -p "$BUILD_TREE/app/src/main/res/xml"
# res 可能有多个 xml（当前只有 network_security_config.xml），用 glob 拷
for f in "$PUB_REPO"/android-java/res/xml/*.xml; do
    [ -e "$f" ] || continue
    cp -f "$f" "$BUILD_TREE/app/src/main/res/xml/"
    echo "    ✓ $(basename "$f")"
done
cp -f "$PUB_REPO/android-java/AndroidManifest.xml" \
      "$BUILD_TREE/app/src/main/AndroidManifest.xml"

echo "==> 校验三副本"
bash "$PUB_REPO/scripts/verify-sync.sh"