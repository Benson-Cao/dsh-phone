#!/usr/bin/env bash
# 02-build-bundle.sh —— 在【手机 Termux】里打包 dsh 运行环境
#
# 用法： bash 02-build-bundle.sh
# 产出： ~/storage/shared/dsh-build/dsh-bundle.tar.gz
#
# ⚠️ 布局必须与 App 的 DshBootstrap 期望一致（Termux 约定）：
#     $PREFIX = files/usr   -> usr/bin/node、usr/bin/bash、usr/lib/*.so
#     $HOME   = files/home  -> home/node_modules/...
#   早期版本用 `-C "$PREFIX" bin/node` 打成了扁平的 bin/lib/node_modules，
#   而 App 按 usr/bin 去找 → 真机上 bash/node/.so 全部显示"缺失"。
#
# ⚠️ 必须保留符号链接（不要加 -h / --dereference）：
#   node/bash 的 NEEDED 是短名（libz.so.1、libsqlite3.so、libreadline.so.8、
#   libicu*.so.78），真实文件都带完整版本号，短名在 Termux 里正是软链。
#   GNU tar 默认保存软链；一旦用 --dereference，或者"先解包再重打包"，
#   软链就会丢 → 运行时 "library not found"。

set -euo pipefail

OUT_DIR="$HOME/storage/shared/dsh-build"
mkdir -p "$OUT_DIR"

PREFIX="${PREFIX:-$HOME/../usr}"          # /data/data/<pkg>/files/usr
HOME_DIR="${HOME:-$PREFIX/../home}"       # /data/data/<pkg>/files/home
FILES_ROOT="$(cd "$PREFIX/.." && pwd)"    # /data/data/<pkg>/files
BUNDLE="$OUT_DIR/dsh-bundle.tar.gz"

echo "==> 打包 dsh 环境"
echo "  PREFIX     = $PREFIX"
echo "  HOME       = $HOME_DIR"
echo "  FILES_ROOT = $FILES_ROOT"

[ -x "$PREFIX/bin/node" ]        || { echo "✗ 找不到可执行的 $PREFIX/bin/node"; exit 1; }
[ -x "$PREFIX/bin/bash" ]        || { echo "✗ 找不到可执行的 $PREFIX/bin/bash"; exit 1; }
[ -d "$HOME_DIR/node_modules" ]  || { echo "✗ 找不到 $HOME_DIR/node_modules"; exit 1; }

# 保留软链是硬要求：不用 -h / --dereference。
echo "==> tar（保留符号链接，不加 -h）"
tar -czf "$BUNDLE" \
  -C "$FILES_ROOT" \
  usr/bin/node \
  usr/bin/bash \
  usr/lib \
  home/node_modules

echo "==> 打包完成: $BUNDLE"
ls -lh "$BUNDLE"
echo
echo "==> 自检（应看到 usr/bin/node、以及 libz.so.1 -> libz.so.1.x 这类软链）"
tar -tzvf "$BUNDLE" 2>/dev/null \
  | grep -E "usr/bin/(node|bash)$|usr/lib/(libz\.so\.1|libicuuc\.so\.78|libsqlite3\.so|libreadline\.so\.8)$" \
  | head -10
echo
echo "==> 把上面的文件传到 PC，放进 termux-app/app/src/main/assets/dsh-bundle.tar.gz"
echo "    注意：传完后必须改 App 的 DshBootstrap.BUNDLE_REV，否则旧安装会跳过解包。"
