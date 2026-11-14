#!/usr/bin/env bash
# install-apk.sh —— 把编好的 APK 装到真机（含 vivo/OPPO 系「超级守护」的处置提示）
#
# 用法：
#   bash scripts/install-apk.sh                       # 自动挑最新的 debug APK
#   bash scripts/install-apk.sh <apk路径> [serial]
#   PKG=com.dsh.harness bash scripts/install-apk.sh
#
# 这条流程被踩过四次，每次都表现成「卡十几分钟然后失败」，所以固化下来：
#   1) 设备必须**亮屏且解锁**。熄屏时安装器无法显示确认页，adb 会一直等到超时，
#      最后报 INSTALL_FAILED_ABORTED: User rejected permissions（看着像被拒，其实是没人点）。
#   2) `adb push` 的设备路径会被 MSYS 改写（/data/local/tmp/x → D:/…/PortableGit/…），
#      报「1 file pushed」但设备上没有文件。必须 MSYS_NO_PATHCONV=1，
#      且此时源路径要用 cygpath -w 给 Windows 形式。
#   3) 装完要核对 lastUpdateTime —— 同 versionCode 覆盖安装也会更新它，
#      不核对就会把「命令返回 0」当成「装上了」。
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

ADB="${ADB:-/d/AndroidSDK/platform-tools/adb.exe}"
[ -x "$ADB" ] || ADB="$(command -v adb || true)"
[ -x "${ADB:-}" ] || { echo "✗ 找不到 adb（设 ADB=...）"; exit 1; }

PKG="${PKG:-com.dsh.harness}"
APK="${1:-}"
SERIAL="${2:-${SERIAL:-}}"

if [ -z "$APK" ]; then
  BUILD_DIR="${BUILD_DIR:-/d/work/termux-app/app/build/outputs/apk/debug}"
  APK="$(ls -t "$BUILD_DIR"/*.apk 2>/dev/null | head -1)"
  [ -n "$APK" ] || { echo "✗ 没有找到 APK（先出包，或显式传路径）"; exit 1; }
fi
[ -f "$APK" ] || { echo "✗ APK 不存在：$APK"; exit 1; }
[ -n "$SERIAL" ] || SERIAL="$("$ADB" devices | awk 'NR>1 && $2=="device"{print $1; exit}')"
[ -n "$SERIAL" ] || { echo "✗ 没有已连接设备"; exit 1; }

echo "==> 设备 $SERIAL"
echo "==> APK  $(basename "$APK")  ($(du -h "$APK" | cut -f1))"

# ---- 1) 亮屏 + 解锁 + 常亮（缺这步安装必失败）----
"$ADB" -s "$SERIAL" shell "input keyevent 224; wm dismiss-keyguard; svc power stayon true" >/dev/null 2>&1
echo "  ✓ 已唤醒并保持常亮"

# ---- 2) 推送（绕开 MSYS 路径改写）----
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'
if command -v cygpath >/dev/null 2>&1; then
  SRC_WIN="$(cygpath -w "$APK")"
else
  SRC_WIN="$APK"
fi
DEV_APK="/data/local/tmp/dsh-install.apk"
"$ADB" -s "$SERIAL" push "$SRC_WIN" "$DEV_APK" >/dev/null 2>&1
LOCAL_SZ="$(stat -c %s "$APK" 2>/dev/null || wc -c < "$APK" | tr -d ' ')"
DEV_SZ="$("$ADB" -s "$SERIAL" shell "stat -c %s $DEV_APK 2>/dev/null || ls -l $DEV_APK | awk '{print \$5}'" | tr -d '\r' | head -1)"
if [ -z "$DEV_SZ" ] || [ "$DEV_SZ" != "$LOCAL_SZ" ]; then
  echo "✗ 推送失败或大小不符（本地 $LOCAL_SZ / 设备 ${DEV_SZ:-<无>}）"
  echo "  检查 MSYS_NO_PATHCONV 是否生效：设备路径被改写时 adb 仍会打印 pushed。"
  exit 1
fi
echo "  ✓ 已推送 $DEV_SZ 字节"

# ---- 3) 设备端安装 ----
BEFORE="$("$ADB" -s "$SERIAL" shell "dumpsys package $PKG | grep -m1 lastUpdateTime" | tr -d '\r')"
echo "==> 安装前：${BEFORE:-<未安装>}"
OUT="$("$ADB" -s "$SERIAL" shell "pm install -r $DEV_APK" 2>&1 | tr -d '\r')"
echo "$OUT" | head -3 | sed 's/^/  /'

case "$OUT" in
  *Success*)
    AFTER="$("$ADB" -s "$SERIAL" shell "dumpsys package $PKG | grep -m1 lastUpdateTime" | tr -d '\r')"
    if [ "$AFTER" = "$BEFORE" ]; then
      echo "✗ 报 Success 但 lastUpdateTime 未变 —— 可能装的是同一份包，核对 version_code"
      exit 1
    fi
    echo "  ✓ 安装成功：$AFTER"
    ;;
  *INSTALL_FAILED_ABORTED*|*User\ rejected*)
    echo
    echo "✗ 被安装器拦下（不是网络/空间问题）："
    echo "  设备上会弹出厂商的安装确认页（vivo 叫「超级守护」，OPPO 是「应用安全检测」）。"
    echo "  它需要**在屏幕上点一次「继续 / 安装」**，adb 无法代点；熄屏时更是必然失败。"
    echo "  两种解法："
    echo "    a) 重跑本脚本，在设备亮屏时盯住屏幕，出现确认页立刻点「继续/安装」；"
    echo "    b) 开发者选项里打开「USB 调试（安全设置）」/ 允许「通过 USB 安装应用」，"
    echo "       之后 adb install 不再逐次询问。"
    echo "  当前设备仍装的是上一版，功能未变。"
    exit 2
    ;;
  *)
    echo "✗ 安装失败，原始输出见上"
    exit 1
    ;;
esac

# ---- 4) 清理 ----
"$ADB" -s "$SERIAL" shell "rm -f $DEV_APK" >/dev/null 2>&1
echo "  ✓ 已清理设备端临时 APK"
