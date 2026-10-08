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
REPO="${REPO:-termux-app}"
BUNDLE="${BUNDLE:-}"
INSTALL="${INSTALL:-0}"

echo "########## STEP 1/6：准备 termux-app 仓库 ##########"
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

echo "########## STEP 2/6：放入 bundle + patch 到 assets ##########"
ASSETS="$REPO/app/src/main/assets"
mkdir -p "$ASSETS"
cp -f "$HERE/patch.sh" "$ASSETS/patch.sh"
if [ -n "$BUNDLE" ] && [ -f "$BUNDLE" ]; then
  cp -f "$BUNDLE" "$ASSETS/dsh-bundle.tar.gz"
  echo "  ✓ bundle + patch.sh -> assets（含 dsh 运行环境）"
else
  echo "  ! 未提供 BUNDLE —— 编出来是【空壳 APK】：能装能开，但会停在自检页"
  echo "    完整版：手机 Termux 跑 02-build-bundle.sh 打出 dsh 环境后重编"
  # 放个合法但空的占位 tar，避免 DshBootstrap 首启读 assets 崩溃
  tar -czf "$ASSETS/dsh-bundle.tar.gz" -T /dev/null 2>/dev/null || true
fi

echo "########## STEP 3/6：改造 Termux 源码（幂等） ##########"
bash "$HERE/01-modify-termux.sh" "$REPO"

echo "########## STEP 4/6：拷贝 Java 源码 ##########"
# ⚠️ 必须是 .java：Termux v0.118.0 没有 Kotlin 插件，.kt 会被完全忽略、不编进 dex
DST="$REPO/app/src/main/java/com/dsh/harness"
mkdir -p "$DST"
cp -f "$HERE/../android-java/com/dsh/harness/"*.java "$DST/"
echo "  ✓ $(ls "$DST" | wc -l) 个源文件已就位"

echo "########## STEP 5/6：编译 ##########"
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

echo "########## STEP 6/6：产物 ##########"
APK="$REPO/app/build/outputs/apk/debug/termux-app_apt-android-7-debug_arm64-v8a.apk"
[ -f "$APK" ] || APK=$(find app/build/outputs -name "*arm64-v8a*.apk" | head -1)
if [ -z "$APK" ]; then echo "✗ 未找到产物 APK"; exit 1; fi
echo "  ✓ $APK ($(du -h "$APK" | cut -f1))"

if [ "$INSTALL" = "1" ]; then
  if command -v adb >/dev/null 2>&1 && adb devices | grep -q "device$"; then
    adb install -r "$APK" && echo "  ✓ 已安装"
  else
    echo "  ! 未检测到 adb 设备，跳过安装（手机请手动安装 APK）"
  fi
fi
echo "########## 完成 ##########"