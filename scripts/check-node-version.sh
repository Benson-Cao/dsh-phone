#!/usr/bin/env bash
# check-node-version.sh —— 核对真机 bundle 里的 node 版本与 version.ini 是否一致
#
# 用法：
#   bash scripts/check-node-version.sh              # 自动挑一台已连设备
#   bash scripts/check-node-version.sh <serial>     # 指定 adb 序列号
#
# 为什么需要它：version.ini 的 [components].node_version 是「bundle 里实际装了什么」的
# 声明，而 version_code / DshBootstrap.BUNDLE_REV 的升级判断都间接依赖它。它一旦和真实
# 环境不一致，出包与排障都会照错的那份走 —— 2026-11-14 就出现过声明 22.22.2、真机
# 实跑 v24.18.0 的情况。写死注释提醒不管用，能一条命令复跑才有意义。
#
# 起 node 的两个必要环境变量（少一个都跑不起来，且报错信息都指向别处）：
#   LD_LIBRARY_PATH  —— bundle 里的 node 依赖 usr/lib 下的 .so（否则 libz.so.1 not found）
#   OPENSSL_CONF     —— 二进制把 openssl.cnf 路径硬编码成 /data/data/com.termux/...，
#                       不改指到 bundle 自己的 etc/tls 就会 "BIO_new_file: Permission denied"
#
# 为什么用 run-as 而不是 adb shell 直接跑：App 的私有目录对 adb shell 用户不可读，
# 只有 run-as（debuggable）能进。

set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

ADB="${ADB:-/d/AndroidSDK/platform-tools/adb.exe}"
[ -x "$ADB" ] || ADB="$(command -v adb || true)"
if [ -z "${ADB:-}" ] || [ ! -x "$ADB" ]; then
  echo "[SKIP] 找不到 adb（设 ADB=/path/to/adb）"
  exit 0
fi

PKG="${PKG:-com.dsh.harness}"
SERIAL="${1:-}"
if [ -z "$SERIAL" ]; then
  SERIAL="$("$ADB" devices 2>/dev/null | awk 'NR>1 && $2=="device"{print $1; exit}')"
fi
if [ -z "$SERIAL" ]; then
  echo "[SKIP] 没有已连接的设备（先 adb connect <ip:port>）"
  exit 0
fi

DECLARED="$(sed -n 's/^[[:space:]]*node_version[[:space:]]*=[[:space:]]*\([^#]*\).*/\1/p' "$ROOT/version.ini" | head -1 | tr -d '[:space:]')"
if [ -z "$DECLARED" ]; then
  echo "[FAIL] version.ini 里没有 node_version"
  exit 1
fi

ACTUAL="$("$ADB" -s "$SERIAL" shell \
  "run-as $PKG sh -c 'cd files && OPENSSL_CONF=\$PWD/usr/etc/tls/openssl.cnf LD_LIBRARY_PATH=\$PWD/usr/lib ./usr/bin/node -v'" \
  2>&1 | tr -d '\r' | tail -1)"

echo "设备      : $SERIAL"
echo "version.ini: $DECLARED"
echo "真机实测  : ${ACTUAL:-<读取失败>}"

ACTUAL_NUM="${ACTUAL#v}"
if [ -z "$ACTUAL_NUM" ] || [ "$ACTUAL_NUM" = "$ACTUAL" ]; then
  echo "[SKIP] 读不到版本（设备离线 / 未安装 / 未 run-as 授权），本条不判定"
  exit 0
fi

if [ "$ACTUAL_NUM" = "$DECLARED" ]; then
  echo "[PASS] 一致"
  exit 0
fi
echo "[FAIL] 不一致：bundle 实跑 $ACTUAL_NUM，version.ini 声明 $DECLARED"
echo "       出包前必须对齐（改 version.ini，或重打 bundle），否则版本判定依据是错的。"
exit 1
