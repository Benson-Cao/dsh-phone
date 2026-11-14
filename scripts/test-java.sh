#!/usr/bin/env bash
# test-java.sh —— 平台层纯逻辑回归（桌面 JDK，秒级，不需要真机/不需要 Gradle）
#
# 用法：
#   bash scripts/test-java.sh            # 纯逻辑测试 + Android 源编译校验
#   bash scripts/test-java.sh --logic    # 只跑纯逻辑测试（不找 android.jar）
#
# 存在意义：本仓库没有 Gradle test sourceset（构建链是「Termux 源码树 + 补丁 + gradle」），
# 安卓侧单测跑不起来。把可纯化的逻辑抽成零 Android 依赖的类之后，密码学口径、
# 请求体形状、错误码处置、地址规整这四类最容易写错又最难在真机上定位的东西，
# 就能在改完立刻验证 —— 而不是靠「装到手机上点一下看看」。
#
# 关键：指纹与 HMAC 的期望值由 node crypto **现场计算**，不写死常量。
# 写死常量只能证明 Java 与自己对得上；现场算才能钉住「与服务端 Node 口径一致」。

set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
SRC="$ROOT/android-java/com/dsh/harness"
TEST="$ROOT/tests/java"

LOGIC_ONLY=0
[ "${1:-}" = "--logic" ] && LOGIC_ONLY=1

ERR=0
ok()   { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad()  { printf '  \033[31m✗\033[0m %s\n' "$1"; ERR=$((ERR+1)); }
info() { printf '  · %s\n' "$1"; }
sec()  { printf '\n\033[1m%s\033[0m\n' "$1"; }

# ---- JDK ----
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JDK="$JAVA_HOME"
else
  for c in "/d/AndroidSDK/jdk17" "/c/Program Files/Android/Android Studio/jbr" /usr/lib/jvm/*; do
    if [ -x "$c/bin/javac" ]; then JDK="$c"; break; fi
  done
fi
if [ -z "${JDK:-}" ]; then
  bad "找不到 JDK（设 JAVA_HOME，或装 /d/AndroidSDK/jdk17）"
  exit 1
fi
info "JDK: $JDK ($("$JDK/bin/javac" -version 2>&1))"

# ---- node（用于跨语言对照）----
if command -v node >/dev/null 2>&1; then
  NODE=node
elif [ -x "/d/Program Files/nodejs/node.exe" ]; then
  NODE="/d/Program Files/nodejs/node.exe"
else
  NODE=""
fi
if [ -n "$NODE" ]; then
  info "node: $("$NODE" -v)"
else
  info "没有 node —— 跳过跨语言对照（只跑自洽断言）"
fi

OUT="$(mktemp -d 2>/dev/null || echo /tmp/dsh-java-test)"
mkdir -p "$OUT"

# ---- 1. 纯逻辑测试 ----
sec "1. 纯逻辑测试（指纹 / HMAC / 请求体 / 错误处置 / 地址规整）"

NODE_SIG=""
NODE_FP=""
NODE_SSH_FP=""
if [ -n "$NODE" ]; then
  NODE_SIG="$("$NODE" -e 'const{createHmac}=require("crypto");process.stdout.write(createHmac("sha256","9f2c4a17d0b35e68ac0127be945df013").update("a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718").digest("hex"))')"
  NODE_FP="$("$NODE" -e 'const{createHash}=require("crypto");process.stdout.write(createHash("sha256").update("dsh-device-key-test-vector").digest("hex"))')"
  NODE_SSH_FP="$("$NODE" -e 'const c=require("crypto");const b=Buffer.from("dsh-ssh-hostkey-vector");process.stdout.write("SHA256:"+c.createHash("sha256").update(b).digest("base64").replace(/=+$/,""))')"
fi

if ! "$JDK/bin/javac" -encoding UTF-8 -nowarn -d "$OUT" \
    "$SRC/DeviceKeyFingerprint.java" "$SRC/PairProof.java" "$SRC/PairRequest.java" \
    "$SRC/PairError.java" "$SRC/PairUrl.java" "$SRC/HostsError.java" "$SRC/SshHostKey.java" \
    "$SRC/SshForm.java" \
    "$TEST/com/dsh/harness/PlatformLayerTest.java"; then
  bad "纯逻辑源码编译失败"
  exit 1
fi
ok "纯逻辑源码编译通过（零 Android 依赖）"

# 注意：JDK 17 默认按系统编码（Windows 上是 GBK）写 stdout，而本脚本与终端都是 UTF-8，
# 不显式指定就会出现「断言全过但中文全乱码」—— 乱码会让人误判成编码 bug 去改源码。
TEST_OUT="$("$JDK/bin/java" -Dfile.encoding=UTF-8 -DnodeSig="$NODE_SIG" -DnodeFp="$NODE_FP" -DnodeSshFp="$NODE_SSH_FP" \
  -cp "$OUT" com.dsh.harness.PlatformLayerTest 2>&1)"
TEST_RC=$?
echo "$TEST_OUT" | sed 's/^/    /'
if [ $TEST_RC -ne 0 ]; then
  bad "纯逻辑测试未通过"
else
  ok "纯逻辑测试全部通过"
fi
PASSED="$(echo "$TEST_OUT" | sed -n 's/^# \([0-9]*\) passed.*/\1/p')"
[ -n "$PASSED" ] && info "断言数: $PASSED"

# ---- 2. Android 源编译校验 ----
if [ $LOGIC_ONLY -eq 0 ]; then
  sec "2. Android 源编译校验（配对页 + 平台层）"
  ANDROID_JAR=""
  for p in /d/AndroidSDK /c/Users/*/AppData/Local/Android/Sdk "${ANDROID_SDK_ROOT:-}"; do
    [ -n "$p" ] || continue
    for v in android-34 android-33 android-30; do
      [ -f "$p/platforms/$v/android.jar" ] && ANDROID_JAR="$p/platforms/$v/android.jar" && break
    done
    [ -n "$ANDROID_JAR" ] && break
  done

  if [ -z "$ANDROID_JAR" ]; then
    info "没有 android.jar —— 跳过编译校验（纯逻辑已通过）"
  else
    info "android.jar: $ANDROID_JAR"
    ANDROID_OUT="$OUT/android"
    mkdir -p "$ANDROID_OUT"

    # 第三方 jar（jsch / jcifs-ng）与 androidx / Termux R 的编译期桩。
    # 桩只用于 javac，绝不进包（tests/stubs 不在 android-java 下，脚本不会拷它）。
    JARS=""
    for j in "$ROOT/../termux-app/app/libs" ; do :; done
    for cand in \
        /d/AndroidSDK/gradle-8.9/lib/plugins/jsch-0.2.16.jar \
        "$(find /c/Users/Administrator/.gradle/caches -name 'jcifs-ng-*.jar' 2>/dev/null | head -1)"; do
      [ -f "$cand" ] && JARS="$JARS:$cand"
    done

    FULL_CP="$ANDROID_JAR$JARS"
    if "$JDK/bin/javac" -encoding UTF-8 -nowarn -d "$ANDROID_OUT" -cp "$FULL_CP" \
        -sourcepath "$ROOT/tests/stubs" \
        "$SRC"/*.java "$SRC/webview"/*.java 2>"$OUT/javac.log"; then
      N="$(ls "$ANDROID_OUT/com/dsh/harness"/*.class 2>/dev/null | wc -l | tr -d ' ')"
      ok "整树编译通过（$N 个类，含配对页 / 离线页 / 桥改动）"
    else
      bad "整树编译失败（详见下）"
      iconv -f GBK -t UTF-8 "$OUT/javac.log" 2>/dev/null | grep -v '^注' | head -12 | sed 's/^/    /' \
        || head -12 "$OUT/javac.log" | sed 's/^/    /'
    fi
  fi
fi

# ---- 汇总 ----
printf '\n'
if [ $ERR -eq 0 ]; then
  printf '\033[32m[PASS]\033[0m 平台层校验通过\n'
  exit 0
fi
printf '\033[31m[FAIL]\033[0m %d 项未通过\n' "$ERR"
exit 1
