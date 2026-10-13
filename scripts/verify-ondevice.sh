#!/usr/bin/env bash
# r79 真机全面体检：安装 → 冷启动 → 抓日志 → 关键点断言
#
# 用法：
#   bash scripts/verify-ondevice.sh <设备IP:端口> [APK路径]
#
# 例：
#   bash scripts/verify-ondevice.sh 192.168.1.88:5555
#
# 设计原则（沿用本项目既有教训）：
#   ① 不猜 —— 每个结论都来自真实命令输出；
#   ② 抓不到就说抓不到，不把"没报错"当成"没问题"；
#   ③ 日志按 tag 过滤，避免被系统噪声淹没。

set -u

ADB="/d/AndroidSDK/platform-tools/adb.exe"
PKG="com.dsh.harness"
ACT=".MainActivity"
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"

DEV="${1:-}"
APK="${2:-}"
[ -z "$DEV" ] && { echo "用法: bash $0 <设备IP:端口> [APK路径]"; exit 1; }
[ -z "$APK" ] && APK="$REPO/../Program AI/DSH Phone/apk-out/DSH-Phone-1.0.0-r101-darksplash-arm64-v8a-debug.apk"
[ -z "$APK" ] && APK="$(ls -t /d/work/termux-app/app/build/outputs/apk/debug/*arm64*.apk 2>/dev/null | head -1)"

echo "########## 0. 连接设备 ##########"
"$ADB" connect "$DEV" 2>&1 | tail -2
sleep 1
if ! "$ADB" devices | grep -q "device$"; then
  echo "✗ 连接失败。检查：手机 IP/端口是否正确、无线调试是否开着、是否已授权本机"
  exit 1
fi
echo "✓ 已连接: $DEV"
echo "--- 设备信息 ---"
"$ADB" shell getprop ro.product.model 2>/dev/null | sed 's/^/  型号: /'
"$ADB" shell getprop ro.build.version.release 2>/dev/null | sed 's/^/  Android: /'
echo "--- 当前深浅色（uiMode night）---"
NIGHT=$("$ADB" shell "cmd uimode night" 2>/dev/null | tr -d '\r')
echo "  cmd uimode night => $NIGHT"

echo
echo "########## 1. 安装 ##########"
if [ -f "$APK" ]; then
  echo "  APK: $APK ($(du -m "$APK" | cut -f1) MB)"
  # 258MB 传输较久，后台跑并显示进度
  "$ADB" install -r -t "$APK" 2>&1 | tail -5
else
  echo "  ! 找不到 APK，跳过安装（已有旧包，将只做日志体检）"
fi

echo
echo "########## 2. 记录当前是否已装 + 版本 ##########"
"$ADB" shell dumpsys package "$PKG" 2>/dev/null | grep -E "versionName|versionCode|firstInstallTime|lastUpdateTime" \
  | sed 's/^/  /' | head -6

echo
echo "########## 3. 清日志 → 冷启动 → 抓取 ##########"
"$ADB" logcat -c 2>/dev/null
echo "  已清空 logcat"
# 强制停止后重新启动，确保是「冷启动」路径（启动页可见）
"$ADB" shell am force-stop "$PKG" 2>/dev/null
sleep 1
echo "  冷启动中…"
"$ADB" shell am start -n "$PKG/$ACT" 2>&1 | grep -iE "error|warning|Starting" | head -3

# 关键：启动页→页面有十几秒，10s 采样足够覆盖全过程
sleep 12

echo
echo "--- 3.1 启动页调色板（r79 新增，最该看的）---"
"$ADB" logcat -d -s DshPalette:* 2>/dev/null | tail -8 | sed 's/^/  /'
if ! "$ADB" logcat -d -s DshPalette:* 2>/dev/null | grep -q .; then
  echo "  ! 无 DshPalette 日志 → 启动页可能没走到取色，或日志被过滤"
fi

echo
echo "--- 3.2 dsh 主题回读（r79 第二段）---"
"$ADB" logcat -d -s DshWebViewClient:* 2>/dev/null | grep -E "主题|theme" | tail -4 | sed 's/^/  /'

echo
echo "--- 3.3 启动流程主干 ---"
"$ADB" logcat -d -s MainActivity:* DshBootstrap:* 2>/dev/null | tail -14 | sed 's/^/  /'

echo
echo "--- 3.4 设置列表图标注入（r78 修复）---"
"$ADB" logcat -d 2>/dev/null | grep -iE "dsh-mkt|dsh-sync|navIcon|marketCell" | tail -6 | sed 's/^/  /'

echo
echo "--- 3.5 NAS 同步（r75）---"
"$ADB" logcat -d -s DshSync:* DshWebDavClient:* 2>/dev/null | tail -8 | sed 's/^/  /'

echo
echo "########## 4. 异常扫描（只看 app 自己的 tag）##########"
echo "--- ERROR/FATAL ---"
"$ADB" logcat -d 2>/dev/null | grep -E "^[0-9-]+ [0-9:.]+ +[0-9]+ +[0-9]+ [EF] " | tail -12 | sed 's/^/  /'
echo "--- app 进程崩溃记录 ---"
CRASH=$("$ADB" logcat -d -b crash 2>/dev/null | grep -c "$PKG")
if [ "$CRASH" -gt 0 ]; then
  echo "  ⚠ 发现 $CRASH 条 crash 记录："
  "$ADB" logcat -d -b crash 2>/dev/null | grep -A15 "$PKG" | head -30 | sed 's/^/    /'
else
  echo "  ✓ 无 crash"
fi

echo
echo "########## 5. 现场截图（用于视觉核对）##########"
SHOT="/d/tmp/ondevice-$(date +%H%M%S).png"
mkdir -p /d/tmp 2>/dev/null
if "$ADB" exec-out screencap -p > "$SHOT" 2>/dev/null && [ -s "$SHOT" ]; then
  echo "  ✓ 已保存: $SHOT ($(du -k "$SHOT" | cut -f1) KB)"
else
  echo "  ! 截图失败（可能需要 USB 或权限）"
fi

echo
echo "########## 完成 ##########"
echo "后续手动检查建议："
echo "  ① 切到深色系统：adb shell cmd uimode night yes → 强停重开看启动页"
echo "  ② 切回浅色：adb shell cmd uimode night no"
echo "  ③ 抓切换瞬间：adb logcat -c后 再执行上面两条，检查是否闪色"