#!/system/bin/sh
# 验证 DshSshTunnel.scanKeyLineViaConnect 的机制：bundle 里**没有** ssh-keyscan 时，
# 用一次「不做主机校验」的试探连接把对端公钥落进临时 known_hosts，再读出来做 TOFU。
#
# 判据：临时文件里必须出现「主机 算法 base64」一行，且指纹与对端一致。
# 注意：认证必然失败（-l dsh-keyscan 是不存在的用户）——那是预期的，host key 在
# 密钥交换阶段就写盘了，早于认证。
PREFIX=/data/user/0/com.dsh.harness/files/usr
H=/data/user/0/com.dsh.harness/files/home
export PREFIX
export HOME=$H
export LD_LIBRARY_PATH=$PREFIX/lib
export OPENSSL_CONF=$PREFIX/etc/tls/openssl.cnf
export PATH=$PREFIX/bin:$PATH
export TMPDIR=$PREFIX/tmp

HOST=${1:-100.99.226.70}
PORT=${2:-2222}
T=$H/.ssh/scan_known_hosts.tmp

rm -f $T
ssh -N -T -o BatchMode=yes -o StrictHostKeyChecking=no -o UpdateHostKeys=no \
    -o UserKnownHostsFile=$T -o GlobalKnownHostsFile=/dev/null -o ConnectTimeout=8 \
    -p $PORT -l dsh-keyscan $HOST >$TMPDIR/fallback.log 2>&1 &
PID=$!
for i in 1 2 3 4 5 6 7 8 9 10; do
  [ -s $T ] && break
  sleep 0.4
done

echo "== 临时 known_hosts =="
if [ -s $T ]; then
  cat $T
  echo "== 指纹 =="
  ssh-keygen -l -f $T 2>&1
  echo "== 行首形态检查（非 22 端口必须是 [host]:port）=="
  head -1 $T | grep -q "^\[$HOST\]:$PORT " && echo "  ✓ [host]:port 形态正确" \
                                          || echo "  ✗ 行首不是 [$HOST]:$PORT"
else
  echo "  ✗ 空：试探连接没拿到公钥"
fi

echo "== ssh 输出（认证失败是预期的）=="
cat $TMPDIR/fallback.log 2>/dev/null

kill $PID 2>/dev/null
rm -f $T
echo "== 完成 =="
