#!/usr/bin/env bash
# fetch-openssh.sh —— 把 Termux 的 openssh 客户端塞进 App 的 prefix（为出带 ssh 的 bundle 做准备）
#
# 用法：
#   bash scripts/fetch-openssh.sh                 # 自动挑设备 + com.dsh.harness
#   bash scripts/fetch-openssh.sh <serial>
#   PKG=com.dsh.harness BIN=ssh,ssh-keygen bash scripts/fetch-openssh.sh
#
# 为什么需要它：`02b-harvest-bundle.sh` 从**已装 App 的私有目录**收割运行环境，
# 而 App 的 `files/usr/bin` 只有 node/bash/pnpm/dsh-web.sh —— 没有包管理器，
# 也没法在设备上 `pkg install`。所以「bundle 带 openssh」的真实做法是：
#   1) 从 Termux 官方仓库取 aarch64 的 openssh deb（PC 侧解包，不依赖 ar/7z）；
#   2) 把 ssh / ssh-keygen / ssh-keyscan / scp / sftp 放进 App 的 files/usr/bin
#      （r122 起补上 ssh-keyscan：缺它时首次连接取不到主机公钥，TOFU 无从谈起）；
#   3) 再跑 02b 收割 —— 之后每份 bundle 都自带 ssh。
#
# 为什么只取二进制不取依赖库：这台机器的 prefix 里已经有 libcrypto.so.3 / libssl.so.3 /
# libkrb5 / libldns / libedit / libandroid-support / libtermux-auth / libz.so.1
# （node 与 dsh 的依赖），脚本会先核对，缺任何一个就明确报出来，不静默产出半残环境。
#
# ⚠️ Termux 二进制的 RUNPATH 指向 /data/data/com.termux/files/usr/lib。运行时要靠
#    LD_LIBRARY_PATH（DshSshTunnel 与 dsh-web.sh 都设了 $PREFIX/lib）覆盖它。
#    在没装 com.termux 的手机上这一点尤其关键 —— 别把「本机能跑」当成「到处能跑」。

set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

ADB="${ADB:-/d/AndroidSDK/platform-tools/adb.exe}"
[ -x "$ADB" ] || ADB="$(command -v adb || true)"
[ -x "${ADB:-}" ] || { echo "✗ 找不到 adb（设 ADB=...）"; exit 1; }

PKG="${PKG:-com.dsh.harness}"
SERIAL="${1:-}"
[ -n "$SERIAL" ] || SERIAL="$("$ADB" devices 2>/dev/null | awk 'NR>1 && $2=="device"{print $1; exit}')"
[ -n "$SERIAL" ] || { echo "✗ 没有已连接设备（先 adb connect <ip:port>）"; exit 1; }

TMP="$(mktemp -d 2>/dev/null || echo /tmp/dsh-ssh)"
mkdir -p "$TMP"

echo "==> 设备 $SERIAL / 包 $PKG"

# ---- 1) 依赖库自检（缺了就别往下走，产出的环境是半残的）----
MISSING=""
for lib in libcrypto.so.3 libssl.so.3 libkrb5.so libldns.so libedit.so \
           libandroid-support.so libtermux-auth.so libz.so.1; do
  if ! "$ADB" -s "$SERIAL" shell "run-as $PKG sh -c 'ls files/usr/lib/$lib* >/dev/null 2>&1'" >/dev/null 2>&1; then
    MISSING="$MISSING $lib"
  fi
done
if [ -n "$MISSING" ]; then
  echo "✗ prefix 里缺少 openssh 依赖库：$MISSING"
  echo "  这些库本该随 node/dsh 一起在 bundle 里；缺失说明这台设备的环境不完整，先修环境再谈 ssh。"
  exit 1
fi
echo "  ✓ 依赖库齐（libcrypto/libssl/libkrb5/libldns/libedit/libandroid-support/libtermux-auth/libz）"

# ---- 2) PC 侧取 deb 并解出二进制（Python 标准库：ar 头 + xz + tar）----
python - "$TMP" <<'PY' || exit 1
import sys, io, os, gzip, lzma, tarfile, urllib.request

out = sys.argv[1]
INDEX = "https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages.gz"
BASE  = "https://packages.termux.dev/apt/termux-main/"

raw = urllib.request.urlopen(INDEX, timeout=60).read()
txt = gzip.decompress(raw).decode("utf-8", "replace")

pkg = None
for block in txt.split("\n\n"):
    if block.startswith("Package: openssh\n") or block.startswith("Package: openssh\r"):
        info = {}
        for line in block.splitlines():
            if ": " in line:
                k, v = line.split(": ", 1)
                info[k.strip()] = v.strip()
        pkg = info
        break
if not pkg:
    print("✗ 仓库索引里没找到 openssh")
    sys.exit(1)

ver, fname = pkg["Version"], pkg["Filename"]
print("  · openssh %s  (%s)" % (ver, fname))
data = urllib.request.urlopen(BASE + fname, timeout=300).read()
print("  · deb 已下载 %.0f KB" % (len(data) / 1024))

def ar_members(buf):
    """解析 ar 归档：返回 [(name, payload)]。deb 只用到 data.tar.{xz,gz}。"""
    if not buf.startswith(b"!<arch>\n"):
        raise ValueError("不是 ar 归档")
    pos, out2 = 8, []
    while pos + 60 <= len(buf):
        hdr = buf[pos:pos + 60]
        name = hdr[0:16].decode("ascii", "replace").strip().rstrip("/")
        size = int(hdr[48:58].decode("ascii").strip() or 0)
        pos += 60
        out2.append((name, buf[pos:pos + size]))
        pos += size + (size & 1)
    return out2

member = None
for name, payload in ar_members(data):
    if name.startswith("data.tar"):
        member = (name, payload)
        break
if not member:
    print("✗ deb 里没有 data.tar.*")
    sys.exit(2)

name, payload = member
tarball = lzma.decompress(payload) if name.endswith(".xz") else gzip.decompress(payload)

WANT = ("ssh", "ssh-keygen", "ssh-keyscan", "scp", "sftp")
got = []
with tarfile.open(fileobj=io.BytesIO(tarball)) as tf:
    for m in tf.getmembers():
        if not m.isfile():
            continue
        base = os.path.basename(m.name)
        if base in WANT and "/usr/bin/" in m.name:
            dst = os.path.join(out, base)
            with open(dst, "wb") as f:
                f.write(tf.extractfile(m).read())
            os.chmod(dst, 0o755)
            got.append(base)

missing = [b for b in WANT if b not in got]
if missing:
    print("✗ deb 里缺少：" + ", ".join(missing))
    sys.exit(3)
print("  ✓ 解出：" + " ".join(got))
PY

# ---- 3) 推到设备并落到 App 的 prefix ----
# ⚠️ Windows 上 adb.exe 不认识 MSYS 的 /tmp 路径（它会按 C:\tmp 解析），
#    必须用 cygpath 转成 Windows 形式，否则 push 一定失败在「找不到文件」。
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

if command -v cygpath >/dev/null 2>&1; then
  TMP_WIN="$(cygpath -w "$TMP")"
else
  TMP_WIN="$TMP"
fi

for b in ssh ssh-keygen ssh-keyscan scp sftp; do
  "$ADB" -s "$SERIAL" push "$TMP_WIN\\$b" "/data/local/tmp/dsh-$b" >/dev/null 2>&1 || {
    echo "✗ push 失败：$b（源 $TMP_WIN\\$b）"; exit 1; }
  "$ADB" -s "$SERIAL" shell "run-as $PKG sh -c 'mkdir -p files/usr/bin && cp /data/local/tmp/dsh-$b files/usr/bin/$b && chmod 700 files/usr/bin/$b'" >/dev/null 2>&1 || {
    echo "✗ 落到 prefix 失败：$b"; exit 1; }
  "$ADB" -s "$SERIAL" shell "rm -f /data/local/tmp/dsh-$b" >/dev/null 2>&1
  echo "  ✓ files/usr/bin/$b"
done

# ---- 4) 真跑一次，证明链接与库都能用 ----
VER="$("$ADB" -s "$SERIAL" shell \
  "run-as $PKG sh -c 'cd files && LD_LIBRARY_PATH=\$PWD/usr/lib OPENSSL_CONF=\$PWD/usr/etc/tls/openssl.cnf ./usr/bin/ssh -V'" \
  2>&1 | tr -d '\r' | head -2)"
echo "  ssh -V => ${VER:-<无输出>}"

if ! printf '%s' "$VER" | grep -q "OpenSSH"; then
  echo "✗ ssh 起不来（多半是缺库或 RUNPATH 指向 com.termux）。上面是原始输出。"
  exit 1
fi

echo
echo "[PASS] openssh 客户端已进 prefix。接下来（出包）："
echo "  1) 递增 android-java/com/dsh/harness/DshBootstrap.java 的 BUNDLE_REV（否则老安装命中旧戳跳过重解包）"
echo "  2) bash scripts/02b-harvest-bundle.sh            # 从本机收割，产出含 ssh 的 bundle"
echo "  3) bash scripts/00-build-apk.sh BUNDLE=<收获的 tar.gz>"
echo "  4) bash scripts/check-node-version.sh            # 出包前后各跑一次"
