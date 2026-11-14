# pc-sshd —— 电脑侧 SSH 服务端（给手机「SSH 主机」档案做隧道对端）

## 为什么不是 Windows 自带的 OpenSSH Server

| 尝试 | 结果 |
| --- | --- |
| 22 端口是否有 sshd | 没有（`Test-NetConnection 127.0.0.1 -Port 22` → False） |
| `Get-WindowsCapability -Online -Name OpenSSH.Server*` | `请求的操作需要提升` —— 能力状态都读不到 |
| `Start-Process powershell -Verb RunAs`（自提权） | 被安全策略拦：`Start-Process` 指向 shell 被禁止 |
| 提权后 `Add-WindowsCapability` | 无法执行（上一条已经拦死） |

Windows 的 sshd 属于可选系统能力，且**必须由 SYSTEM 起服务**，普通账户跑不起来。
所以这里提供一个够用且**更小**的实现：只做手机 SSH 档真正需要的两件事。

## 它做什么 / 不做什么

做：

- **只收公钥认证**，且只认 `authorized_keys` 里列出的那一份（密码认证在无 askpass 的手机端本来也走不通）；
- **只做本地转发**（`direct-tcpip`），且**目标只能是回环**（`127.0.0.1` / `::1`）。

不做：

- 不提供 shell / exec / sftp / pty —— 没有的能力就攻击不到；
- 不接受非回环的转发目标 —— 这台机器不会被当成跳板。

## 装

```cmd
cd D:\Dw phone\dsh-phone\tools\pc-sshd
npm install
node pc-sshd.mjs                        :: 前台跑，端口 22
node pc-sshd.mjs --port 2222            :: 换端口
node pc-sshd.mjs --print-config         :: 打印生效配置
```

首次运行会生成主机密钥（`%USERPROFILE%\.dsh\pc-sshd\host_ed25519`）并打印指纹；
手机首次连接会钉住这个指纹（TOFU，`SshHostKey.decide` 三态）。

### 登记手机公钥

```cmd
node pc-sshd.mjs --add-key "ssh-ed25519 AAAA... dsh-phone"
```

公钥文件默认 `%USERPROFILE%\.dsh\ssh\authorized_keys`，每次连接现读 —— 增删不用重启。
**文件不存在或为空时，任何连接都会被拒**：不做「首次自动信任任意公钥」。

### 长期运行

```cmd
start.cmd                    :: 前台带窗口（调试用）
start-silent.cmd             :: 后台最小化（推荐）
install-autostart.cmd        :: 写 HKCU\...\Run，登录后自动起（不需要管理员）
uninstall-autostart.cmd      :: 撤销
```

> 启动文件夹（`%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup`）那条路在本机被
> 沙箱拒写（`EPERM`），所以自启走 `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`，
> 同样是用户级、同样不需要管理员。

## 自测（不用手机，不碰你的真实配置）

```cmd
npm test
```

`selfcheck.mjs` 在临时目录里起一套独立实例（随机端口、临时主机密钥、临时
`authorized_keys`），用 ssh2 客户端把安全口径逐条变成断言：

| 断言 | 期望 |
| --- | --- |
| 服务在指定端口起来 | 能 accept |
| `authorized_keys` 内的公钥通过认证 | `ready` |
| `direct-tcpip` 转发到回环假 HTTP 服务 | 收到 `SELFCHECK-OK` |
| 转发到非回环目标（192.0.2.1） | 被拒 |
| 请求 `session`（shell / exec / sftp） | 被拒 |
| 未登记的公钥 | 认证失败 |
| 日志留痕 | 认证 / 隧道 / 拒绝原因都在 |

跑完自动关子进程、清临时目录，退出码非 0 即有断言失败。改过 `pc-sshd.mjs`
的认证或转发分支后，先跑这个再上真机。

## 手机侧怎么填

| 字段 | 值 |
| --- | --- |
| 主机地址 | 这台电脑的地址（局域网 IP、Tailscale IP 都行） |
| SSH 端口 | 上面启动时用的端口（默认 22） |
| 用户名 | 任意（本服务不区分用户，仅用于日志） |
| 本机端口 | 3081（被占用才改） |
| 电脑上 dsh 的端口 | 见下节，**这是最容易填错的一栏** |
| 访问令牌 | 见下节 |

## 「电脑上 dsh 的端口」和「访问令牌」到底填什么

这是本功能唯一真正麻烦的地方，实测结论如下：

1. **DSH 桌面版（本机的 DeepSeek Harness）拿不到令牌**。dsh web 的启动令牌是
   `randomBytes`、只存在于启动进程的 stdout 里，端口也是每次随机。上游
   `dsh-remote/lib/web-attach.js` 的注释原文：

   > The launch token CANNOT be read from anywhere but stdout. It is a `randomBytes`
   > value held in a process-local WeakMap and printed on one line
   > (`dsh web: http://127.0.0.1:<port>/?token=…`). **No file, no env var.**

   实测佐证：本机 61167 上 `GET /` → 401；从桌面端缓存里翻出的 6 个历史令牌逐个试，
   全部 401。也就是说，除非这一版 dsh web 是**你自己在终端里起**的（那样 stdout 就在你眼前），
   否则手机拿不到令牌，隧道通了也只能看到 401。

2. **所以 SSH 档指向「你自己起的 dsh web」**：

   ```cmd
   dsh web --port 3080
   :: 输出里有一行：dsh web: http://127.0.0.1:3080/?token=XXXX
   ```

   把 `3080` 填进「电脑上 dsh 的端口」，把 `XXXX` 填进「访问令牌」，测试连接应报
   `✓ 电脑侧 dsh 应答 200`。

3. **本机的桌面版 dsh，正确工具是 Relay 档**（`wbsync-host.json` 已注册、手机已配对）。
   Relay 持有令牌，手机用 `?pt=<deviceToken>` 换页面，不需要知道端口和令牌。

## 安全口径

- 私钥永不离开手机（AndroidKeyStore 之外的那把 ed25519 在 App 私有目录，权限 600）；
- 服务端只认 `authorized_keys` 里的公钥，不接受密码、不接受键盘交互；
- 转发目标仅限回环，非回环一律 reject 并记日志；
- 日志在 `%USERPROFILE%\.dsh\pc-sshd\pc-sshd.log`，记录每次认证的客户端公钥指纹、
  每次隧道目标，以及被拒原因。

## 已验

真机（vivo X200s / Android 17，手机 bundle 里的 OpenSSH 10.6p2）：

- `ssh-keyscan -p 22` 取到主机公钥 `SHA256:H9bqm5UR4w5G3PFWlPPLikkB6ZsexxEuRDXnE6sIW/Y`；
- `ssh -N -T -p 22 -i id_ed25519 -L 127.0.0.1:3082:127.0.0.1:8899 dsh@<PC>` 隧道建立成功；
- 经隧道 `GET /health` → `HTTP 200 {"ok":true,"data":{"protocol":"wbsync/2.3","online":1}}`
  （目标是本机真实运行的 Relay，不是替身）；
- 服务端日志记录：`✓ 公钥认证通过 user=dsh fp=SHA256:4KZCbbvC…` → `ready` → `✓ 隧道 127.0.0.1:8899`。

### 手机 App 端到端（SSH 档 → 隧道 → WebView）

为了**不依赖真实 dsh web 的随机端口/令牌**做确定性验证，PC 侧在回环起了最小 HTTP 替身
`tests/ssh-testdouble/fake-dsh.mjs`（`PORT=7788 TOKEN=dsh123`，只绑 127.0.0.1，必须经隧道才到得了）。
手机 SSH 档：`current=ssh, host=<PC IP>, port=22, user=dsh, localPort=3081, targetPort=7788, token=dsh123`。

真机（vivo X200s / Android 17）结果：

- `pc-sshd.log`：`✓ 公钥认证通过 user=dsh fp=SHA256:4KZCbbvC…` → `ready` → `✓ 隧道 127.0.0.1:7788`；
- `fake-dsh` 收到：`GET /?token=dsh123 ← 127.0.0.1 host=127.0.0.1:3081 ua=Mozilla/5.0 (Linux; Android 17; V2458A …)`
  —— 手机 WebView 经隧道打到 PC 侧、带正确令牌、UA 确认为手机；
- 手机截图（[ssh-demo-evidence.png](ssh-demo-evidence.png)）：WebView 渲染出
  `FAKE 3080 · PC SIDE`，页内 `path /?token=dsh123  host 127.0.0.1:3081`。
- 电脑侧 `npm test`：7/7 断言通过（不依赖手机、临时目录独立实例，可随时复跑）。

也就是说「手机 SSH 档 → pc-sshd 公钥认证 → SSH 隧道 → 手机 WebView → PC 侧页面」整条链在真机上跑通。
换真实 dsh web 只需把 `targetPort` / `token` 改成对应那台 dsh 的启动行（见上节），链路本身不变。
