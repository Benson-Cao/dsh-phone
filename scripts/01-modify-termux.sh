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
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

NEW_PKG="com.dsh.harness"
NEW_APP_NAME="DeepSeek Harness"

# ---- 版本单一来源：version.ini（maintainability-plan.md §四.1）----
# 过去这里写死 versionCode 999，导致每次出包 versionCode 都一样：
#   - 系统无法区分新旧包（"同版本覆盖安装"，装错批次也不拦）；
#   - 只能靠人工给 APK 改名来讲批次，文件名与真实版本极易对不上。
# 现在统一从 version.ini 读，脚本里不再出现版本字面量。
VERSION_INI="$ROOT/version.ini"
if [ ! -f "$VERSION_INI" ]; then
  echo "✗ 找不到 $VERSION_INI（版本单一来源，见 maintainability-plan.md §四.1）"; exit 1
fi
# 只取 key=value 的值部分；容错空行 / # 注释 / 行首空格。
ini_get() {
  local key="$1" fallback="${2:-}"
  local v
  v="$(sed -n "s/^[[:space:]]*${key}[[:space:]]*=[[:space:]]*\([^#]*\).*/\1/p" "$VERSION_INI" \
        | head -1 | tr -d '[:space:]')"
  [ -n "$v" ] || v="$fallback"
  printf '%s' "$v"
}
VERSION_CODE="$(ini_get version_code 999)"
VERSION_NAME="$(ini_get version_name 1.0.0)"
echo "==> 版本来源: version.ini  versionCode=$VERSION_CODE  versionName=$VERSION_NAME"

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
# versionCode 来自 version.ini（不再是写死的 999）。
# versionName 同样接进来：原来完全没有，导致应用信息里只能看到系统默认值。
if ! grep -q '^        versionName ' "$APP/build.gradle"; then
  sed -i "0,/^        versionCode /s//        versionName \"$VERSION_NAME\"\n        versionCode /" "$APP/build.gradle"
fi
sed -i "s/^        versionCode .*/        versionCode $VERSION_CODE/" "$APP/build.gradle"
sed -i "s/^        versionName .*/        versionName \"$VERSION_NAME\"/" "$APP/build.gradle"
echo "  ✓ versionCode -> $VERSION_CODE / versionName -> $VERSION_NAME（避免降级安装被拒）"

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

# ---- 5.5) 拷贝网络安全配置（C-05：明文只放开 127.0.0.1/localhost） ----
# 必须和第 6 步注入的 android:networkSecurityConfig 成对出现，
# 否则 manifest 引用了 @xml/network_security_config 而资源不存在 -> 编译失败。
mkdir -p "$APP/src/main/res/xml"
cp -f "$SRC_DIR"/res/xml/network_security_config.xml "$APP/src/main/res/xml/"
echo "  ✓ 拷贝 network_security_config.xml -> $APP/src/main/res/xml/"

# ---- 5.6) 拷贝 FileProvider 路径配置（方案 C，见 docs/saf-feasibility.md）----
# 必须和第 6 步注册的 <provider> 成对出现，否则运行期
# FileProvider.getUriForFile 会抛 IllegalArgumentException（"Failed to find configured root"）。
cp -f "$SRC_DIR"/res/xml/dsh_file_paths.xml "$APP/src/main/res/xml/"
echo "  ✓ 拷贝 dsh_file_paths.xml -> $APP/src/main/res/xml/"

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
#    （NetworkSecurityPolicy 对 localhost 同样拦截），必须放开明文，否则 WebView 报
#    net::ERR_CLEARTEXT_NOT_PERMITTED。
#    C-05：不再用 usesCleartextTraffic=true 全局放行（那等于对任意域名都开明文），
#    改为挂 networkSecurityConfig，把明文收窄到 127.0.0.1 / localhost 两个域名。
#    xml 由下面 5.5 步拷入 res/xml/；显式 <base-config> 会覆盖 manifest 上的
#    usesCleartextTraffic 标志，所以即使上游已有该标志也不会互相打架。
if 'networkSecurityConfig' not in s:
    s = re.sub(r'(<application\b)',
               r'\1 android:networkSecurityConfig="@xml/network_security_config"', s, count=1)
    print("  ✓ 已加 android:networkSecurityConfig（明文仅放开 127.0.0.1/localhost）")

open(p, 'w', encoding='utf-8').write(s)

n = s.count('android.intent.category.LAUNCHER')
print(f"  ✓ 注册 MainActivity 为 LAUNCHER（当前 LAUNCHER 数量: {n}，应为 1）")
if n != 1:
    print("  ⚠ LAUNCHER 数量异常，请手工检查 AndroidManifest.xml")
PYEOF
fi

# ---- 6.5) 注册 dsh 数据导出的 FileProvider（方案 C，幂等）----
# 为什么要独立成一步（而不是塞进上面的 Python 块）：
#   那个块只在「MainActivity 还没注册」时才跑。首次之后所有重跑都会走
#   `else` 分支跳过 —— 如果 provider 注册也放里面，第二次构建 provider
#   就消失了，而运行期 FileProvider.getUriForFile 会抛
#   IllegalArgumentException（Failed to find configured root），且只在真机才暴露。
# 所以这里独立判断，保证「重复跑脚本」与「跑一次」结果一致。
if grep -q "dsh.files" "$MANIFEST"; then
  echo "  · dsh FileProvider 已注册，跳过"
else
  "$PY" - "$MANIFEST" <<'PYEOF2'
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()

# authority 用 ${TERMUX_PACKAGE_NAME} 变量 —— 01 步会把常量改成 com.dsh.harness，
# 而 manifestPlaceholders 正是靠它注入，跟项目现有两个 provider 保持同一套做法。
# ⚠️ 不设 android:exported="true"：FileProvider 默认 false 才是安全的。
#    只有我们显式 FLAG_GRANT_READ_URI_PERMISSION 授权的 App 才能读。
#    逐文件授权，不做目录树共享。
#
# ⚠️ paths 必须写在 <meta-data> **子元素**里（android.support.FILE_PROVIDER_PATHS），
#    不能写成 android:resource="..." 属性 —— 实测那样写 AAPT 直接报
#    "attribute android:metadata not found"，构建失败。
provider = (
    '    <provider\n'
    '        android:name="androidx.core.content.FileProvider"\n'
    '        android:authorities="${TERMUX_PACKAGE_NAME}.dsh.files"\n'
    '        android:exported="false"\n'
    '        android:grantUriPermissions="true">\n'
    '        <meta-data\n'
    '            android:name="android.support.FILE_PROVIDER_PATHS"\n'
    '            android:resource="@xml/dsh_file_paths" />\n'
    '    </provider>\n'
)
s = s.replace('</application>', provider + '</application>')
open(p, 'w', encoding='utf-8').write(s)
print("  ✓ 注册 FileProvider（authority=${TERMUX_PACKAGE_NAME}.dsh.files）")
PYEOF2
fi

# ---- 6.6) 自检：provider 与 paths xml 必须成对 ----
if grep -q "dsh.files" "$MANIFEST" && [ -f "$APP/src/main/res/xml/dsh_file_paths.xml" ]; then
  echo "  ✓ FileProvider 与 dsh_file_paths.xml 成对存在"
elif grep -q "dsh.files" "$MANIFEST"; then
  echo "  ✗ 有 provider 但缺 dsh_file_paths.xml —— 运行期会崩，请检查第 5.6 步"
  exit 1
else
  echo "  · 未注册 FileProvider（方案 C 未启用）"
fi

# ---- 7) 创建 assets 目录，放 bundle + patch（由 build.sh 拷入实际文件） ----
mkdir -p "$APP/src/main/assets"

echo "==> 改造完成"
echo "    改包名核心：TermuxConstants + manifestPlaceholders + res/xml"
echo "    保持不变：AndroidManifest package=、namespace=com.termux"
