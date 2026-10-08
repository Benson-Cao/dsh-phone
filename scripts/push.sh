#!/usr/bin/env bash
# push.sh —— 推送到 GitHub，**不需要手工提供令牌**
#
# 原理：全局 credential.helper = helper-selector（Git Credential Manager），
# GCM 里已缓存 GitHub 的 OAuth 凭据（gho_ 开头的长期令牌），会自动取用与刷新。
# 所以只要走 HTTPS + GCM 就能直接 push，不必再往命令行里贴 token。
#
# 用法：
#   bash scripts/push.sh                # 推送当前分支
#   bash scripts/push.sh --force        # 强推（谨慎）
#
# ⚠️ 代理端口会变，每次都要现查：
#    env | grep -i '^https_proxy='
#    本脚本自动读取环境里的代理；若 git 直连不通，可用 PROXY=host:port 覆盖。
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$HERE/.." && pwd)"
cd "$REPO_DIR"

PROXY="${PROXY:-$(env | grep -i '^https_proxy=' | head -1 | cut -d= -f2-)}"
GOPTS=()
[ -n "$PROXY" ] && GOPTS=(-c "http.proxy=$PROXY" -c "https.proxy=$PROXY")

if [ -z "$(git remote get-url origin 2>/dev/null)" ]; then
  echo "✗ 没有 origin 远端。先执行："
  echo "    git remote add origin https://github.com/<owner>/<repo>.git"
  exit 1
fi

echo "==> 分支状态"
git status -sb | head -5
git remote -v

echo
echo "==> 推送${1:-}"
git "${GOPTS[@]}" push -u origin HEAD "${1:-}"

echo
echo "✓ 完成：$(git remote get-url origin)"
echo "  下次直接 bash scripts/push.sh 即可（GCM 会自动提供并刷新凭据）"