#!/usr/bin/env bash
# 00-build-apk.sh —— 在【PC】一键编译 dsh Phone 的 APK
#
# 流程：clone termux-app → 放 bundle → 改造源码 → 编译 → 可选安装
#
# 用法：
#   bash 00-build-apk.sh                       # 用默认路径 ./termux-app
#   REPO=/d/work/termux-app bash 00-build-apk.sh
#   PROXY=127.0.0.1:51651 bash 00-build-apk.sh # 需要代理时（Gradle 的 Java TLS 握手必须显式传参）
#   BUNDLE=/path/dsh-bundle-gnu-v2.tar.gz bash 00-build-apk.sh
#   INSTALL=1 bash 00-build-apk.sh             # 编完自动 adb install（需要连着设备）
#
# ⚠️ 三条会浪费几十分钟的硬约束（都踩过）：
#
# 1) 工程路径【不能含空格】。
#    ndk-build.cmd 是批处理，路径含空格时 Gradle 会报
#    "APP_BUILD_SCRIPT points to an unknown file"（NDK 找不到明明存在的 Android.mk）。
#    ⚠️ 建 junction【无效】—— Gradle 会把路径解析回含空格的原始路径。
#       唯一可靠做法：把工程 mv 到无空格路径（例如 D:\work\termux-app）。
#
# 2) 全量 `./gradlew clean` 会触发 bootstrap 资源重下，卡 30+ 分钟。
#    只想增量就删 app/build/outputs/apk 与 app/build/intermediates/{assets,compressed_assets}。
#
# 3) Gradle 的 Java TLS 握手会被代理中断，必须把代理写进 gradle.properties 的
#    systemProp.http(s).proxy*（端口会变，每次用 env | grep -i proxy 现查）。
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
REPO="${REPO:-termux-app}"
BUNDLE="${BUNDLE:-}"
INSTALL="${INSTALL:-0}"
# 归档目录（出包后按版本命名留档）。默认指向约定的产物目录；
# 设为空字符串则跳过归档。
APK_OUT="${APK_OUT:-D:/Program AI/DSH Phone/apk-out}"
# 归档名里的内容标签（如 export / protocol），便于一眼看出这包装了什么。
APK_TAG="${APK_TAG:-build}"

# 版本单一来源（maintainability-plan.md §四.1）：version.ini。
# 出包后据此重命名产物，人工改名不再是区分批次/版本的手段。
VERSION_INI="$ROOT/version.ini"
ini_get() {
  local key="$1" fallback="${2:-}" v
  v="$(sed -n "s/^[[:space:]]*${key}[[:space:]]*=[[:space:]]*\([^#]*\).*/\1/p" "$VERSION_INI" \
        | head -1 | tr -d '[:space:]')" 2>/dev/null || true
  [ -n "$v" ] || v="$fallback"
  printf '%s' "$v"
}
if [ -f "$VERSION_INI" ]; then
  V_NAME="$(ini_get version_name 1.0.0)"
  V_REV="$(ini_get build_rev 0)"
else
  V_NAME="unknown"; V_REV="0"
  echo "  ! 未找到 version.ini，产物名将不含版本号"
fi

echo "########## STEP 1/7：准备 termux-app 仓库 ##########"
if [ ! -d "$REPO/.git" ]; then
  echo "  clone termux-app（浅克隆，v0.118.0）..."
  git -c http.proxy="${PROXY:-}" -c https.proxy="${PROXY:-}" \
      clone --depth 1 --branch v0.118.0 https://github.com/termux/termux-app.git "$REPO"
else
  echo "  已存在 $REPO，跳过 clone"
fi

case "$REPO" in
  *" "*)
    echo "  ✗ 路径含空格，ndk-build 一定会失败。请先执行："
    echo "      mv \"$(cd "$REPO" && pwd)\" /d/work/termux-app"
    exit 1;;
esac

echo "########## STEP 2/7：放入 bundle + patch 到 assets ##########"
# 实际逻辑在 03-package-asset.sh（可单独重跑，含 gzip/布局自检）。
# 这里调它而不是复制一份粘贴 —— 过去两份逻辑各改各的，已经开始漂移。
BUNDLE="$BUNDLE" bash "$HERE/03-package-asset.sh" "$REPO"

echo "########## STEP 3/7：改造 Termux 源码（幂等） ##########"
bash "$HERE/01-modify-termux.sh" "$REPO"

echo "########## STEP 4/7：拷贝 Java 源码 ##########"
# ⚠️ 必须是 .java：Termux v0.118.0 没有 Kotlin 插件，.kt 会被完全忽略、不编进 dex
DST="$REPO/app/src/main/java/com/dsh/harness"
mkdir -p "$DST"
cp -f "$HERE/../android-java/com/dsh/harness/"*.java "$DST/"
echo "  ✓ $(ls "$DST" | wc -l) 个源文件已就位"

echo "########## STEP 5/7：编译 ##########"
export ANDROID_HOME="${ANDROID_HOME:-D:/AndroidSDK}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-D:/AndroidSDK}"
export JAVA_HOME="${JAVA_HOME:-D:/AndroidSDK/jdk17}"

if [ -n "${PROXY:-}" ]; then
  P_HOST="${PROXY%%:*}"; P_PORT="${PROXY##*:}"
  echo "  代理: $PROXY（写入 gradle.properties）"
  for f in http https; do
    sed -i "s|^systemProp.$f.proxyHost=.*|systemProp.$f.proxyHost=$P_HOST|;\
            s|^systemProp.$f.proxyPort=.*|systemProp.$f.proxyPort=$P_PORT|" \
           "$REPO/gradle.properties" 2>/dev/null || true
  done
fi

# termux-am-library 来自 jitpack，缺失时从镜像补齐（GitHub release 直连常常不通）
ensure_bootstraps() {
  local cpp="$REPO/app/src/main/cpp"
  local ver="2026.02.12-r1%2Bapt.android-7"
  for arch in aarch64 arm i686 x86; do
    if [ -f "$cpp/bootstrap-$arch.zip" ]; then
      echo "  · bootstrap-$arch.zip 已存在"
    else
      echo "  ↓ 从镜像下载 bootstrap-$arch.zip"
      curl -sL --max-time 600 \
        "https://gh-proxy.com/https://github.com/termux/termux-packages/releases/download/bootstrap-${ver}/bootstrap-${arch}.zip" \
        -o "$cpp/bootstrap-$arch.zip" || true
    fi
  done
}
ensure_bootstraps

cd "$REPO"
# 注意任务名：只有 assembleDebug，没有 assembleArm64-v8aDebug
bash gradlew :app:assembleDebug --console=plain

echo "########## STEP 6/7：产物 ##########"
APK="$REPO/app/build/outputs/apk/debug/termux-app_apt-android-7-debug_arm64-v8a.apk"
[ -f "$APK" ] || APK=$(find app/build/outputs -name "*arm64-v8a*.apk" | head -1)
if [ -z "$APK" ] || [ ! -f "$APK" ]; then echo "✗ 未找到产物 APK"; exit 1; fi
echo "  ✓ $APK ($(du -h "$APK" | cut -f1))"

# 自动按 version.ini 重命名 —— 取代过去人工改成 DSH-Phone-v999-r95-*.apk 的做法。
# 保留原文件（cp 而非 mv）：gradle 的 outputs 目录留着不影响下次构建。
OUT_APK="$(dirname "$APK")/DSH-Phone-${V_NAME}-r${V_REV}-$(basename "$APK")"
cp -f "$APK" "$OUT_APK"
echo "  ✓ 版本化副本: $OUT_APK"

echo "########## STEP 7/7：产物 hash 校验 + 归档 ##########"
# 目的：assets 静默损坏/传输截断要能被立刻发现，而不是等真机启动失败才发现。
# 700MB bundle 打进去的包，静默损坏的排查成本极高。
APK_DIR="$(dirname "$APK")"
( cd "$APK_DIR" && sha256sum "$(basename "$APK")" > SHA256SUMS.txt )
echo "  ✓ SHA256SUMS.txt（构建目录）"
sed 's/^/      /' "$APK_DIR/SHA256SUMS.txt"

# ---- 归档到统一产物目录 ----
# 为什么要有这一步：产物散在 termux-app/app/build/outputs/apk/debug/ 下，
# 那个目录 `./gradlew clean` 一冲就没了，且历史包会被覆盖、无法回退对比。
# 归档后按版本命名留档，还能随时校验拷贝是否完整（258MB 拷贝值得验一次）。
if [ -n "$APK_OUT" ]; then
  mkdir -p "$APK_OUT" 2>/dev/null || { echo "  ! 归档目录不可写，跳过：$APK_OUT"; APK_OUT=""; }
fi
if [ -n "$APK_OUT" ]; then
  # 归档名带版本与内容标签：DSH-Phone-<ver>-r<rev>-<tag>-<abi>-debug.apk
  # 从 gradle 原始名里剥出 abi 部分（...debug_arm64-v8a.apk → arm64-v8a）
  A_ABI="$(basename "$APK" | sed 's/^termux-app_apt-android-7-debug_//;s/\.apk$//')"
  A_NAME="DSH-Phone-${V_NAME}-r${V_REV}-${APK_TAG}-${A_ABI}-debug.apk"
  cp -f "$APK" "$APK_OUT/$A_NAME"
  echo "  ✓ 已归档: $APK_OUT/$A_NAME ($(du -m "$APK_OUT/$A_NAME" | cut -f1) MB)"
  # 归档目录里重新生成校验和（含本目录历史包，便于一次校验全部）
  ( cd "$APK_OUT" && sha256sum *.apk > SHA256SUMS.txt 2>/dev/null || true )
  # 拷贝完整性自查：立刻验一次，别等到装机时才发现包是坏的
  if ( cd "$APK_OUT" && sha256sum -c SHA256SUMS.txt >/dev/null 2>&1 ); then
    echo "  ✓ 归档校验通过"
  else
    echo "  ⚠ 归档校验未通过，请检查磁盘空间或拷贝中断"
  fi
fi

if [ "$INSTALL" = "1" ]; then
  if command -v adb >/dev/null 2>&1 && adb devices | grep -q "device$"; then
    adb install -r "$OUT_APK" && echo "  ✓ 已安装（$OUT_APK）"
  else
    echo "  ! 未检测到 adb 设备，跳过安装（手机请手动安装 APK）"
  fi
fi
echo "########## 完成 ##########"