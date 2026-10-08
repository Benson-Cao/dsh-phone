#!/usr/bin/env bash
# patch.sh —— 内置补丁，打进 APK assets，首启由 DshBootstrap 调用
#
# 重要：这些补丁针对 dsh v0.1 预览版，接口可能变化。
#       每次 dsh 升级请重新核对下面每一项是否仍需打。
set -e

PREFIX="${PREFIX:-$HOME/../usr}"
HM="$HOME"

echo "==> 应用 dsh 运行环境补丁"

# 补丁 1：node-pty 预编译二进制就位确认
PTY="$HM/node_modules/node-pty/prebuilds/android-arm64/pty.node"
if [ -f "$PTY" ]; then
  echo "  ✓ pty.node 已就位 ($PTY)"
else
  echo "  ! 未找到 pty.node，请确认 bundle 包含 node-pty"
fi

# 补丁 2：文件锁 flock.js 在部分 Android 内核不兼容，做 no-op 保护
FLOCK="$HM/node_modules/@deepseek-ai/node-addon-system/lib/flock.js"
if [ -f "$FLOCK" ]; then
  sed -i 's/flock(/__noop_flock(/g' "$FLOCK" 2>/dev/null || true
  echo "  ✓ flock.js 已做兼容处理"
fi

# 补丁 3：会话持久化目录确保存在
mkdir -p "$HM/node_modules/@deepseek-ai/dsh-session-persistence-jsonl/lib"
echo "  ✓ 会话持久化目录就绪"

echo "==> 补丁完成"
