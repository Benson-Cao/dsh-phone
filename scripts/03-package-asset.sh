#!/usr/bin/env bash
# 03-package-asset.sh —— 只负责：把 dsh bundle + patch.sh 放进 APK 的 assets
#
# 用法：
#   bash scripts/03-package-asset.sh [termux-app 仓库路径]
#   BUNDLE=/path/dsh-bundle.tar.gz bash scripts/03-package-asset.sh
#
# 输入：
#   $BUNDLE            dsh 运行环境 tar.gz（不传则只更新 patch.sh）
#   $1 / $REPO         termux-app 工程路径（默认 ./termux-app）
# 输出：
#   $REPO/app/src/main/assets/{dsh-bundle.tar.gz, patch.sh}
#
# 为什么要单独拆出来（maintainability-plan.md §二）：
#   bundle 有 700MB，是整个流程里最慢的一步。混在 00-build-apk.sh 里意味着
#   "只想重打 assets" 也得重走 clone + 改造 + 编译。拆开后：
#     - 重打 assets： bash scripts/03-package-asset.sh && ./gradlew :app:assembleDebug
#     - 00-build-apk.sh 仍会调它（幂等），流程对调用方无感。

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="${1:-${REPO:-termux-app}}"
BUNDLE="${BUNDLE:-}"

ASSETS="$REPO/app/src/main/assets"

echo "==> 打包 assets"
echo "    目标: $ASSETS"

if [ ! -d "$REPO/app" ]; then
  echo "✗ 找不到 $REPO/app —— 请先 clone termux-app 或用 REPO= 指定路径"
  exit 1
fi

mkdir -p "$ASSETS"

# ---- patch.sh：总是从本仓库覆盖（它是本仓库的真相源）----
if [ -f "$HERE/patch.sh" ]; then
  cp -f "$HERE/patch.sh" "$ASSETS/patch.sh"
  echo "  ✓ patch.sh -> assets/patch.sh"
else
  echo "  ! scripts/patch.sh 不存在，assets 里的旧 patch.sh 保持不变"
fi

# ---- dsh-bundle.tar.gz ----
if [ -n "$BUNDLE" ]; then
  if [ ! -f "$BUNDLE" ]; then
    echo "✗ BUNDLE 指向的文件不存在: $BUNDLE"; exit 1
  fi
  SRC_SZ=$(du -h "$BUNDLE" | cut -f1)
  echo "  ↓拷贝 bundle ($SRC_SZ)，这是最慢的一步，请耐心等待…"
  # 注意用 cp -f 而不是 mv：bundle 通常在共享目录/手机传过来的位置，
  # 删掉源文件会让「重打一次」变得不可能。
  cp -f "$BUNDLE" "$ASSETS/dsh-bundle.tar.gz"
  echo "  ✓ dsh-bundle.tar.gz -> assets/ ($(du -h "$ASSETS/dsh-bundle.tar.gz" | cut -f1))"
elif [ -s "$ASSETS/dsh-bundle.tar.gz" ]; then
  echo "  · 未传 BUNDLE，保留已有 dsh-bundle.tar.gz ($(du -h "$ASSETS/dsh-bundle.tar.gz" | cut -f1))"
else
  echo "  ! 没有 BUNDLE 且 assets 里也没有 —— 放一个合法空 tar 占位"
  echo "    ⚠️ 编出来是【空壳 APK】：能装能开，但会停在自检页。"
  echo "    完整版请在手机 Termux 跑 02-build-bundle.sh 后带 BUNDLE= 重跑本脚本。"
  # DshBootstrap 首启会读 assets 里的 bundle，空文件会直接崩，必须给合法空 tar。
  tar -czf "$ASSETS/dsh-bundle.tar.gz" -T /dev/null 2>/dev/null || true
  echo "  ✓ 已放空占位 tar"
fi

# ---- 自检：确认 assets 里的东西真的可用 ----
echo "==> 自检"
if [ -f "$ASSETS/patch.sh" ]; then
  echo "  ✓ patch.sh 存在 ($(wc -c < "$ASSETS/patch.sh") 字节)"
else
  echo "  ! patch.sh 缺失 —— 解包后运行时补丁不会执行（不影响启动）"
fi
BZ="$ASSETS/dsh-bundle.tar.gz"
# 临时文件（存条目列表）；trap 保证任何退出路径都清掉。
TMP_ENTRIES="$(mktemp "${TMPDIR:-/tmp}/dsh-entries.XXXXXX" 2>/dev/null || echo "")"
[ -n "$TMP_ENTRIES" ] || TMP_ENTRIES="$ASSETS/.dsh-entries.tmp"
cleanup() { [ -n "${TMP_ENTRIES:-}" ] && rm -f "$TMP_ENTRIES"; return 0; }
trap cleanup EXIT
if [ -s "$BZ" ]; then
  # ⚠️ 用 `gzip -t` 而不是 `tar -tzf` 做完整性校验：
  #   tar -z 要把整个 700MB 解压流走一遍才能列完，在慢盘上会耗几分钟，
  #   看起来就像"卡住/损坏"。gzip -t 只校验流与 CRC，快得多且同样权威。
  #   （实测踩过：195MB bundle 被误判为损坏，其实是 tar 列目录太慢。）
  if gzip -t "$BZ" 2>/dev/null; then
    echo "  ✓ dsh-bundle.tar.gz gzip 流完整（$(du -h "$BZ" | cut -f1)）"
    # 布局自检：App 按 usr/bin、home/node_modules 去找，扁平静默期最难查。
    # ⚠️ 两个坑都在这行：
    #   1) `tar ... | head -N`：head 提前关闭管道 → tar 收 SIGPIPE 非零退出 → 误报"读取失败"；
    #   2) 完整 `tar -tzf` 列全部条目：700MB 要几分钟（实测 195MB 就很慢）。
    # 走 python 只读前若干条目判断，秒级完成。
    PY=""
    for c in python3 python; do command -v "$c" >/dev/null 2>&1 && { PY="$c"; break; }; done
    if [ -z "$PY" ]; then
      echo "  ! 没有 python，跳过布局自检（布局错只能在真机暴露，建议装 python）"
    else
      LAYOUT="$("$PY" "$HERE/.check-bundle-layout.py" "$BZ" 2>/dev/null || echo "BAD")"
      case "$LAYOUT" in
        OK)      echo "  ✓ 布局正确（含 usr/bin/node）" ;;
        FLAT)    echo "  ✗ 布局错误：有 home/node_modules 但没有 usr/bin/node（扁平静默期最难查的坑）"
                 echo "     必须用 04-repack-bundle-layout.py 重排后再构建！"
                 exit 1 ;;
        BAD*)    echo "  ✗ bundle 无法作为 tar 读取：$LAYOUT"
                 exit 1 ;;
        UNKNOWN) echo "  ✗ 布局可疑：既无 usr/bin/node 也无 home/node_modules —— 多半不是本项目打的包"
                 exit 1 ;;
      esac
    fi
  else
    echo "  ✗ bundle gzip 流损坏/被截断（gzip -t 失败）—— 重新拷贝，别用它构建"
    exit 1
  fi
fi

echo "==> 完成。接着可以编译："
echo "    cd $REPO && ./gradlew :app:assembleDebug --offline"