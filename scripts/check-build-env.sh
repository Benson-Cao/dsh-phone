#!/usr/bin/env bash
# check-build-env.sh —— 构建前环境自检（maintainability-plan.md §四.2）
#
# 用法：
#   bash scripts/check-build-env.sh              # 自检并给出结论
#   bash scripts/check-build-env.sh --strict     # 有 WARN 也返回非 0（给 CI 用）
#   REPO=/d/work/termux-app bash scripts/check-build-env.sh
#
# 存在意义：把「构建失败」从「半小时后 gradle 报一堆看不懂的错」提前到
# 「3 秒内告诉你缺什么」。下面每一条都对应一个真实踩过的坑。

set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
REPO="${REPO:-$ROOT/../termux-app}"

STRICT=0
[ "${1:-}" = "--strict" ] && STRICT=1

ERR=0; WARN=0
ok()   { printf '  \033[32m✓\033[0m %s\n' "$1"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$1"; WARN=$((WARN+1)); }
err()  { printf '  \033[31m✗\033[0m %s\n' "$1"; ERR=$((ERR+1)); }
sec()  { printf '\n\033[1m%s\033[0m\n' "$1"; }

sec "1. 仓库与版本源"
[ -d "$ROOT/android-java/com/dsh/harness" ] \
  && ok "harness 源码: $ROOT/android-java" \
  || err "找不到 harness 源码: $ROOT/android-java"
if [ -f "$ROOT/version.ini" ]; then
  ok "version.ini 存在（版本单一来源）"
  VC="$(sed -n 's/^[[:space:]]*version_code[[:space:]]*=[[:space:]]*\([^#]*\).*/\1/p' "$ROOT/version.ini" | head -1 | tr -d '[:space:]')"
  [ -n "$VC" ] && ok "  version_code = $VC" || err "  version.ini 里没有 version_code"
else
  err "缺少 version.ini（maintainability-plan.md §四.1）—— 脚本会以 999 兜底，等于没做版本管理"
fi

sec "2. Termux Gradle 工程"
if [ -d "$REPO/.git" ]; then
  ok "termux-app: $REPO"
  if [ -f "$REPO/gradlew" ]; then ok "  gradlew 存在"; else err "  缺 gradlew"; fi
else
  err "找不到 termux-app 工程: $REPO"
  echo "      先跑： bash scripts/00-build-apk.sh（会自动 clone）或手动 clone v0.118.0"
fi
# 坑 1：路径含空格 → ndk-build 必失败，且 junction 无效
case "$REPO" in
  *" "*) err "工程路径含空格 —— ndk-build 会报 APP_BUILD_SCRIPT points to an unknown file" ;;
  *) [ -d "$REPO" ] && ok "  路径无空格（ndk-build 要求）" ;;
esac

sec "3. Android SDK / JDK"
for v in ANDROID_HOME ANDROID_SDK_ROOT JAVA_HOME; do
  if [ -n "${!v:-}" ] && [ -d "${!v}" ]; then ok "$v = ${!v}"; else warn "$v 未设置或不存在（构建时会默认 D:/AndroidSDK）"; fi
done
SDK="${ANDROID_HOME:-D:/AndroidSDK}"
[ -d "$SDK" ] && ok "SDK 目录: $SDK" || err "找不到 SDK: $SDK（装 build-tools / platform / jdk17）"
[ -d "$SDK/build-tools" ] && ok "  build-tools: $(ls "$SDK/build-tools" 2>/dev/null | tr '\n' ' ')" \
  || err "  缺 build-tools（sdkmanager 'build-tools;34.0.0'）"
JDK="${JAVA_HOME:-$SDK/jdk17}"
if [ -x "$JDK/bin/javac.exe" ] || [ -x "$JDK/bin/javac" ]; then
  ok "JDK: $JDK ($("$JDK/bin/java" -version 2>&1 | head -1))"
else
  err "找不到可用 JDK: $JDK（Termux v0.118 需要 JDK 17）"
fi

sec "4. Gradle 依赖缓存（决定能否离线构建）"
# 坑 2：依赖都在 ~/.gradle/caches 时，--offline 完全可以出包。
# 这是本机唯一可靠的构建方式（见下方「代理」一节）。
GRADLE_HOME_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}"
if [ -d "$GRADLE_HOME_DIR/caches/modules-2" ]; then
  ok "Gradle 缓存: $GRADLE_HOME_DIR/caches（存在，可尝试 --offline）"
else
  warn "没有 Gradle 依赖缓存 —— 首次构建必须联网，proxy 又多半是坏的"
fi

sec "5. 代理（⚠️ 本机最大的坑）"
# 坑 3：termux-app/gradle.properties 里写死的代理端口是某次会话的临时端口，
# 那端口早已失效 —— 在线构建会卡在 maven.google.com。而代理端口会随会话变化。
if [ -f "$REPO/gradle.properties" ]; then
  PH="$(sed -n 's/^systemProp\.http\.proxyHost=//p' "$REPO/gradle.properties" | head -1)"
  PP="$(sed -n 's/^systemProp\.http\.proxyPort=//p' "$REPO/gradle.properties" | head -1)"
  if [ -n "$PH" ]; then
    ok "gradle.properties 代理: $PH:$PP"
    # ⚠️ 坑（实测）：**光看端口能不能连是不可靠的** —— 沙箱/会话会复用端口，
    # 写死的 51778 可能被别的服务占着，于是"可连"但根本不是代理。
    # 必须真正发一个经代理的请求、看能否拿到远端响应，才算这个代理还活着。
    if command -v curl >/dev/null 2>&1; then
      CODE=$(curl -s -m 8 -o /dev/null -w '%{http_code}' \
             --proxy "http://$PH:$PP" https://maven.google.com/ 2>/dev/null || echo "000")
      # ⚠️ curl 失败时 -w 仍可能吐出多段（如 "000000"：重试/连接复用各一次），
      #   直接比较会误判成"可用"。取最后 3 位并只认明确的 HTTP 状态码。
      CODE="$(printf '%s' "$CODE" | tail -c 4 | sed 's/[^0-9]//g;s/^0*//')"
      [ -z "$CODE" ] && CODE="000"
      case "$CODE" in
        000)
          warn "  代理 $PH:$PP 经访问 maven.google.com 完全失败 —— 不是可用代理（端口可能被别的服务占着）"
          echo "      在线构建会卡住；两条出路："
          echo "      (a) 离线构建：cp 源码后在 termux-app 里 ./gradlew :app:assembleDebug --offline"
          echo "      (b) 覆盖端口：PROXY=host:port bash scripts/00-build-apk.sh（端口用 env | grep -i proxy 现查）"
          ;;
        502|503|504)
          warn "  经代理访问 maven.google.com 返回 $CODE（上游不通）—— 在线构建大概率失败，建议 --offline"
          ;;
        2??|3??)
          ok "  经代理访问 maven.google.com 返回 $CODE（代理可用）"
          ;;
        *)
          warn "  经代理访问 maven.google.com 返回 $CODE（意料之外）—— 建议先用 --offline 验证构建"
          ;;
      esac
    fi
  else
    ok "gradle.properties 未写死代理（走环境变量/直连）"
  fi
fi
ENV_PROXY="$(env | grep -i '^https\?_proxy=' | head -1 || true)"
[ -n "$ENV_PROXY" ] && ok "环境代理: $ENV_PROXY" || ok "环境无代理"

sec "6. 构建必需的静态资源"
# bootstrap-*.zip 来自 termux-packages release；缺了就必须联网下（很慢）。
CPP="$REPO/app/src/main/cpp"
[ -d "$CPP" ] && BOOT_OK=1 || BOOT_OK=0
for a in aarch64 arm i686 x86; do
  if [ -f "$CPP/bootstrap-$a.zip" ]; then ok "  bootstrap-$a.zip"; else
    if [ "$BOOT_OK" = "1" ]; then warn "  缺 bootstrap-$a.zip（会在构建时尝试从 gh-proxy 下载）"; else err "  缺 bootstrap-$a.zip 且 cpp 目录不存在"; fi
  fi
done
# dsh bundle：没有它编出来是「空壳 APK」——能装能开，但停在自检页。
if [ -f "$REPO/app/src/main/assets/dsh-bundle.tar.gz" ]; then
  SZ=$(du -h "$REPO/app/src/main/assets/dsh-bundle.tar.gz" 2>/dev/null | cut -f1)
  if [ "${SZ:-0}" = "0" ] || [ ! -s "$REPO/app/src/main/assets/dsh-bundle.tar.gz" ]; then
    warn "dsh-bundle.tar.gz 存在但是空的 —— 编出来是空壳 APK"
  else
    ok "dsh-bundle.tar.gz ($SZ)"
  fi
else
  warn "没有 assets/dsh-bundle.tar.gz —— 编出来是【空壳 APK】（能装能开，停在自检页）"
  echo "      完整版：在手机 Termux 跑 scripts/02-build-bundle.sh 产出后，用BUNDLE=... 指定"
fi

sec "7. 构建脚本"
for s in 00-build-apk 01-modify-termux 02-build-bundle 03-package-asset check-build-env; do
  [ -f "$HERE/$s.sh" ] && ok "  $s.sh" || warn "  缺 $s.sh"
done
command -v bash >/dev/null 2>&1 && ok "bash 可用: $(bash --version | head -1)" || err "没有 bash"
command -v tar >/dev/null 2>&1 && ok "tar 可用" || err "没有 tar（解包 bundle 必需）"

# ---- 结论 ----
printf '\n\033[1m结论\033[0m\n'
echo "  错误 $ERR 项 / 警告 $WARN 项"
if [ "$ERR" -eq 0 ]; then
  echo -e "  \033[32m可以开始构建：bash scripts/00-build-apk.sh\033[0m"
  [ "$WARN" -gt 0 ] && echo "  （有警告，注意上面标 ! 的项）"
else
  echo -e "  \033[31m先修掉上面标 ✗ 的项，否则构建一定失败。\033[0m"
fi

[ "$ERR" -gt 0 ] && exit 1
[ "$STRICT" = "1" ] && [ "$WARN" -gt 0 ] && exit 2
exit 0