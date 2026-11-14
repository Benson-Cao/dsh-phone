# SSH 档联调用的测试替身

**不是产品代码，不进 APK。** 只用来验手机侧 SSH 隧道（`DshSshTunnel`）。

## 为什么需要

手机侧 SSH 档要验的是这一串动作：

1. `ssh-keyscan` 取对端主机公钥（r122 起随包；缺它时走试探连接兜底）
2. TOFU：算指纹 → 与上次记录比对 → 不同即阻断
3. 用 ed25519 公钥认证
4. `ssh -N -T -L 127.0.0.1:3081:127.0.0.1:3080` 建本地隧道
5. 手机侧 3081 能 accept，WebView 打 `http://127.0.0.1:3081`

它只需要一个「肯接受公钥、肯转发 direct-tcpip」的对端。而本机（Windows）只有
OpenSSH **客户端** —— `C:\Windows\System32\OpenSSH` 里没有 `sshd.exe`，且 Windows 的
sshd 必须由 SYSTEM 起服务，普通账户跑不起来。所以用 `ssh2` 写这个替身。

## 用法

```bash
npm install                      # 只依 ssh2
node fake-dsh.mjs                # 终端 A：127.0.0.1:3080，只绑回环
PORT=2222 node server.mjs        # 终端 B：0.0.0.0:2222，日志打指纹
```

然后按需推设备端脚本：

```bash
export MSYS_NO_PATHCONV=1        # 否则 adb 的设备路径会被 MSYS 改写
adb -s <serial> push "D:\\Dw phone\\dsh-phone\\tests\\ssh-testdouble\\device-tunnel-test.sh" \
    /data/local/tmp/sshtest.sh
adb -s <serial> shell "chmod 644 /data/local/tmp/sshtest.sh; run-as com.dsh.harness sh /data/local/tmp/sshtest.sh"
```

两个设备端脚本：

| 脚本 | 验什么 |
| --- | --- |
| `device-tunnel-test.sh` | 全链路：客户端可用 → 生成密钥 → 写 known_hosts → 起隧道 → 经隧道取页面 |
| `device-keyscan-fallback-test.sh` | `scanKeyLineViaConnect` 的机制：没有 keyscan 时靠试探连接把 host key 落进临时 known_hosts |

`device-keyscan-fallback-test.sh` 里 `-l dsh-keyscan` 是**不存在的用户**，
认证必然失败 —— 那是预期，host key 在密钥交换阶段就写盘了，早于认证。

## 换主机密钥 / 验「指纹不一致必须阻断」

```bash
FRESH=1 node server.mjs          # 删掉 hostkey_ed25519 重新生成
```

手机侧钉住的是旧指纹 → 连接应被拒绝并提示「主机指纹与上次记录不一致」。
验完再删 `hostkey_ed25519` 重来一次，让两端回到同一把钥匙。

## 已有结论

见 `docs/native-pages.md` §18：真机（vivo X200s / Android 15，经 Tailscale）已验通
隧道层，并因此发现 bundle 缺 `ssh-keyscan`（r122 修）。
