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

## 免维护令牌入口：dsh-web-daemon

**为什么需要它**：dsh web 的令牌每次启动都是随机值（进程内 `randomBytes`），
而手机档案里的「访问令牌」是静态字段。服务一重启 —— 包括后台进程被回收 ——
手机里那串立刻失效，每次都要重新取令牌、再去手机上改一次。

**它做什么**：

| 层 | 端口 | 作用 |
| --- | --- | --- |
| 代理 | `127.0.0.1:3080` | 手机 SSH 档的「电脑上 dsh 的端口」，永远不变 |
| 上游 dsh web | `127.0.0.1:3082` | 守护进程拉起，从启动行捕获真令牌 |

- 手机首载带的是档案里的旧令牌。代理只在「还没有 `dsh-auth` cookie 且 URL 带
  `token` 参数」时把它换成当前真令牌；已登录的请求原样透传 —— 否则上游每次
  都回 303，会变成 303 → 注入 → 303 的死循环。
- **原始 Host 头必须透传**：上游按请求 Host 签 cookie 的 `authority`（授权域），
  手机那侧是 `127.0.0.1:3081`，签成上游端口会导致后续请求 401。
- WebSocket / SSE 的 upgrade 一并转发；上游退出自动重启并刷新令牌。
- 真令牌只留在 `%USERPROFILE%\.dsh\pc-sshd\dsh-web-token.txt` 与守护日志里，
  不下发给手机。

**跑起来**：

```bash
cd tools/pc-sshd
npm run start:web                 # --detach，脱离调用方会话
node dsh-web-daemon.mjs --print-config
# 若所在环境会回收 detached 进程，就以前台模式交给会话后台任务：
node dsh-web-daemon.mjs
```

`--listen` / `--upstream` / `--exe` / `--cli` 可覆盖默认端口与安装路径。

**安全口径**：代理只绑回环；能连到 3080 的连接必须已经过 pc-sshd 的公钥认证
（它只放行回环转发目标）。真令牌不进手机档案，档案里那串退化成无效桩值，
即使泄露也进不了上游。

**自测**：`npm test` 现在跑两套 —— pc-sshd **7/7** + 转发规则 **12/12**。
规则是纯函数 `web-proxy-rules.mjs`，覆盖「已登录不注入」「畸形 URL 不抛」
「含 `-` / `_` 的令牌不失真」等分支。

2026-11-15 本机实测：WorkBuddy 会话里 `--detach` 起的进程会被进程管理回收
（node 与上游一起消失、端口无监听），改用会话后台任务跑前台模式后正常。
要真正跨登录常驻，把 `npm run start:web` 挂进 `HKCU\...\Run` 即可（同 pc-sshd）。

## 手机侧怎么填

| 字段 | 值 |
| --- | --- |
| 主机地址 | 这台电脑的地址（局域网 IP、Tailscale IP 都行） |
| SSH 端口 | 上面启动时用的端口（默认 22） |
| 用户名 | 任意（本服务不区分用户，仅用于日志） |
| 本机端口 | 3081（被占用才改） |
| 电脑上 dsh 的端口 | 见下节，**这是最容易填错的一栏** |
| 访问令牌 | 见下节；**启用 `dsh-web-daemon` 后填任意非空值即可**，代理会把它换成当前真令牌 |

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

2. **所以 SSH 档指向「你自己起的 dsh web」**。本机 PATH 里没有 `dsh`，
   但安装目录里的 `runtime/cli/bin/dsh.cmd` 就是个包装：设 `ELECTRON_RUN_AS_NODE=1`
   再让 `DeepSeek Harness.exe` 跑 asar 内的 `dsh-desktop-host/lib/cli.js`。等价命令：

   ```bash
   EXE="D:/Program AI/Deepseek Harness/DeepSeek Harness.exe"
   CLI="D:/Program AI/Deepseek Harness/resources/app.asar/dsh/node_modules/@deepseek-ai/dsh-desktop-host/lib/cli.js"
   env -u NODE_OPTIONS ELECTRON_RUN_AS_NODE=1 "$EXE" --expose-internals "$CLI" \
     web --no-open --port 3080
   # 输出一行：dsh web: http://127.0.0.1:3080/?token=XXXX
   ```

   `--no-open` 是不弹浏览器；`web --help` 可看全部参数（含 `--host` / `--trusted-host`）。
   把 `3080` 填进「电脑上 dsh 的端口」，把 `XXXX` 填进「访问令牌」。
   **令牌每次启动都变** —— 直接跑裸 dsh web 时，实例一重启就得手工同步手机里的
   令牌；见下面 `dsh-web-daemon` 那节，用代理入口可以避免这件重复劳动。

   2026-11-15 本机实测（`dsh web --help` 与 `--port 3080` 都跑通）：

   - 带令牌首载返回 **303**，不是 200：`Location: ./` + 下发 `HttpOnly;
     SameSite=Strict` 的签名 cookie；WebView 跟随跳转后 `GET /` 才是 200。
     配置页「测试连接」的探针关掉了自动跟随，原先只认 200 会把正常的登录流程
     报成「那一头不像是 dsh 的界面」；r127 起按 `303 + Location: ./` 判为
     「已接受令牌」，判定抽成纯函数 `SshForm.probeVerdict`，桌面 9 条断言覆盖。
   - cookie 里的 `authority` **按请求 Host 签**：手机经隧道时 Host 是
     `127.0.0.1:3081`，签出来的也是 `127.0.0.1:3081`，不会因为「监听 3080 /
     访问 3081」被浏览器信任围栏拦下，无需额外加 `--trusted-host`。
   - 临时 pc-sshd 实例 + 临时密钥模拟手机，端到端 **6/6 断言通过**：首载 303 →
     下发 cookie → authority=3081 → 跟随跳转 200 → body 43015 字符且**不含**
     `FAKE 3080`。也就是说从替身换成真页面，链路一行都不用改。
   - 桌面版那个 61167 与这个实例是两个独立进程，互不干扰；61167 仍是 401。

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

## 排障：手机说「连不上这台电脑」时，先看 `pc-sshd.log`

手机端只会给一句笼统的失败文案，真正的分叉在电脑侧日志里：

| 日志行 | 实际含义 | 处置 |
| --- | --- | --- |
| `✗ 拒绝未授权公钥 fp=…` | 手机那把公钥没登记 | 把配置页里的公钥贴进 `authorized_keys`，不用重启服务 |
| `✓ 公钥认证通过 …` → `ready` → `✓ 隧道 127.0.0.1:N` | 整条链路通 | 页面内容由 N 端口上那台服务决定，与 SSH 无关 |
| `✗ 连不上 127.0.0.1:N → ECONNREFUSED` | **SSH 是通的**，只是电脑上 N 端口没人监听 | 把「电脑上 dsh 的端口」改成真在监听的端口，或把那台服务起起来 |
| `401` / 空白页 | 服务在，但没带对令牌 | 见上节：桌面版 dsh 的令牌拿不到，要用自己起的 `dsh web` |

2026-11-14 23:56 真机出现过第三行：把端口从替身的 7788 改回 3080 后，认证和隧道照常成功，
只是 3080 上什么都没跑。这时去查 sshd / 公钥 / 网络全是白费力气 —— 先看目标端口有没有人听。

**另一种与 SSH 完全无关的失败页**：标题「连不上这台电脑」、地址是
`http://127.0.0.1:8899`、原因列着「Relay 没在运行 / 管理台收回」，但明明配的是 SSH 档。
这不是隧道故障，是手机档案 `current` 被切成了 `relay` —— 失败页按档案类型分流，
所以它如实显示 Relay 的地址与原因。根因是 r128 之前 `OfflineActivity.enterRemote()`
（选择页的「进入这台电脑」）无条件 `setRelay + setCurrent(relay)`，点一次就把 SSH 档改掉。
r128 起 SSH 档原样保留；用旧包遇到这页时，进 SSH 配置页点「保存并连接」把 `current`
切回 `ssh` 即可。判断口径：先看 `dsh_profiles.xml` 的 `current`，再决定查哪条链路。

## 真机端到端（固定入口代理，2026-11-15）

手机档案切成 `current=ssh`、六项保持原值（含**已经失效的旧令牌**），点「保存并连接」后：

- 前台 `MainActivity` 直接渲染出**真实 dsh 会话页**：顶部会话标题、底部
  「任务 / 定时任务 / 发现 / 我的」、模型 `Hy4`、输入框「发消息…」，
  以及「正在思考…」的实时状态。不是错误页，也不是 `FAKE 3080` 替身页。
- `pc-sshd.log` 当日累计 44 条 `✓ 隧道 127.0.0.1:3080`；守护日志出现
  `[llm-deepseek-account] model listing ... loaded 2 model(s)`；
  `netstat` 上 pc-sshd → 3080 → 3082 各两条 ESTABLISHED 长连接，
  说明经代理的 WebSocket / SSE 升级通道也在工作，不只是首屏能打开。

手机里的旧令牌一次都没改过：首载由代理换成真令牌，之后靠签名 cookie 通信。
链路接通之后，上游 dsh web 再重启也只需要守护进程自己刷新令牌。
