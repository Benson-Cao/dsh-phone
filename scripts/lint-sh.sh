#!/usr/bin/env bash
# lint-sh.sh —— shell 脚本提交前自检（maintainability-plan.md §五）
#
# 用法：
#   bash scripts/lint-sh.sh              # 检查 scripts/ 下所有 .sh
#   bash scripts/lint-sh.sh path/to.sh   # 检查指定文件
#   bash scripts/lint-sh.sh --fix-hints  # 额外提示常见坑（本项目踩过的）
#
# 两级策略：
#   有 shellcheck → 跑 shellcheck（真正的静态分析）
#   没有         → 退回 `bash -n`（只查语法）
# 之所以不强制装 shellcheck：这是提交前的便利检查，不是门禁；
# 在没装的机器上应该降级可用，而不是直接报错拒绝。

set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
HINTS=0
[ "${1:-}" = "--fix-hints" ] && { HINTS=1; shift; }

if [ $# -gt 0 ]; then
  FILES="$*"
else
  FILES="$(find "$HERE" -maxdepth 1 -name '*.sh' | sort)"
fi

if [ -z "$FILES" ]; then
  echo "没有可检查的 .sh 文件"; exit 0
fi

N=$(echo "$FILES" | wc -l)
echo "==> 检查 $N 个脚本"

# ---- Level 1: 语法（bash -n），任何机器都能跑 ----
SYNTAX_FAIL=0
echo
echo "---- bash -n 语法检查 ----"
for f in $FILES; do
  if bash -n "$f" 2>/tmp/dsh-lint-err.$$; then
    printf '  \033[32m✓\033[0m %s\n' "$(basename "$f")"
  else
    printf '  \033[31m✗\033[0m %s\n' "$(basename "$f")"
    sed 's/^/      /' /tmp/dsh-lint-err.$$ 2>/dev/null
    SYNTAX_FAIL=$((SYNTAX_FAIL+1))
  fi
done
rm -f /tmp/dsh-lint-err.$$

# ---- Level 2: shellcheck（装了才跑）----
echo
if command -v shellcheck >/dev/null 2>&1; then
  echo "---- shellcheck ----"
  SC_FAIL=0
  for f in $FILES; do
    if out="$(shellcheck -S warning "$f" 2>&1)"; then
      printf '  \033[32m✓\033[0m %s\n' "$(basename "$f")"
    else
      printf '  \033[33m!\033[0m %s\n' "$(basename "$f")"
      echo "$out" | head -20 | sed 's/^/      /'
      SC_FAIL=$((SC_FAIL+1))
    fi
  done
  echo "  shellcheck 有警告的文件数: $SC_FAIL"
else
  echo "---- shellcheck ----"
  echo "  ! 未安装 shellcheck，只做了语法检查。"
  echo "    装它能查出更多问题（未定义变量、危险写法等）："
  echo "      winget install koalaman.shellcheck   # Windows"
  echo "      apt install shellcheck               # Linux/WSL"
fi

# ---- 本项目踩过的坑提示 ----
if [ "$HINTS" = "1" ]; then
  echo
  echo "---- 本项目高频坑自查 ----"
  for f in $FILES; do
    b="$(basename "$f")"
    # 跳过 lint 自身：它的检查规则就写在文件里，匹配自己必然全中
    [ "$b" = "lint-sh.sh" ] && continue
    # 只看真正的代码行：注释里经常**引用**这些坑来警告（02-build-bundle.sh
    # 就是用注释写"不要加 -h"），留着会把警告本身报成违规。
    CODE="$(grep -vE '^[[:space:]]*#' "$f" 2>/dev/null)"
    # 踩过的坑 1：写死 versionCode，应走 version.ini。
    #   排除 `ini_get version_code 999` 这类**回退默认值** —— 那是刻意兜底。
    if printf '%s' "$CODE" | grep -vE 'ini_get version_code|version_code .*fallback' \
       | grep -qE 'versionCode[[:space:]]+[0-9]{3,}'; then
      printf '  \033[33m!\033[0m %s 里出现硬编码 versionCode —— 应从 version.ini 读\n' "$b"
    fi
    # 踩过的坑 2：tar 加 -h/--dereference 会丢软链 → 运行时 library not found。
    #   两条避坑：① 必须是 tar 命令本身（排除 du -h 这种 human-readable，
    #   实测就是 du -h 被误报的）；② -h 必须紧跟 tar，不许隔太远。
    if printf '%s' "$CODE" \
       | grep -qE '(^|[;&|][[:space:]]*)tar[[:space:]][^|;&]*([[:space:]])(-{1,2}h|--dereference)([[:space:]]|$)'; then
      printf '  \033[33m!\033[0m %s 里 tar 用了 -h/--dereference —— 会丢符号链接！\n' "$b"
    fi
    # 踩过的坑 3：head 提前关管道导致 tar SIGPIPE 误判
    if printf '%s' "$CODE" | grep -qE 'tar[^|]*\|[[:space:]]*(head|grep[[:space:]]+-m)'; then
      printf '  \033[33m!\033[0m %s 里 tar 管道接 head/grep -m —— 可能误判为失败\n' "$b"
    fi
  done
  echo "  （没有输出= 没有命中上述坑）"
fi

echo
if [ "$SYNTAX_FAIL" -gt 0 ]; then
  printf '\033[31m%d 个脚本有语法错误，必须修。\033[0m\n' "$SYNTAX_FAIL"
  exit 1
fi
printf '\033[32m语法检查全部通过。\033[0m'
exit 0