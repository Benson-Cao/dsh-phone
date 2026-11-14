#!/usr/bin/env bash
# ============================================================================
# 02b-harvest-bundle.sh —— 在【PC】上从真机 App 收割 dsh 运行环境为 bundle
#
# 背景：为什么不用 02-build-bundle.sh（手机 Termux 打包）？
#   1. com.termux 里根本没有 dsh 环境（2026-11-14 实测 unknown）；
#   2. 就算全新 npm i @deepseek-ai/dsh@0.2.2-rc.1，也拿不到 r152/r161 的
#      bundle 级修复（flock 守卫、fontkit module.mjs、domino 整包、
#      settings-account 的 package.json、link()→rename 降级）——
#      那些是当年 PC 侧组包时手工塞进 tar 的，不在 patch.sh 里；
#   3. 而 App（com.dsh.harness，debug 构建）的私有目录里是
#      「正在真机运行的 0.2.2-rc.1 + 全部修复 + 与壳层验证过的组合」
#      —— 权威数据源，零漂移。
#
# 前提：手机上装的必须是 debug 包（run-as 才可用）。
#
# 用法：
#   bash 02b-harvest-bundle.sh [输出.tar.gz]
#   SERIAL=<serial> ADB=<adb路径> bash 02b-harvest-bundle.sh
#   EXPECTED_DSH_VER=0.2.2-rc.1 FORCE=1 bash 02b-harvest-bundle.sh   # 版本不一致强收
#
# 产出：默认 D:/work/dsh-bundle-<ver>-harvest.tar.gz（喂给 00-build-apk.sh 的 BUNDLE=）
# ============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ADB="${ADB:-D:/AndroidSDK/platform-tools/adb.exe}"
EXPECTED_DSH_VER="${EXPECTED_DSH_VER:-0.2.2-rc.1}"
OUT="${1:-}"

[ -x "$ADB" ] || { echo "✗ 找不到 adb：$ADB"; exit 1; }

# ---- 1) 设备 ----
SERIAL="${SERIAL:-}"
if [ -z "$SERIAL" ]; then
  SERIAL="$("$ADB" devices | awk '$2=="device"{print $1; exit}')"
fi
[ -n "$SERIAL" ] || { echo "✗ 无可用 adb 设备（先 adb connect <ip:port>）"; exit 1; }
echo "==> 设备: $SERIAL"

# ---- 2) run-as 可用性（debug 构建才有效）----
# 注：adb shell 会把参数拼成一条命令交设备端 sh 解析，所以这里必须
# 把完整命令作为【单个参数】传（引号在设备端才展开），拆参数传会
# 把 sh -c 的脚本截断成单令牌（首版踩过：cat 读不到文件 → 误判未解包）。
R() { "$ADB" -s "$SERIAL" shell "run-as com.dsh.harness $1"; }

if ! R 'true' >/dev/null 2>&1; then
  echo "✗ run-as com.dsh.harness 不可用 —— 手机上装的是 release 包，收割前提不成立。"
  exit 1
fi
echo "  ✓ run-as 可用（debug 构建）"

# ---- 3) App 内 dsh 版本（权威来源：package.json 本体）----
# ⚠️ Windows 版 adb 的 shell 输出带 \r：不洗掉会污染版本号 → 拼 tar 路径全盘静默失效。
PKGJSON="$(R 'cat files/home/node_modules/@deepseek-ai/dsh/package.json' 2>/dev/null || true)"
PKGJSON="$(printf '%s' "$PKGJSON" | tr -d '\r')"
APP_DSH_VER="$(printf '%s' "$PKGJSON" | sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)"
[ -n "$APP_DSH_VER" ] || { echo "✗ 读不到 App 内 dsh 版本（App 未解包过？先在手机上跑一次 App）"; exit 1; }
echo "  ✓ App 内 dsh 版本: $APP_DSH_VER"

if [ "$APP_DSH_VER" != "$EXPECTED_DSH_VER" ]; then
  echo "  ✗ App 内 dsh ($APP_DSH_VER) ≠ 预期 ($EXPECTED_DSH_VER)。"
  echo "    确认无误要强行收割（比如故意要打包旧版）： FORCE=1 重跑"
  [ "${FORCE:-0}" = "1" ] || exit 1
fi

# ---- 4) 空间预检（tar 落在 App files/ 里，约 250MB）----
FREE_KB="$(R 'df -k files 2>/dev/null | tail -1' 2>/dev/null | tr -d '\r' | awk '{print $4}')"
if [ -n "$FREE_KB" ] && [ "$FREE_KB" -lt 600000 ] 2>/dev/null; then
  echo "  ✗ App 数据分区可用空间不足（${FREE_KB}KB < 600MB），先清存储。"
  exit 1
fi

# ---- 5) App 内打包（toybox tar 保留软链；清单与 02 脚本同款）----
echo "==> App 内 tar（node + bash + usr/lib + home/node_modules）"
R 'rm -f files/dsh-harvest.tar.gz' 2>/dev/null || true
R 'toybox tar -czf files/dsh-harvest.tar.gz -C files usr/bin/node usr/bin/bash usr/lib home/node_modules' \
  || { echo "✗ App 内打包失败"; exit 1; }
SZ="$(R 'ls -l files/dsh-harvest.tar.gz' 2>/dev/null | tr -d '\r' | awk '{print $5}')"
echo "  ✓ 打包完成: ${SZ} bytes"

# ---- 6) 拉回 PC（exec-out 二进制流，不经 sdcard）----
if [ -z "$OUT" ]; then
  OUT="D:/work/dsh-bundle-${APP_DSH_VER}-harvest.tar.gz"
fi
echo "==> 拉回 PC: $OUT"
"$ADB" -s "$SERIAL" exec-out run-as com.dsh.harness cat files/dsh-harvest.tar.gz > "$OUT"

# ---- 7) 清理手机侧临时文件 ----
R 'rm -f files/dsh-harvest.tar.gz' 2>/dev/null || true
echo "  ✓ 已清理手机侧临时 tar"

# ---- 8) PC 侧完整性校验 ----
[ -s "$OUT" ] || { echo "✗ 产物为空"; exit 1; }
echo "==> 校验（体积 / 清单 / 软链 / dsh 版本）"
ls -lh "$OUT" | awk '{print "  size: " $5}'

# Git Bash 的 GNU tar 会把 "D:/..." 解析成「主机 D:」——tar 操作一律用 /d/ 形式
OUT_TAR="$(printf '%s' "$OUT" | sed 's|^\([A-Za-z]\):/|\1/|;s|^\([A-Za-z]\)/|/\1/|')"
case "$OUT_TAR" in /*) ;; *) OUT_TAR="/$OUT_TAR" ;; esac

LIST_FILE="$OUT.list.txt"
tar -tzf "$OUT_TAR" > "$LIST_FILE"
echo "  ✓ 清单落盘: $(wc -l < "$LIST_FILE" | tr -d ' ') 个成员"
for p in usr/bin/node usr/bin/bash usr/bin/ssh \
         home/node_modules/@deepseek-ai/dsh/package.json; do
  if grep -qx "$p" "$LIST_FILE"; then
    echo "  ✓ $p"
  else
    echo "  · 缺 $p（ssh 缺属预期：批 C 再注入）"
  fi
done
SYML="$(tar -tvzf "$OUT_TAR" 2>/dev/null | grep -E '^l.*usr/lib/libz\.so\.1 -> ' | head -1 || true)"
[ -n "$SYML" ] && echo "  ✓ 软链保留样例: ${SYML#*usr/lib/}" || echo "  ⚠ 未见到 libz.so.1 软链 —— 检查 tar 是否被解引用"
rm -f "$LIST_FILE"

REALVER="$(tar -xzOf "$OUT_TAR" home/node_modules/@deepseek-ai/dsh/package.json \
  | sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)"
if [ "$REALVER" = "$APP_DSH_VER" ]; then
  echo "  ✓ tar 内 dsh 版本一致: $REALVER"
else
  echo "  ✗ tar 内 dsh 版本 $REALVER ≠ App 版本 $APP_DSH_VER —— 收割不完整，不要用这个包"
  exit 1
fi

echo
echo "==> 完成: $OUT"
echo "    下一步： BUNDLE=$OUT bash scripts/00-build-apk.sh"
