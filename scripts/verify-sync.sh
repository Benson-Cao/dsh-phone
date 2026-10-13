#!/usr/bin/env bash
# ============================================================================
# verify-sync.sh —— 守卫：校验「本仓库（真源）→ termux-app（构建树）」是否一致
#
# 规则：
#   * java            本仓库 ≡ 构建树
#   * res/xml/*.xml   本仓库 ≡ 构建树（逐个文件，且要求对方**存在**）
#   * assets/         本仓库 ≡ 构建树
#   * AndroidManifest.xml **不校验**（见下）
#
# ⚠️ 为什么不再校验 manifest（2026-10-13 改）：
#   本仓库**不含** AndroidManifest.xml。它在 termux-app 里，由
#   01-modify-termux.sh 幂等改造（改包名 / 注入 LAUNCHER / 注册 FileProvider）。
#   过去这里拿它跟一个不存在的"③ 副本"比较，恒定报"不一致"——纯假漂移，
#   会让人以为真出了问题。manifest 的正确性由 01 脚本自己的 6.6 自检保证。
#
# 退出码：0 = 一致；1 = 有漂移。
# 设计原则：**路径不存在就跳过而不是失败** —— 只 clone 了本仓库时
#           （CI / 别人的电脑）仍能正常运行，不至于误报阻塞。
# ============================================================================
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC_ROOT="${DSH_SRC_ROOT:-$REPO_ROOT/android-java}"
BUILD_TREE="${DSH_BUILD_TREE:-D:/work/termux-app}"

SRC_JAVA="$SRC_ROOT/com/dsh/harness"
DST_JAVA="$BUILD_TREE/app/src/main/java/com/dsh/harness"

fail=0

# ---- 1) java ----
if [ -d "$SRC_JAVA" ] && [ -d "$DST_JAVA" ]; then
    if diff -rq "$SRC_JAVA" "$DST_JAVA" >/dev/null 2>&1; then
        echo "✓ java：真源 ≡ 构建树（$(ls -1 "$SRC_JAVA"/*.java 2>/dev/null | wc -l | tr -d ' ') 个文件）"
    else
        echo "✗ java 存在漂移："
        diff -rq "$SRC_JAVA" "$DST_JAVA" 2>&1 | sed 's/^/    /'
        fail=1
    fi
else
    echo "! 跳过 java 校验（真源或构建树不存在）"
fi

# ---- 2) res/xml ----
# manifest 引用了这些资源，缺任何一个都会**编译失败**，所以是硬校验：
# 真源里有，就必须构建树里也有，且内容一致。
if [ -d "$SRC_ROOT/res/xml" ] && [ -d "$BUILD_TREE/app/src/main/res/xml" ]; then
    for f in "$SRC_ROOT"/res/xml/*.xml; do
        [ -e "$f" ] || continue
        name="$(basename "$f")"
        dst="$BUILD_TREE/app/src/main/res/xml/$name"
        if [ ! -f "$dst" ]; then
            echo "✗ 构建树缺少 res/xml/$name（manifest/Provider 引用了它，会编译失败）"
            echo "    修复：bash scripts/01-modify-termux.sh $BUILD_TREE"
            fail=1
        elif cmp -s "$f" "$dst"; then
            echo "✓ res/xml/$name：真源 ≡ 构建树"
        else
            echo "✗ res/xml/$name 内容不一致："
            fail=1
        fi
    done
else
    echo "! 跳过 res/xml 校验（真源或构建树缺 res/xml）"
fi

# ---- 3) FileProvider 与 paths 必须成对（方案 C）----
# 历史上踩过：provider 注册在 01 脚本的"首次才跑"分支里，第二次构建就没了，
# 运行期getUriForFile 直接抛 IllegalArgumentException。
MANIFEST="$BUILD_TREE/app/src/main/AndroidManifest.xml"
PATHS="$BUILD_TREE/app/src/main/res/xml/dsh_file_paths.xml"
if [ -f "$MANIFEST" ]; then
    if grep -q "dsh.files" "$MANIFEST"; then
        if [ -f "$PATHS" ]; then
            echo "✓ FileProvider 已注册且 dsh_file_paths.xml 就位"
        else
            echo "✗ manifest 注册了 FileProvider，但缺 dsh_file_paths.xml —— 运行期会崩"
            fail=1
        fi
    else
        echo "! 未注册 dsh FileProvider（方案 C 未启用）"
    fi
fi

# ---- 4) assets ----
AS="$SRC_ROOT/assets"
AD="$BUILD_TREE/app/src/main/assets"
if [ -d "$AS" ] && [ -d "$AD" ]; then
    if diff -rq "$AS" "$AD" >/dev/null 2>&1; then
        echo "✓ assets：真源 ≡ 构建树"
    else
        #构建树里还有 bundle/patch.sh 等不属于本仓库的文件，
        # 所以只报本仓库有、构建树缺的（反向差异才是问题）。
        MISSING=""
        while IFS= read -r rel; do
            [ -n "$rel" ] && MISSING="$MISSING\n    缺 $rel"
        done < <(cd "$AS" && find . -type f | sed 's|^\./||' | while read -r r; do
            [ -f "$AD/$r" ] || echo "$r"
        done)
        if [ -n "$MISSING" ]; then
            echo "✗ assets 有文件未同步到构建树："
            printf '%b\n' "$MISSING"
            fail=1
        else
            echo "✓ assets：本仓库文件均已同步（构建树另有 bundle 等，正常）"
        fi
    fi
elif [ -d "$AS" ]; then
    echo "✗ 构建树缺少 assets 目录"
    fail=1
else
    echo "! 跳过 assets 校验（真源没有 assets/）"
fi

echo "---"
if [ "$fail" = "0" ]; then
    echo "==> 全部一致"
else
    echo "==> 存在漂移。修复：bash scripts/sync-from-source.sh"
fi
exit $fail