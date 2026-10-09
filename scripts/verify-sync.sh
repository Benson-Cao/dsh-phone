#!/usr/bin/env bash
# ============================================================================
# verify-sync.sh —— A-01 守卫：校验三副本是否一致
#
# 规则（与 sync-from-source.sh 对应）：
#   * java        ① ≡ ② ≡ ③
#   * manifest/res ③ ≡ ②
#
# 退出码：0 = 一致；1 = 有漂移。
# 设计原则：**路径不存在就跳过而不是失败** —— 这样在只 clone 了 ③ 的机器上
#           （CI / 别人的电脑，没有 ① 和 ②）仍能正常运行，不至于误报阻塞。
# ============================================================================
set -uo pipefail

SRC_ROOT="${DSH_SRC_ROOT:-D:/Program AI/DSH Phone/dsh-android-app/android-java}"
BUILD_TREE="${DSH_BUILD_TREE:-D:/work/termux-app}"
PUB_REPO="${DSH_PUB_REPO:-D:/work/dsh-harness-android}"

SRC_JAVA="$SRC_ROOT/com/dsh/harness"
D2="$BUILD_TREE/app/src/main/java/com/dsh/harness"
D3="$PUB_REPO/android-java/com/dsh/harness"

fail=0

# ---- 1) java 三副本 ----
if [ -d "$SRC_JAVA" ] && [ -d "$D2" ] && [ -d "$D3" ]; then
    if diff -rq "$SRC_JAVA" "$D2" >/dev/null 2>&1 && diff -rq "$SRC_JAVA" "$D3" >/dev/null 2>&1; then
        echo "✓ java：① ≡ ② ≡ ③"
    else
        echo "✗ java 三副本存在漂移："
        diff -rq "$SRC_JAVA" "$D2" 2>&1 | sed 's/^/    [①vs②] /'
        diff -rq "$SRC_JAVA" "$D3" 2>&1 | sed 's/^/    [①vs③] /'
        fail=1
    fi
else
    echo "! 跳过 java 校验（①/②/③ 路径不全 —— 只 clone 了发布仓库时属正常）"
fi

# ---- 2) manifest ----
M2="$BUILD_TREE/app/src/main/AndroidManifest.xml"
M3="$PUB_REPO/android-java/AndroidManifest.xml"
if [ -f "$M2" ] && [ -f "$M3" ]; then
    if diff -q "$M2" "$M3" >/dev/null 2>&1; then
        echo "✓ manifest：② ≡ ③"
    else
        echo "✗ AndroidManifest.xml 不一致："
        diff -q "$M2" "$M3" 2>&1 | sed 's/^/    /'
        fail=1
    fi
else
    echo "! 跳过 manifest 校验（② 或 ③ 缺 AndroidManifest.xml）"
fi

# ---- 3) networkSecurityConfig（C-05 引入的资源，两边都必须有）----
X2="$BUILD_TREE/app/src/main/res/xml/network_security_config.xml"
X3="$PUB_REPO/android-java/res/xml/network_security_config.xml"
if [ -f "$X2" ] && [ -f "$X3" ]; then
    if diff -q "$X2" "$X3" >/dev/null 2>&1; then
        echo "✓ network_security_config：② ≡ ③"
    else
        echo "✗ network_security_config.xml 不一致："
        diff -q "$X2" "$X3" 2>&1 | sed 's/^/    /'
        fail=1
    fi
elif [ -f "$X3" ]; then
    echo "✗ ② 缺少 res/xml/network_security_config.xml（manifest 引用了它，编译会失败）"
    fail=1
else
    echo "! 跳过 NSC 校验（③ 也没有该文件）"
fi

echo "---"
if [ "$fail" = "0" ]; then
    echo "==> 全部一致"
else
    echo "==> 存在漂移。修复：bash scripts/sync-from-source.sh"
fi
exit $fail