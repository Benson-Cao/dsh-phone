#!/usr/bin/env bash
# 01-modify-termux.sh —— 改造 Termux 源码（改包名 / App 名）
#
# 用法： bash 01-modify-termux.sh [termux-app 仓库路径]
# 默认： 当前目录下的 termux-app/
#
# 依据 termux-app v0.118.0 官方注释（TermuxConstants.java 约 295-320 行），
# 改包名需要动的就这几处，缺一不可：
#   1. TermuxConstants.TERMUX_PACKAGE_NAME / TERMUX_APP_NAME（Java 常量，全盘路径由此派生）
#   2. app/build.gradle 的 manifestPlaceholders（AndroidManifest 靠它注入）
#   3. strings.xml 用 &TERMUX_*; 实体，随 placeholder 自动替换，无需改
#   4. res/xml/*_preferences.xml、shortcuts.xml（动态变量在 XML 里不生效，需硬替换）
#
# 注意：不要改 AndroidManifest.xml 的 package= —— 官方注释明确说会 break 大量东西。

set -euo pipefail

REPO="${1:-termux-app}"
APP="$REPO/app"
SRC_DIR="$(cd "$(dirname "$0")/../android-java" && pwd)"

NEW_PKG="com.dsh.harness"
NEW_APP_NAME="DeepSeek Harness"

echo "==> 改造目标仓库: $REPO"

[ -d "$APP" ] || { echo "✗ 找不到 $APP，请先 clone termux-app"; exit 1; }

# ---- 0) applicationId + versionCode（决定真实包名，缺一不可） ----
# v0.118.0 的 defaultConfig 里【没有】 applicationId，Android 会退回用 namespace
# （= com.termux），导致装出来仍叫 Termux、且与手机上已有 Termux 撞包名/版本。
# 另外 versionCode 必须调高，否则被系统判为"降级安装"直接拒绝。
if ! grep -q 'applicationId "' "$APP/build.gradle"; then
  sed -i '0,/^    defaultConfig {$/s//    defaultConfig {\n        applicationId "'"$NEW_PKG"'"/' "$APP/build.gradle"
  echo "  ✓ 插入 applicationId = $NEW_PKG"
else
  sed -i "s|applicationId \".*\"|applicationId \"$NEW_PKG\"|" "$APP/build.gradle"
  echo "  ✓ applicationId -> $NEW_PKG"
fi
sed -i "s/^        versionCode .*/        versionCode 999/" "$APP/build.gradle"
echo "  ✓ versionCode -> 999（避免降级安装被拒）"

# ---- 1) TermuxConstants.java：包名 + App 名常量（最关键） ----
CONSTANTS=$(find "$REPO" -name "TermuxConstants.java" | head -1)
if [ -n "$CONSTANTS" ]; then
  sed -i "s|\(TERMUX_PACKAGE_NAME *= *\)\"com.termux\"|\1\"$NEW_PKG\"|" "$CONSTANTS"
  sed -i "s|\(TERMUX_APP_NAME *= *\)\"Termux\"|\1\"$NEW_APP_NAME\"|" "$CONSTANTS"
  echo "  ✓ TermuxConstants: TERMUX_PACKAGE_NAME / TERMUX_APP_NAME"
  grep -n "TERMUX_PACKAGE_NAME *=\|TERMUX_APP_NAME *=" "$CONSTANTS" | head -2
else
  echo "  ! 未找到 TermuxConstants.java"
fi

# ---- 2) app/build.gradle：manifestPlaceholders（AndroidManifest 注入用） ----
#     注意：新版没有 applicationId，只有 namespace（namespace 保持 com.termux 即可）
sed -i "s|manifestPlaceholders.TERMUX_PACKAGE_NAME = \".*\"|manifestPlaceholders.TERMUX_PACKAGE_NAME = \"$NEW_PKG\"|" "$APP/build.gradle"
sed -i "s|manifestPlaceholders.TERMUX_APP_NAME = \".*\"|manifestPlaceholders.TERMUX_APP_NAME = \"$NEW_APP_NAME\"|" "$APP/build.gradle"
sed -i "s|manifestPlaceholders.TERMUX_API_APP_NAME = \".*\"|manifestPlaceholders.TERMUX_API_APP_NAME = \"$NEW_APP_NAME:API\"|" "$APP/build.gradle"
sed -i "s|manifestPlaceholders.TERMUX_BOOT_APP_NAME = \".*\"|manifestPlaceholders.TERMUX_BOOT_APP_NAME = \"$NEW_APP_NAME:Boot\"|" "$APP/build.gradle"
echo "  ✓ build.gradle manifestPlaceholders 已替换"

# ---- 3) strings.xml：把 &TERMUX_APP_NAME; 实体换成字面值 ----
# 注意：manifestPlaceholders 只对 AndroidManifest.xml 生效；
#       strings.xml 里的 &TERMUX_APP_NAME; 是字面文本，必须直接替换，
#       否则桌面图标下面仍显示 Termux。
for f in "$APP/src/main/res/values/strings.xml" "$REPO/termux-shared/src/main/res/values/strings.xml"; do
  if [ -f "$f" ]; then
    sed -i "s|&TERMUX_APP_NAME;|$NEW_APP_NAME|g" "$f"
    echo "  ✓ 应用名 -> $NEW_APP_NAME  [$(echo "$f" | sed "s|$REPO/||")]"
  fi
done

# ---- 4) res/xml 里的硬编码（动态变量在 XML 不生效，必须实替换） ----
XML_FIXED=0
for f in $(grep -rl "com.termux" "$APP/src/main/res/xml/" 2>/dev/null || true); do
  sed -i "s|com.termux|$NEW_PKG|g" "$f"
  echo "  ✓ 修正 $f"
  XML_FIXED=1
done
[ "$XML_FIXED" = "0" ] && echo "  · res/xml 无 com.termux 硬编码，跳过"

# ---- 5) 拷贝 Java 源码到 com/dsh/harness/ ----
# 注意：Termux 是纯 Java 项目（app/build.gradle 只配了 com.android.application，
# 没有 Kotlin 插件），所以必须用 .java，.kt 会被完全忽略、不会编进 dex。
DST="$APP/src/main/java/com/dsh/harness"
rm -rf "$DST"
mkdir -p "$DST"
cp -f "$SRC_DIR"/com/dsh/harness/*.java "$DST"/
echo "  ✓ 拷贝 android-java/com/dsh/harness/*.java -> $DST"

# ---- 6) 注册 MainActivity 为启动入口 ----
MANIFEST="$APP/src/main/AndroidManifest.xml"
PY=""
for c in python3 python; do command -v "$c" >/dev/null 2>&1 && { PY="$c"; break; }; done

if [ -z "$PY" ]; then
  echo "  ! 找不到 python，无法自动注册 MainActivity，请手动在 AndroidManifest.xml 加"
elif [ ! -f "$MANIFEST" ]; then
  echo "  ! AndroidManifest.xml 不存在，跳过注册"
elif grep -q "com.dsh.harness.MainActivity" "$MANIFEST"; then
  echo "  · MainActivity 已注册，跳过"
else
  "$PY" - "$MANIFEST" <<'PYEOF'
import sys, re
p = sys.argv[1]
s = open(p, encoding='utf-8').read()

# 1) 若已存在我们的 MainActivity，先把旧的删掉（保证幂等）
s = re.sub(r'\s*<activity android:name="com\.dsh\.harness\.MainActivity".*?</activity>', '', s, flags=re.S)

# 2) 关键：TermuxActivity 原本也带 MAIN+LAUNCHER，会和我们的入口抢桌面图标。
#    这里把它的 LAUNCHER intent-filter 整块删掉（保留 LEANBACK 电视入口），
#    否则装出来点开还是 Termux 终端界面，而不是 dsh Web 界面。
def strip_launcher(m):
    body = m.group(0)
    if 'android.intent.category.LAUNCHER' not in body:
        return body
    # 只删含 LAUNCHER 的那个 intent-filter
    for f in re.findall(r'<intent-filter>.*?</intent-filter>', body, flags=re.S):
        if 'android.intent.category.LAUNCHER' in f:
            body = body.replace(f, '')
    return body

s = re.sub(r'<activity\s+android:name="\.app\.TermuxActivity".*?(?:/>|</activity>)',
           strip_launcher, s, flags=re.S)

# 3) 注册我们的 MainActivity 为唯一 LAUNCHER
#    windowSoftInputMode=adjustResize：聊天界面软键盘弹出时输入区不被遮挡。
block = ('    <activity android:name="com.dsh.harness.MainActivity" android:exported="true"\n'
         '        android:windowSoftInputMode="adjustResize"\n'
         '        android:configChanges="orientation|screenSize|keyboardHidden"\n'
         '        android:label="@string/application_name">\n'
         '        <intent-filter>\n'
         '            <action android:name="android.intent.action.MAIN" />\n'
         '            <category android:name="android.intent.category.LAUNCHER" />\n'
         '        </intent-filter>\n'
         '    </activity>\n')
s = s.replace('</application>', block + '</application>')

# 4) WebView 要加载 http://127.0.0.1:3080，而 targetSdk>=28 默认禁止明文流量
#    （NetworkSecurityPolicy 对 localhost 同样拦截），必须显式放开，否则 WebView 报
#    net::ERR_CLEARTEXT_NOT_PERMITTED。
if 'usesCleartextTraffic' not in s:
    s = re.sub(r'(<application\b)', r'\1 android:usesCleartextTraffic="true"', s, count=1)
    print("  ✓ 已加 android:usesCleartextTraffic=true（WebView 加载本地 http 必需）")

open(p, 'w', encoding='utf-8').write(s)

n = s.count('android.intent.category.LAUNCHER')
print(f"  ✓ 注册 MainActivity 为 LAUNCHER（当前 LAUNCHER 数量: {n}，应为 1）")
if n != 1:
    print("  ⚠ LAUNCHER 数量异常，请手工检查 AndroidManifest.xml")
PYEOF
fi

# ---- 7) 创建 assets 目录，放 bundle + patch（由 build.sh 拷入实际文件） ----
mkdir -p "$APP/src/main/assets"

echo "==> 改造完成"
echo "    改包名核心：TermuxConstants + manifestPlaceholders + res/xml"
echo "    保持不变：AndroidManifest package=、namespace=com.termux"
