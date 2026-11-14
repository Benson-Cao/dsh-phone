#!/system/bin/sh
# SSH 档隧道层联调（设备端执行，run-as 下跑）。验证四件事：
#   1) bundle 里的 ssh/ssh-keygen 真能在设备上跑（LD_LIBRARY_PATH 覆盖 RUNPATH）
#   2) 客户端 ed25519 密钥生成与指纹
#   3) StrictHostKeyChecking=yes + 手写 known_hosts（bundle 无 ssh-keyscan，见 r122）
#   4) -L 本地转发真的把手机 127.0.0.1:3081 打到电脑的回环 3080
PREFIX=/data/user/0/com.dsh.harness/files/usr
H=/data/user/0/com.dsh.harness/files/home
export PREFIX
export HOME=$H
export LD_LIBRARY_PATH=$PREFIX/lib
export OPENSSL_CONF=$PREFIX/etc/tls/openssl.cnf
export PATH=$PREFIX/bin:$PATH
export TMPDIR=$PREFIX/tmp

HOST=100.99.226.70
PORT=2222
HOSTKEY_LINE="ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAILn3/NV+1lNtTLBkbbIwgH082gzH5afFLk87XaV2a3v8"

echo "== 1) 客户端可用性 =="
ssh -V 2>&1
which ssh-keyscan 2>/dev/null || echo "ssh-keyscan: 不在 PATH（预期缺失）"

mkdir -p $H/.ssh
chmod 700 $H/.ssh

echo "== 2) 客户端密钥 =="
if [ ! -f $H/.ssh/id_ed25519 ]; then
  ssh-keygen -t ed25519 -N '' -C dsh-phone -f $H/.ssh/id_ed25519 >/dev/null 2>&1
  echo "  新生成"
else
  echo "  复用已有"
fi
chmod 600 $H/.ssh/id_ed25519
cat $H/.ssh/id_ed25519.pub
ssh-keygen -l -f $H/.ssh/id_ed25519.pub 2>&1

echo "== 3) known_hosts（非 22 端口用 [host]:port）=="
printf '[%s]:%s %s\n' "$HOST" "$PORT" "$HOSTKEY_LINE" > $H/.ssh/known_hosts
cat $H/.ssh/known_hosts

echo "== 4) 起隧道 =="
ssh -N -T \
    -o BatchMode=yes \
    -o ExitOnForwardFailure=yes \
    -o StrictHostKeyChecking=yes \
    -o UserKnownHostsFile=$H/.ssh/known_hosts \
    -o ServerAliveInterval=20 \
    -o ServerAliveCountMax=3 \
    -i $H/.ssh/id_ed25519 \
    -p $PORT \
    -L 127.0.0.1:3081:127.0.0.1:3080 \
    dsh@$HOST >$TMPDIR/ssh.log 2>&1 &
SSHPID=$!
sleep 5
if kill -0 $SSHPID 2>/dev/null; then
  echo "  隧道进程存活 pid=$SSHPID"
else
  echo "  隧道进程已退出"
fi
echo "  --- ssh 输出 ---"
cat $TMPDIR/ssh.log 2>/dev/null
echo "  --- /proc/net/tcp 里的 3081 ---"
grep -i ":0C09" /proc/net/tcp 2>/dev/null | head -3

echo "== 5) 经隧道请求（期望看到 FAKE 3080）=="
node -e "const http=require('http');const r=http.get({host:'127.0.0.1',port:3081,path:'/',timeout:8000},res=>{let b='';res.on('data',d=>b+=d);res.on('end',()=>{console.log('  HTTP '+res.statusCode+' '+(/FAKE 3080/.test(b)?'✓ 含 FAKE 3080 标记':'✗ 未含标记'));const m=b.match(/<dd>([^<]*)<\/dd>/g)||[];console.log('  页面字段: '+(m.slice(0,2).join(' | ')||'-'))})});r.on('error',e=>console.log('  ✗ 请求失败 '+e.message));r.on('timeout',()=>{console.log('  ✗ 超时');r.destroy()})" 2>&1

kill $SSHPID 2>/dev/null
echo "== 完成 =="
