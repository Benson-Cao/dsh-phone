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
# 构建树默认指向 /d/work/termux-app：这是 r129/r130 一直在用的正式树，路径无空格。
# 仓库内那份 termux-app/ 只是早期检出，commit 与正式树不同，别在里面出包（见下方空格守卫）。
if [ -z "${REPO:-}" ]; then
  if [ -d /d/work/termux-app/.git ]; then REPO=/d/work/termux-app; else REPO=termux-app; fi
fi
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

# ⚠️ 必须查**绝对路径**有没有空格，不能查 $REPO 这个变量值。
#    2026-11-15 踩坑：REPO 默认是相对名 `termux-app`，永远不含空格，于是这条守卫
#    形同虚设 —— 实际落在 `D:\Dw phone\dsh-phone\termux-app`，ndk-build 收到
#    `APP_BUILD_SCRIPT=D:\Dw phone\...\Android.mk` 后报「points to an unknown file」，
#    看上去像 Android.mk 缺失，其实是空格把参数切断了。
#
# ⚠️ 更关键的是：本项目的正式构建树一直是 **/d/work/termux-app**。
#    dsh-phone/termux-app 是仓库内的一次性检出（gitignore 之外），两者 commit 不同、
#    状态不同，在那个树里编出来的东西和 r129/r130 的产物没有可比性。
#    所以默认值直接指向 /d/work/termux-app，避免再编错树。
REPO_ABS="$(cd "$REPO" 2>/dev/null && pwd || echo "$REPO")"
case "$REPO_ABS" in
  *" "*)
    echo "  ✗ 构建树路径含空格：$REPO_ABS"
    echo "    ndk-build 会把 APP_BUILD_SCRIPT 参数切断，然后报「unknown file」。"
    echo "    请改用无空格路径，例如：REPO=/d/work/termux-app bash $0"
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
# ⚠️ 必须整树拷：源码含子包 webview/（OfficialShell 注入 + DshWebBridge 消息通道），
#    只拷 *.java 顶层会让这两个类缺失 → MainActivity 引用不到，编译失败。
DST="$REPO/app/src/main/java/com/dsh/harness"
SRC="$HERE/../android-java/com/dsh/harness"
mkdir -p "$DST"
# ⚠️ 这里**不要**出现 rm/mv：本仓库现在有 60+ 个源文件，WorkBuddy 的 safe-delete 闸门
#    只要看到命令行里有针对该目录的 rm/mv，就按目录文件数判成「批量删除」并整条拒绝执行
#    （实测 count=67/68 > threshold=50）。症状极隐蔽：脚本在 STEP 4 静默中止，而外层
#    `... | tail` 的管道退出码仍是 0 —— 看着像构建成功，其实连 APK 都没开始编。
#    所以只做覆盖拷贝；删掉源文件后如果目标里留下陈旧类，构建会报重复类，
#    这时手工删掉那一个文件即可（一次一个，不触发闸门）。
cp -rf "$SRC/." "$DST"/
echo "  ✓ $(find "$DST" -name '*.java' | wc -l | tr -d ' ') 个源文件已就位（含子包）"

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

# ---- bootstrap 资源：**从 build.gradle 现读**钉版与哈希，绝不在这里硬编码 ----
#
# 这段的唯一目的，是让 gradle 的 `:app:downloadBootstraps` 什么都不做。
# 那个任务对 src/main/cpp/bootstrap-<arch>.zip 做 SHA-256 校验，不匹配就
# **删掉文件**，改从 github.com 直连重下。本机直连常被限到几 KB/s，构建于是
# 表现成「卡住不动」，实际是在慢慢下载 —— 30MB 的包一等就是二三十分钟。
#
# ⚠️ 这里曾经硬编码过版本号，结果连踩两次：第一次写 2026.02.12-r1，而当时树里
#    钉的是 2022.01.07-r1；改成 2022 之后，正式树又被人升回了 2026.02.12-r1。
#    **硬编码一定会跟 build.gradle 漂移**，而漂移的症状是「构建静默卡 20 分钟」
#    而不是报错，极难定位。所以现在改成运行时解析。
#
# ⚠️ 四个 arch 都要备齐（aarch64 / arm / i686 / x86_64），因为 downloadBootstraps
#    是无条件逐个调用的，与本次编哪个 ABI 无关。少一个就会触发直连下载。
#    资产名是 x86_64，不是 x86 —— 曾因此拿到一个 9 字节的错误页。
pick_python() {
  for c in python3 python py; do
    command -v "$c" >/dev/null 2>&1 && { echo "$c"; return 0; }
  done
  return 1
}

# 从 build.gradle 现读「钉版 + 每个 arch 的 SHA-256」，输出：arch sha version
read_bootstrap_pins() {
  local py gradle="$REPO/app/build.gradle"
  [ -f "$gradle" ] || return 1
  py="$(pick_python)" || return 1
  "$py" - "$gradle" <<'PYEOF_PINS'
import re, sys
src = open(sys.argv[1], encoding='utf-8').read()
# 只认 apt-android-7 这一支 —— 产物文件名 termux-app_apt-android-7-debug_* 就是它
m = re.search(r'packageVariant\s*==\s*"apt-android-7"\)\s*\{(.*?)\}\s*else', src, re.S)
if not m:
    sys.exit(1)
block = m.group(1)
mv = re.search(r'def\s+version\s*=\s*(.+)', block)
if not mv:
    sys.exit(1)
# version 是拼出来的："2026.02.12-r1" + "%2B" + "apt.android-7"
ver = ''.join(re.findall(r'"([^"]*)"', mv.group(1)))
for arch, sha in re.findall(r'downloadBootstrap\(\s*"(\w+)"\s*,\s*"([0-9a-f]{64})"', block):
    print(arch, sha, ver)
PYEOF_PINS
}

bootstrap_ok() {
  local f="$1" want="$2" got
  [ -s "$f" ] || { echo "    · 缺失或空文件：$(basename "$f")"; return 1; }
  got="$(sha256sum "$f" | cut -d' ' -f1)"
  [ "$want" = "$got" ] && return 0
  echo "    · 哈希不符（$(du -h "$f" | cut -f1)）：期望 ${want:0:12}… 实际 ${got:0:12}…"
  return 1
}

ensure_bootstraps() {
  local cpp="$REPO/app/src/main/cpp" pins arch sha ver
  if ! pins="$(read_bootstrap_pins)" || [ -z "$pins" ]; then
    echo "  ! 读不出 build.gradle 里的 bootstrap 钉版，跳过预取（交给 gradle 自己处理）"
    return 0
  fi
  while read -r arch sha ver; do
    [ -n "$arch" ] || continue
    if bootstrap_ok "$cpp/bootstrap-$arch.zip" "$sha"; then
      echo "  · bootstrap-$arch.zip 已就绪（$(du -h "$cpp/bootstrap-$arch.zip" | cut -f1)）"
      continue
    fi
    echo "  ↓ 经 gh-proxy 拉 bootstrap-$arch.zip（$ver）"
    curl -sL --retry 3 --retry-delay 2 --max-time 2400 \
      "https://gh-proxy.com/https://github.com/termux/termux-packages/releases/download/bootstrap-${ver}/bootstrap-${arch}.zip" \
      -o "$cpp/bootstrap-$arch.zip" || true
    if bootstrap_ok "$cpp/bootstrap-$arch.zip" "$sha"; then
      echo "    ✓ $(du -h "$cpp/bootstrap-$arch.zip" | cut -f1)"
    else
      rm -f "$cpp/bootstrap-$arch.zip"
      echo "    ✗ bootstrap-$arch.zip 仍不可用 —— gradle 会自行直连下载，很可能卡很久"
    fi
  done <<< "$pins"
}
# 历史残留：早先把资产名写成 x86，下回来的是错误页。留着会让 gradle 的
# clean 之外的路径读到一个毒文件，直接清掉。
rm -f "$REPO/app/src/main/cpp/bootstrap-x86.zip"
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