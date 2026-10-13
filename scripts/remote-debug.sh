#!/usr/bin/env bash
# ============================================================================
# remote-debug.sh —— 一条命令：连设备 → 装 APK → 冷启 → 开 WebView 远程调试
#                    → 拉全量 dsh-web.log（诊断页只给末 25 行，这是补上的那一半）
#
# 用法:
#   bash scripts/remote-debug.sh <设备IP:端口> [APK路径]
#   例: bash scripts/remote-debug.sh 192.168.1.88:5555
#
# 为什么要这个脚本（r107 的教训）:
#   ① vivo 会吞第三方 app 的 logcat → 页面报什么错完全看不见。r107 在
#      MainActivity.onCreate 开了 setWebContentsDebuggingEnabled(true)，
#      这里把 adb forward 和 CDP 客户端接上，就能直接读 DOM / console / 跑 JS。
#   ② 启动页诊断只打印 dsh-web.log 的**末 25 行**，而"启动器被反复拉起"这类
#      问题恰恰要靠**统计**（start_dsh 出现几次）和**第一处报错**才看得出来 ——
#      本脚本把整份日志拉回来并当场做这三项统计。
#
# 前置条件（手机上）:
#   开发者选项 → 无线调试 打开。首次需要：
#     · 若手机支持「无线调试」：设置里点「使用配对码配对设备」拿到 IP:配对端口+6 位码，
#       先跑一次  adb pair <IP>:<配对端口> <6位码>
#     · 老式做法（需先 USB 一次）：adb tcpip 5555，之后 adb connect <IP>:5555
# ============================================================================
set -u

ADB="${ADB:-/d/AndroidSDK/platform-tools/adb.exe}"
PKG="com.dsh.harness"
ACT=".MainActivity"
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
OUT_DIR="${DSH_ONDEVICE_DIR:-/d/work/ondevice}"
CDP_PORT="${CDP_PORT:-9222}"

DEV="${1:-}"
APK="${2:-}"
[ -z "$DEV" ] && { echo "用法: bash $0 <设备IP:端口> [APK路径]"; exit 1; }
if [ -z "$APK" ]; then
  APK="$(ls -t "/d/Program AI/DSH Phone/apk-out/"DSH-Phone-*-arm64-v8a-debug.apk 2>/dev/null | head -1)"
fi
[ -f "$APK" ] || { echo "✗ 找不到 APK：${APK:-<空>}"; exit 1; }
mkdir -p "$OUT_DIR" 2>/dev/null

echo "########## 0. 连接设备 ##########"
if "$ADB" devices | awk '{print $1}' | grep -qx "$DEV"; then
  echo "  ✓ 已在连接列表: $DEV"
else
  "$ADB" connect "$DEV" 2>&1 | tail -2
fi
if ! "$ADB" devices | grep -q "^$DEV[[:space:]]*device"; then
  echo "✗ 连不上 $DEV。逐条查："
  echo "   ① 手机与电脑是否同一 Wi-Fi；② 无线调试是否开着（切后台可能自动关）；"
  echo "   ③ 若提示 unauthorized → 看手机屏幕点「允许」；"
  echo "   ④ 首次配对要 adb pair <IP>:<配对端口> <6位码>（配对端口 ≠ 连接端口）"
  exit 1
fi
echo "  型号: $("$ADB" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
echo "  Android: $("$ADB" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')"
echo "  abi: $("$ADB" shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')"

echo
echo "########## 1. 安装 ##########"
echo "  APK: $APK（$(du -m "$APK" | cut -f1) MB，约 270MB 要传一会儿）"
"$ADB" install -r -t "$APK" 2>&1 | tail -4
echo "  --- 安装后版本 ---"
"$ADB" shell dumpsys package "$PKG" 2>/dev/null | grep -E "versionName|versionCode|lastUpdateTime" | sed 's/^/  /' | head -4

echo
echo "########## 2. 冷启动 ##########"
"$ADB" shell am force-stop "$PKG" 2>/dev/null
"$ADB" logcat -c 2>/dev/null
"$ADB" shell am start -n "$PKG/$ACT" 2>&1 | grep -iE "Starting|Error" | head -2

echo
echo "########## 3. 等 WebView 起来 → 建 CDP 通道 ##########"
SOCK=""; PID=""
for i in $(seq 1 40); do
  PID="$("$ADB" shell pidof "$PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}')"
  if [ -n "$PID" ]; then
    SOCK="$("$ADB" shell cat /proc/net/unix 2>/dev/null | tr -d '\r' \
            | grep -o "webview_devtools_remote[_0-9]*" | head -1)"
    [ -n "$SOCK" ] && break
  fi
  sleep 2
done
if [ -z "$SOCK" ]; then
  echo "  ✗ 没找到 webview_devtools_remote 套接字（第 ${i} 次探测）"
  echo "    可能原因：① 本包没开 setWebContentsDebuggingEnabled；"
  echo "             ② WebView 还没建（启动页仍在跑，dsh 就绪后才会建）；"
  echo "             ③ 系统 WebView 被禁用/需要更新。"
else
  echo "  ✓ 套接字: $SOCK (app pid=$PID)"
  "$ADB" forward --remove tcp:$CDP_PORT >/dev/null 2>&1
  "$ADB" forward tcp:$CDP_PORT "localabstract:$SOCK" 2>&1 | tail -1
  echo "  ✓ 端口转发 tcp:$CDP_PORT -> $SOCK"
fi

echo
echo "########## 4. 可调试页面清单（CDP）##########"
if [ -n "$SOCK" ]; then
  node "$HERE/cdp-eval.js" --base "http://127.0.0.1:$CDP_PORT" --list 2>&1 | head -14 | sed 's/^/  /'
else
  echo "  (跳过：没有通道)"
fi

echo
echo "########## 5. 拉全量 dsh-web.log（诊断页看不到的那部分）##########"
TS="$(date +%H%M%S)"
LOG="$OUT_DIR/dsh-web.log.$TS"
if "$ADB" exec-out run-as "$PKG" cat files/home/dsh-web.log > "$LOG" 2>/dev/null && [ -s "$LOG" ]; then
  echo "  ✓ 已保存: $LOG（$(wc -l < "$LOG") 行 / $(du -k "$LOG" | cut -f1) KB）"
  ST="$(grep -c '"state":"start_dsh"' "$LOG" 2>/dev/null || echo 0)"
  EADDR="$(grep -c 'EADDRINUSE' "$LOG" 2>/dev/null || echo 0)"
  SAFLN="$(grep -c 'dsh-saf' "$LOG" 2>/dev/null || echo 0)"
  echo "  --- 三项统计（诊断页不提供的）---"
  echo "   启动器运行次数 (start_dsh): $ST"
  echo "   端口互撞 (EADDRINUSE):      $EADDR"
  echo "   SAF shim 装载行:            $SAFLN"
  echo "  --- 第一处报错（最容易被淹没的）---"
  grep -n -m1 -E "^Error:|抛出|uncaught|Exception" "$LOG" | cut -c1-200 | sed 's/^/   /' \
    || echo "   (整份日志没有 Error:/Exception)"
  echo "  --- 末 20 行 ---"
  tail -20 "$LOG" | sed 's/^/   /'
else
  echo "  ✗ 拉取失败（run-as 需要 debug 包；release 包请用 App 内「导出数据」面板）"
  echo "    排查: $ADB shell run-as $PKG ls files/home"
fi

echo
echo "########## 完成 · 接下来怎么用 ##########"
cat <<EOF
  ① 看 console / 未捕获异常（替代被吞掉的 logcat）：
       node scripts/cdp-eval.js --console 20
  ② 直接问页面要答案：
       node scripts/cdp-eval.js "location.href"
       node scripts/cdp-eval.js "document.querySelectorAll('[data-dsh-]').length"
       node scripts/cdp-eval.js --file /tmp/check.js
  ③ 想用 Chrome 的图形界面：chrome://inspect → 「配置网络目标」加 localhost:$CDP_PORT
     （端口转发已建好，不需要在手机上点任何东西）
  ④ 日志已存 $OUT_DIR/ —— 要复盘把这份整文件给我，别再依赖启动页那 25 行。
EOF
