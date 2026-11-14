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

# 输出目录：优先 ~/storage/shared（adb 可直接 pull）；
# 未跑过 termux-setup-storage 时退回 $HOME（此时 PC 拉不到，脚本会明确提示）。
OUT_DIR="$HOME/storage/shared/dsh-build"
if ! mkdir -p "$OUT_DIR" 2>/dev/null; then
  OUT_DIR="$HOME/dsh-build"
  mkdir -p "$OUT_DIR"
  echo "  ! ~/storage/shared 不可用（未跑过 termux-setup-storage），输出退到: $OUT_DIR"
  echo "    这个目录 PC 的 adb 拉不到，跑完后请在 Termux 里手动拷到共享存储。"
fi

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

# ---- 世代与版本守卫：bundle 里的 dsh 必须与 App 端一致 ----
# 两层校验：
#   1) 世代：必须 0.2.x —— 0.1.x 的运行环境混进 v2.3 bundle 会「能启动但全错配」；
#   2) 精确版本：默认必须 = 0.2.2-rc1（真机 App 当前版本，2026-11-14 用户确认）。
#      为什么必须一致：App 重解包会**整体替换** home/node_modules ——
#      bundle 版本旧于 App，等于把用户升级好的 dsh 静默降级；版本新于 App
#      则可能与壳层（启动脚本/端口/注入约定）未验证组合。确认无误用 FORCE=1。
DSH_VER="$(node -p "require('$HOME_DIR/node_modules/@deepseek-ai/dsh/package.json').version" 2>/dev/null || echo unknown)"
EXPECTED_DSH_VER="${EXPECTED_DSH_VER:-0.2.2-rc.1}"
case "$DSH_VER" in
  0.2.*) echo "  ✓ dsh 世代正确: $DSH_VER" ;;
  *)
    echo "  ✗ Termux HOME 里的 dsh 版本 = $DSH_VER，不是 0.2.x —— 0.1.x 的运行环境不能打进 v2.3 的 bundle。"
    echo "    先在 Termux 里升级： cd ~ && npm i @deepseek-ai/dsh@0.2.2-rc1"
    echo "    确认无误要强行打包（不推荐）： FORCE=1 bash $0"
    [ "${FORCE:-0}" = "1" ] || exit 1 ;;
esac
if [ "$DSH_VER" != "$EXPECTED_DSH_VER" ]; then
  echo "  ✗ dsh 版本 $DSH_VER ≠ 预期 $EXPECTED_DSH_VER（App 端基准版本）。"
  echo "    重解包会整体替换 App 的 node_modules，版本不一致 = 静默升级/降级。"
  echo "    对齐版本： cd ~ && npm i @deepseek-ai/dsh@$EXPECTED_DSH_VER"
  echo "    确认要以此版本出包： FORCE=1 bash $0"
  [ "${FORCE:-0}" = "1" ] || exit 1
else
  echo "  ✓ dsh 版本与 App 基准一致: $DSH_VER"
fi

# ---- 打包清单：node/bash/lib/node_modules + （可选）openssh 客户端 ----
# v2.3 S-6.7 的 SSH 档案要在 App 内起 `ssh -L` 本地隧道，bundle 里必须有 ssh。
# Termux 的 openssh 装好后 ssh/ssh-keygen/scp/sftp 落在 usr/bin，
# 依赖的 libcrypto/libz 等已随整个 usr/lib 进包 —— 所以装了就能带。
# 没装也不报错（本机档案用不到 ssh），只在最后提示。
TAR_ARGS=( -C "$FILES_ROOT" usr/bin/node usr/bin/bash usr/lib home/node_modules )
HAS_SSH=0
for b in ssh ssh-keygen scp sftp; do
  if [ -x "$PREFIX/bin/$b" ]; then
    TAR_ARGS+=( "usr/bin/$b" )
    HAS_SSH=1
  fi
done
if [ "$HAS_SSH" = "1" ]; then
  echo "  ✓ openssh 客户端随包：ssh ssh-keygen scp sftp"
else
  echo "  ! Termux 里没有 openssh —— bundle 将不含 ssh（SSH 档案不可用）。"
  echo "    补装： pkg install -y openssh   然后重跑本脚本。"
fi

# 保留软链是硬要求：不用 -h / --dereference。
echo "==> tar（保留符号链接，不加 -h）"
tar -czf "$BUNDLE" "${TAR_ARGS[@]}"

echo "==> 打包完成: $BUNDLE"
ls -lh "$BUNDLE"
echo
echo "==> 自检（应看到 usr/bin/node、以及 libz.so.1 -> libz.so.1.x 这类软链）"
tar -tzvf "$BUNDLE" 2>/dev/null \
  | grep -E "usr/bin/(node|bash|ssh|ssh-keygen|scp|sftp)$|usr/lib/(libz\.so\.1|libicuuc\.so\.78|libsqlite3\.so|libreadline\.so\.8)$" \
  | head -12
echo
echo "==> 把上面的文件传到 PC，放进 termux-app/app/src/main/assets/dsh-bundle.tar.gz"
echo "    注意：传完后必须改 App 的 DshBootstrap.BUNDLE_REV，否则旧安装会跳过解包。"
echo "    （本次随包已约定 REV=v6-dsh-0.2.1-rc.5-ssh1，仓库里已同步改好）"
