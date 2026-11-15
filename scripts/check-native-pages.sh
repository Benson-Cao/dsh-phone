#!/usr/bin/env bash
# check-native-pages.sh —— 原生页「三处声明」一致性校验
#
# 为什么需要这个脚本：
# 一个原生页要在**三个地方**同时存在才算真的可用，而漏掉任何一处都**不会报错**：
#   1. 源码      android-java/com/dsh/harness/<Name>Activity.java
#   2. 参照清单  android-java/AndroidManifest.xml        （人读的副本）
#   3. 注入脚本  scripts/01-modify-termux.sh 的 6.9 字典 （真正生效的清单由它写进 termux 树）
#
# 今天两种漂移都真实发生过：
#   · 叶子页写好了、字典漏加 → 页面在包里「不存在」，点入口直接崩（ActivityNotFound）；
#   · 页面删了、构建树里的 .java 残留 → 不报重复类，安安静静编进 APK，
#     包里永远躺着一个源码树已经不存在的页。
# 这两种都只能靠事后 grep 发现，所以把「事后 grep」固化成这一步。
#
# 用法： bash scripts/check-native-pages.sh     # 有差异 → 退出码 1
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC_DIR="$ROOT/android-java/com/dsh/harness"
MANIFEST="$ROOT/android-java/AndroidManifest.xml"
INJECT="$ROOT/scripts/01-modify-termux.sh"

[ -d "$SRC_DIR" ] || { echo "✗ 找不到源码目录 $SRC_DIR"; exit 2; }
[ -f "$MANIFEST" ] || { echo "✗ 找不到参照清单 $MANIFEST"; exit 2; }
[ -f "$INJECT" ]   || { echo "✗ 找不到注入脚本 $INJECT"; exit 2; }

# 1) 源码里的 Activity 子类（本包自己的页面；排除 webview/ 下的辅助类）
#    注意：不能用 `xargs basename` —— 仓库路径里有空格（D:/Dw phone），xargs 会按空格
#    把路径劈成两段，凭空多出一个叫「Dw」的类。这里直接用 sed 取末段文件名。
in_source="$(
  grep -lE 'extends (Activity|AppCompatActivity|android\.app\.Activity)' "$SRC_DIR"/*.java 2>/dev/null \
    | sed -e 's#.*/##' -e 's/\.java$//' | sort -u
)"

# 2) 参照清单里声明为 com.dsh.harness.* 的活动
in_manifest="$(
  grep -oE 'com\.dsh\.harness\.[A-Za-z]+Activity' "$MANIFEST" 2>/dev/null \
    | sed 's/.*\.//' | sort -u
)"

# 3) 注入脚本字典的键（'XxxActivity': ( ... ) ）
#    MainActivity 不在这张字典里 —— 它是 LAUNCHER，由脚本更早的「MainActivity 已注册」
#    那一段以 replace 方式写进清单，所以下面比对时把它补回去，否则会永远报一条假差异。
in_inject="$(
  { grep -oE "'[A-Za-z]+Activity':" "$INJECT" 2>/dev/null | tr -d "':"
    echo "MainActivity"; } | sort -u
)"

echo "  源码 Activity: $(echo "$in_source" | grep -c . ) 个"
echo "  参照清单:      $(echo "$in_manifest" | grep -c . ) 个"
echo "  注入字典:      $(echo "$in_inject" | grep -c . ) 个"

fail=0
report() {
  # $1 = 描述, $2 = 集合差
  [ -z "$2" ] && return 0
  fail=1
  echo "  ✗ $1"
  echo "$2" | sed 's/^/      /'
}

report "源码有、清单漏声明（装了也起不来）：" "$(comm -23 <(echo "$in_source") <(echo "$in_manifest"))"
report "清单有、源码没有（清单指向不存在的类）：" "$(comm -13 <(echo "$in_source") <(echo "$in_manifest"))"
report "源码有、注入字典漏加（真正生效的清单里没有）：" "$(comm -23 <(echo "$in_source") <(echo "$in_inject"))"
report "注入字典有、源码没有（会写出一个不存在的页）：" "$(comm -13 <(echo "$in_source") <(echo "$in_inject"))"

if [ "$fail" -eq 0 ]; then
  echo "  ✓ 三处声明一致"
  exit 0
fi
echo "  → 修完再出包：漏声明不会在编译期报错，只会在真机点入口时炸。"
exit 1
