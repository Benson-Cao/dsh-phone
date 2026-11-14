# 原生页面与设备身份（r111）

> 对应 wb-cloud-sync 的 v2.3 P3 并行前置项：手机壳侧的三块原生页面、设备公钥持久化、省电放行。
> 本文记录**已落地**的部分、口径与未落地部分的阻塞原因，避免下一轮重新推导。

## 1. 三块原生页面的状态

| 页面 | 状态 | 文件 | 说明 |
|---|---|---|---|
| 配对页 | 已落地 | `android-java/com/dsh/harness/PairActivity.java` | 电脑地址 + 6 位配对码，两步配对，失败按错误码给处置 |
| PC 离线页 | 已落地 | `android-java/com/dsh/harness/OfflineActivity.java` | 探 `/health`，区分「地址不通 / Relay 在但电脑没上线 / 在线 N 台」 |
| 主机切换器 | 已落地（r113） | `android-java/com/dsh/harness/HostSwitcherActivity.java` | `GET /api/hosts` 列可切电脑，确认后 `POST /api/hosts/select` 带 `confirmNoMigration:true`；切完清 cookie + 作废预热 |
| 入口（侧栏图标） | 已落地（r114） | `android-java/assets/dsh/connection-entry.js` | 窄栏 `#dsh-rail-foot` 加「连接」图标；按 `pairStatus()` 路由：已配对有令牌→切换器，其余→配对页 |

两点口径：

- **原生页不是 Web UI 皮肤**（D-005）：用程序化 View 构建，不引入 `res/layout`，颜色取 `DshPalette`，
  与启动页/设置页同一套令牌。
- **入口**：`DshSafBridge` 新增 `pairStatus()` / `openPairPage()` / `openOfflinePage()` 三个
  `@JavascriptInterface`。写操作（开页面）走 `localOriginAllowed` 守卫，只读状态只回
  「配没配过 + 连的是谁 + 指纹前 16 位」，不回令牌、不回完整指纹（WebView 可读的 JS 对象里不放身份）。

## 2. 设备身份：密钥、指纹、生命周期

```
DshDeviceKey         AndroidKeyStore, alias=dsh.device.key.v1, EC P-256
                     ├─ 私钥不可导出（进程被 dump 也拿不到）
                     └─ fingerprintOrNull() → SPKI 的 SHA-256 → 64 位小写 hex
DshDeviceIdentity    SharedPreferences "dsh_pair"
                     ├─ phoneId = "phone-" + 8 hex   （对齐服务端 ^[A-Za-z0-9_-]{8,64}$）
                     ├─ lastHostDeviceId / lastRelayUrl / lastPairedAt
                     └─ rotate() = 清密钥 + 换 phoneId（两者必须一起换）
```

**核心不变量：`phoneId` 与设备密钥同生命周期。** 卸载重装 / 清除数据时两者一起消失，
服务端看到的是「一台新手机」，不会撞上 `DEVICE_KEY_MISMATCH`。若只换密钥不换 `phoneId`
（例如手写 `DshDeviceKey.clear()` 就重配），r24 会按设计阻断 —— 那是防身份被顶替，
但对用户表现为「配不上了」，所以配对页在收到该错误码时显式提供「重新开始」。

指纹口径的三条硬约束（写错不会报错，只会静默钉错值）：

1. 输入是公钥的 **X.509 SubjectPublicKeyInfo**（`PublicKey.getEncoded()`），不是证书 DER、不是 PEM、不是 SSH 指纹；
2. 摘要 SHA-256，输出 64 位小写 hex（Relay 侧 `DEVICE_KEY_RE = /^[A-Fa-f0-9]{64}$/`，只做大小写归一）；
3. 非法值**整个字段省略**，等价于旧客户端 —— 协议规定非法按未携带处理，且不给指纹探测留缝。

Keystore 参数选择的理由：EC P-256（SPKI 91 字节、指纹快、API 23+ 全支持）；
`SIGN | VERIFY`（当前只用指纹，但留签名能力，协议若加持有性证明不必重建密钥）；
**不设** `setUserAuthenticationRequired`（否则锁屏时后台同步全失败）；
**不设** `setInvalidatedByBiometricEnrollment`（否则用户加个指纹就把自己踢下线）。

## 3. 配对链路与错误码处置

```
POST /pair        { code }                         → { challenge, pairSecret, expiresIn, deviceId }
     sig = HMAC-SHA256(key = pairSecret 的 hex 文本字节, msg = challenge)
POST /pair/verify { challenge, sig, phoneId?, clientName?, devicePubKey? }
                  → { deviceToken, deviceId, hostDeviceId, protocol, relayUrl }
```

> **HMAC 口径坑**：Relay 写的是 `createHmac('sha256', c.pairSecret)`，Node 把 `pairSecret`
> 当**字符串**用 —— 密钥是 hex 文本自身的 ASCII 字节，**不是** hex 解码后的 16 字节。
> 搬服务端代码时最容易在这里"顺手解码"，结果本地自测全过、连上 Relay 永远 401。
> `PairProof` 按文本处理，并用 node 现场算期望值做跨语言对照钉住它。

错误码 → 处置（`PairError`，纯逻辑可测）：

| 错误码 | 是否可原样重试 | 处置 |
|---|---|---|
| `PAIR_CODE_INVALID` | 否 | 配对码一次性且 10 分钟有效，去电脑上重新生成 |
| `CHALLENGE_EXPIRED` | 否 | 挑战 60 秒内要完成，重来一次 |
| `UNAUTHORIZED` | 否 | 可能已被收回，去电脑管理台确认后重配 |
| `CLIENT_LIMIT` | 否 | 该电脑已绑 8 台，先在管理台收回一台 |
| `DEVICE_KEY_MISMATCH` | 否 | 提供「重新开始」换身份；或先在管理台收回本机 |
| `HOST_OFFLINE` | 是 | 电脑休眠/掉线，稍后再试 |
| 无响应 | 是 | 网络故障 |
| 未识别的 409 | 否 | 服务端拒绝配对（不冒充"电脑不可达"，那会让人反复查网络） |

## 4. 省电放行

`DshPowerGuard` 三层降级，永远返回一个可用的 Intent：

1. `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（带包名）—— 一步放行，前提是清单里有
   `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（`01-modify-termux.sh` 第 6.9 步注入）；
2. 该 action 被 ROM 摘掉 → `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`（列表页）；
3. 再不行 → 应用详情页。

文案只声称「系统省电白名单」，不谎报「已彻底放行」—— 国产 ROM 的「后台限制 / 自启动」
是另一套开关，系统 API 查不到。

**不用** `SYSTEM_ALERT_WINDOW` 或自启动壳对抗系统：那是恶意软件手法，且会被商店下架。

## 5. 验证方式：`scripts/test-java.sh`

```bash
bash scripts/test-java.sh          # 纯逻辑测试 + 整树编译校验
bash scripts/test-java.sh --logic  # 只跑纯逻辑（不找 android.jar）
```

本仓库没有 Gradle test sourceset（构建链是「Termux 源码树 + 补丁 + gradle」），安卓侧单测跑不起来。
因此把可纯化的逻辑抽成**零 Android 依赖**的类，桌面 JDK 秒级回归：

| 类 | 覆盖 |
|---|---|
| `DeviceKeyFingerprint` | 指纹格式、大小写/冒号/空白归一、长度与非 hex 拒绝 |
| `PairProof` | HMAC 口径（与 node 现场对照）、常量时间比较 |
| `PairRequest` | 请求体形状、非法指纹省略、转义、控制字符剔除、名字截断 |
| `PairError` | 八个错误码的处置与可重试性 |
| `PairUrl` | 补 scheme、去尾斜杠、拒绝凭据/空白 |

指纹与 HMAC 的期望值**不写死常量**，由 `node -e` 现场计算再经系统属性传入 ——
写死常量只能证明 Java 与自己对得上，现场算才能钉住「与服务端 Node 口径一致」。

第 2 段做**整树**编译校验（133 个类）。androidx 与 `com.termux.R` 由 gradle 工程提供，
桌面用 `tests/stubs/` 的编译期桩顶上 —— 桩只给 javac 用，`tests/` 不在 `android-java/` 下，
出包脚本不会拷它。桩的签名按实际调用点写，漂了就会变成"假通过"，改桩前先看调用点。

## 6. 未完成与阻塞

| 项 | 阻塞原因 | 归属 |
|---|---|---|
| 主机切换器页面 | 需要 CP `/api/hosts` + 设备令牌；令牌存储属 `client-core`，App 内还没有该运行时 | 本仓库（下一步可先做令牌存储 + 切换器骨架） |
| 官方设置页「连接电脑」一行 | 官方设置 DOM 需真机确认后才能注入，未在无设备条件下猜结构 | 本仓库 + 真机 |
| 真机验收（配对全链路） | 需要一台装着 Relay 的电脑 + 手机在同一局域网 | 真机 |
| 设备公钥签名的持有性证明 | 协议 r24 只钉指纹，`/pair/verify` 暂无签名位；`DshDeviceKey` 已保留 SIGN 能力 | 协议侧 |

## 7. 能力归属：哪些能进插件、哪些必须在壳

问题背景：插件升级不需要重签 APK（APK 换签名会清空 Termux 环境），所以「能进插件的就进插件」
有真实运维价值。但插件的运行前提是**引擎在跑、页面已加载**，很多能力它根本够不到。

| 能力 | 能否进插件 | 原因 |
|---|---|---|
| 配对页 | **否** | 首装时引擎还在解包、WebView 未加载；插件此时不存在。且配对是「用起来之前」的一步 |
| PC 离线页 | **否**（整体） | 页面存在的理由就是连不上；插件依赖引擎活着。仅「引擎活着但电脑掉线」这一子场景可提示 |
| 主机切换器 · 列表与选择 | **可以** | 已连上电脑后的功能；插件面板可渲染（`dsh-nas-sync` 的 `client.js` 即此路径） |
| 主机切换器 · 执行动作 | **否** | `POST /api/hosts/select` 之后还要 `clearView` / `resetCookieJar` / `rebuildIndex`（r22 的 `onHostChanged`），插件拿不到宿主 WebView |
| 设备密钥生成 | **否** | AndroidKeyStore 是 Android API，Termux 里的 Node 插件无 Context、无 JNI |
| 令牌落盘 | **否** | 同上；且令牌属凭据，不该进页面（见 §8） |
| 省电放行 | **否** | 需要 `PowerManager` 与系统设置 intent；插件连「是否在白名单」都查不到 |
| Postgres 实例 | 不适用 | 是运行实例问题，不是代码归属问题 |

**结论：界面可以进插件，身份与系统能力必须在壳。** 因此桥的暴露原则是「给能力，不给凭据」：
`pairStatus()` 只回「配没配过 / 连的是谁 / 指纹前 16 位 / 有没有令牌」，不回令牌本体。
后续要支持插件面板做切换器，桥应新增 `listHosts()`（壳代持令牌请求 CP `/api/hosts`，
只回脱敏行），而不是把令牌交给页面。

> 章程层面：BR-8（v2.3）写的是「手机侧不装插件、不写 UI」，`dsh-nas-sync` 属同期另一条产品线。
> 真要把界面挪进插件，需要先改 BR-8 与 D-005，属 ADR 级变更，不能在实现里顺手改。

## 8. 令牌落盘（r112）

配对成功拿到的 `deviceToken` 由 `DshTokenStore` 加密保存在壳里：

- **算法**：AndroidKeyStore 中的 AES-256-GCM 密钥包裹，密文（IV + ciphertext，Base64）
  存 SharedPreferences；密钥不可导出，备份带不走可用令牌 —— 语义上「换机即失效」，
  与 `DshDeviceKey` 的 TOFU 一致。
- **解不开就清掉**：密钥被清、密文来自别的设备、GCM 校验失败，一律返回 `null` 并删除密文，
  正确处置本来就是重新配对；留着只会让每次启动都失败一次。
- **身份与令牌一起换**：`DshDeviceIdentity.rotate()` 同时清 `DshTokenStore`，避免
  「新身份 + 旧凭据」被服务端按 `UNAUTHORIZED` 拒绝且用户无法自愈。
- **存失败要显示**：`save()` 返回 `false` 时配对页明确提示「令牌没能保存，下次仍需重新配对」，
  不把半成功显示成成功。

## 9. 手机侧仍缺的消费方

配对完成后，binding（`hostDeviceId` / `relayUrl`）与令牌都已在壳里，但**手机侧还没有
代码去用它们**：壳里没有 `client-core` 运行时，WebView 侧也没接。也就是说当前配对
只是把身份备好了，尚未真正接入会话同步。下一步是桥提供 `listHosts()` 能力 +
切换器页面，届时 binding 与令牌才被真正消费。

## 10. 按需连接（r113）

**默认不自动进远端档案。** `HostProfile.autoEnterRemote()` 缺省 `false`：
启动时若当前档案是 `relay` / `ssh`，`MainActivity` 不直接进 WebView，而是打开
`OfflineActivity` 的 **choice 模式**（标题「连接到电脑」），让用户选：

- 「进入这台电脑」→ 校验 relay 三要素（origin / hostId / 令牌），写档 → `ProfileSession.resetRuntime()` → 带 `EXTRA_ENTER_REMOTE=true` 拉起 `MainActivity`；
- 「改用本机引擎」→ 切回 `local` 档 → 同上；
- 「切换到其它电脑」→ `HostSwitcherActivity`。

理由：打开应用多数意味着「用手机上的 dsh」；自动连一台可能已离线或被收回的电脑，
失败时只会停在白屏，而用户并不知道问题在哪。`ssh` 档同理（隧道还没接，更不该自动进）。

显式进入的唯一通道是 `MainActivity.EXTRA_ENTER_REMOTE=true`，只在用户刚点过确认时由
切换器 / 选择页带上。**不要**用「每次启动都带」的方式绕过它 —— 那等于把开关又关掉。

## 11. 响应封套（r113 修复的真实缺陷）

Relay 的全部 HTTP 响应统一是：

```jsonc
{ "ok": true,  "data": { ... }, "protocol": "wbsync/2.3" }
{ "ok": false, "error": { "code": "HOST_OFFLINE", "message": "…", "retryable": true }, "protocol": "wbsync/2.3" }
```

r111 的客户端按**顶层字段**解析（`json.deviceToken` / `json.online`），对真 Relay 的结果是：

- 配对：`challenge` / `pairSecret` 取到 null → 第二步直接判失败，且错误码为空（`error.code` 也没读到）；
- 离线页：`online` 永远 0 → 明明电脑在线也显示「没有电脑在线」。

现在统一走 `payload()`（取 `data` 内层，容忍裸对象形态）与 `error.code`。
**新增任何 Relay 调用都必须走这两个入口**，别再直接读顶层 —— 这条错误不会报错，
只会让功能静默失效，是最难发现的一类。

## 12. 已知漂移（待出包前处理）

真机实测（`adb run-as com.dsh.harness` 执行 `files/usr/bin/node`，即 `dsh-web.sh` 用的
`$PREFIX/bin/node`）报 **node v24.18.0**，而 `version.ini` 的 `components.node_version`
写的是 **22.22.2**。两者必须一致，否则 `version_code` / `BUNDLE_REV` 的判定依据是错的。
处理方式二选一：出包前核实 bundle 实际版本并改 `version.ini`，或在 `02-build-bundle.sh`
收尾加一条 `$PREFIX/bin/node -v` 与 `version.ini` 的断言，让漂移在出包时就失败。

## 13. 三块页面的入口（r114 补）

**此前三页在默认 `local` 档下没有入口**：配对页只能从离线页 / 切换器进，而离线页只在
远端档案启动时出现。功能做完了却点不到，等于没做。现在补在官方窄栏：

- `connection-entry.js` 在 `#dsh-rail-foot` 插一个「连接」图标（与「设置」「引擎」并列）；
- 点击路由：`dshSaf.pairStatus()` 返回 `paired && hasToken` → 切换器；其余 → 配对页；
- 只加图标：不写页面、不注入 CSS 改布局、不重绘宿主组件（与 `engine-switcher.js` 同一先例）；
- `relay` 档页面是远端 origin，壳不注入任何桥，脚本读到 `dshSaf` 缺失即整体退出 ——
  不会在远端页面上留下一个点不动的按钮。

`OfficialShell.ASSETS` 的注入顺序有意义：official-bridge（建桥/侧栏）→ engine-switcher →
connection-entry（依赖 `dshSaf` 已存在）。

## 14. SSH 档（批 C）的真实阻塞

`02-build-bundle.sh` 第 72 行起支持 openssh 随包，但**当前安装包没有**：真机
`files/usr/bin` 下没有 `ssh` / `ssh-keygen` / `scp` / `sftp`（已用 run-as 核实）。
没有 ssh 客户端，`ssh -L` 隧道在本安装包上不可能跑起来，所以这批先不动。

解阻条件：在出包的 Termux 里 `pkg install -y openssh` 后重跑 `02b-harvest-bundle.sh` +
`02-build-bundle.sh`，新 bundle 会带上这四个二进制；那时再接 `DshSshTunnel`
（TOFU 主机指纹守卫 + 断线退避）才有意义。顺带说明：SSH 认证应走密钥而非密码
（`ssh-keygen` 生成到 `$HOME/.ssh`），密码认证需要 askpass/sshpass，而这两者都不在包内。

## 15. SSH 档（批 C）实现（r115）

代码已落地，但**当前安装包跑不起来**：`files/usr/bin` 里没有 `ssh` / `ssh-keygen`
（`DshSshTunnel.available()` 为 false，界面展示重出包指引，不假装连接）。

实现要点：

| 环节 | 做法 | 为什么 |
|---|---|---|
| 隧道命令 | `ssh -N -T -o BatchMode=yes -o ExitOnForwardFailure=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile=<home>/.ssh/known_hosts -o ServerAliveInterval=20 -o ServerAliveCountMax=3 -i <home>/.ssh/id_ed25519 -p <port> -L 127.0.0.1:<localPort>:127.0.0.1:3080 user@host` | 任一步失败都要**退出**而不是挂着一个假连接；`BatchMode` 让任何交互提问直接失败 |
| 认证 | ed25519 密钥（`ssh-keygen` 生成到 `$HOME/.ssh`），用户把公钥加到对端 `authorized_keys` | 包内无 askpass/sshpass，密码认证无法非交互完成 |
| 主机指纹 | `ssh-keyscan` 取原始行 → `SshHostKey.fingerprintOfKeyLine` → 与记录比对 | `StrictHostKeyChecking=no` 等于关掉中间人防线 |
| 判定 | 首次记录 / 一致放行 / **不一致阻断**；取不到本次指纹按不一致处理 | 把「读不到」当「首次」会让攻击者只要让扫描失败就能通过 |
| known_hosts | 非 22 端口写成 `[host]:port keytype base64`，同主机旧行先删 | 端口形态写错时 ssh 认不出这条记录，表现是「写进去了仍提示未知主机」 |
| 就绪 | 轮询 `127.0.0.1:<localPort>` 能否 accept（15s） | 「进程还活着」不是就绪：密钥被拒时进程也会存活数秒 |
| 环境 | `PREFIX`/`HOME`/`PATH`/`LD_LIBRARY_PATH`/`TMPDIR`/`OPENSSL_CONF` 与 `dsh-web.sh` 对齐 | 缺 `LD_LIBRARY_PATH` 报 `libz.so.1 not found`；缺 `OPENSSL_CONF` 报 `BIO_new_file: Permission denied` |

**解阻条件**：出包用的 Termux 里 `pkg install -y openssh` → 重跑 `02b-harvest-bundle.sh`
与 `02-build-bundle.sh`，新包 `usr/bin` 会带 `ssh`/`ssh-keygen`/`scp`/`sftp`；
此时 `available()` 转 true，整条链路即可启用（无需改代码）。

**仍未做**：断线自动重连（现在进程退出只报错，由用户重进）。接法是在
`DshSshTunnel.running()` 之外加一个守护轮询，按 1s→2s→4s…→30s 退避重启，
并在重连成功后不需要重建 WebView（回环端口不变）。

## 16. 出包流程：让 bundle 带上 openssh（r117）

`02b-harvest-bundle.sh` 从**已装 App 的私有目录**收割运行环境，而 App 的
`files/usr/bin` 只有 node/bash/pnpm/dsh-web.sh —— 没有包管理器，设备上也 `pkg install` 不了。
所以「bundle 带 openssh」的正确顺序是「先塞进 prefix，再收割」，不是改打包脚本。

```bash
# 1) 取 openssh 客户端进 prefix（PC 侧解 deb，不依赖 ar/7z）
bash scripts/fetch-openssh.sh
#    → 校验依赖库 → 下载 openssh_<ver>_aarch64.deb → 解出 ssh/ssh-keygen/scp/sftp
#    → run-as 落到 files/usr/bin → 真跑 `ssh -V` 自证

# 2) 递增 BUNDLE_REV（否则老安装命中旧戳、跳过重新解包，新 bundle 永远装不上）
#    android-java/com/dsh/harness/DshBootstrap.java: BUNDLE_REV

# 3) 收割（tar 清单自动包含 ssh 四个二进制，缺则不写进清单）
bash scripts/02b-harvest-bundle.sh

# 4) 出包
bash scripts/00-build-apk.sh BUNDLE=D:/work/dsh-bundle-0.2.2-rc.1-harvest.tar.gz

# 5) 出包前后各跑一次版本一致性
bash scripts/check-node-version.sh
```

两个 Windows 路径陷阱（`fetch-openssh.sh` 已处理，手写命令时要自己躲）：
MSYS 会把 adb 的**参数**当路径改写（`/data/local/tmp/x` → `D:/…/PortableGit/data/local/tmp/x`，
报 `secure_mkdirs() failed`），要 `MSYS_NO_PATHCONV=1`；关掉改写后源路径又得自己给
Windows 形式（`cygpath -w`），否则 adb.exe 按 `C:/tmp` 解析。

**随包的 ssh 是 Termux 构建**，RUNPATH 指向 `/data/data/com.termux/files/usr/lib`。
运行时必须靠 `LD_LIBRARY_PATH=$PREFIX/lib` 覆盖（`DshSshTunnel` 与 `dsh-web.sh` 都设了）。
在**没装 com.termux 的手机**上这一点尤其关键 —— 别把「本机能跑」当成「到处能跑」。

## 17. 远端档加载失败：文案按档案分流（r121）

**现象**：relay 档连不上电脑时，屏幕上是「服务还没就绪 / dsh 后台可能仍在启动，或已退出」。

**为什么是错的**：`serverNotReadyHtml` 是给**本机引擎**写的诊断文档，它假设「后端在
本机、正在启动」。而 relay 档 `needsLocalEngine() == false` —— 按设计不启 `DshService`、
不跑 node，本机根本没有后端可等。于是这句话把用户指向「重启应用/等一等」，
而真实原因是「那台电脑不在」，重试永远不会成功。

**修法**：`DshWebViewClient.showFinalError` 在加载 HTML 错误文档之前按档案分流：

```java
HostProfile.Profile prof = HostProfile.get(view.getContext()).current();
if (!prof.needsLocalEngine() && view.getContext() instanceof MainActivity) {
    ((MainActivity) view.getContext()).showRemoteUnreachable(prof);
    return;
}
```

| 档案 | `needsLocalEngine()` | 失败页 |
| --- | --- | --- |
| `local` | true | `serverNotReadyHtml` / `authExpiredHtml`（原有诊断） |
| `ssh` | false | 原生页「连不上这台电脑」 |
| `relay` | false | 原生页「连不上这台电脑」 |

原生页内容与三个出口：

- 标题「连不上这台电脑」，下面一行等宽字体的 `origin`（连的是哪台）；
- 说明「这台手机现在连的是电脑，不是本机引擎，所以这里没有 dsh 后台可等」；
- 四条常见原因：电脑休眠或关机 / Relay 没在运行 / 不在同一网络 / 已被管理台收回；
- 出口：**重试**（`finish()` + `startActivity(getIntent())`，走完整启动流程而不是只 reload）、
  **改用本机引擎**（`setCurrent(KIND_LOCAL)` + `ProfileSession.resetRuntime()` + 重启）、
  **退出**（`finish()`）。

`ProfileSession.resetRuntime()` 与切换器、离线页共用 —— R-22（切档必须清 cookie 与
预热实例，防跨主机串会话）只有一条实现路径，不在第四处再写一遍。

**幂等**：WebView 对同一次失败可能多次回调 `onReceivedError`/`onReceivedHttpError`，
用 `remoteErrorShown` 挡住重复渲染（否则视图被反复重建、按钮状态丢失）。

## 18. SSH 档隧道层：真机联调与被它揪出的缺口（r122）

### 18.1 为什么需要一个测试替身

本机（Windows）只有 OpenSSH **客户端**：`C:\Windows\System32\OpenSSH` 里没有 `sshd.exe`；
Windows 的 sshd 必须由 SYSTEM 起服务，普通账户跑不起来。于是 `tests/ssh-testdouble/`
用 `ssh2` 写了一个最小服务端：**接受任意公钥 + 只转发 direct-tcpip**，配一个只绑回环的
假 3080（页面打出大字 `FAKE 3080`）。它验的正是手机侧隧道要的四个条件，不需要真 sshd。

```bash
cd tests/ssh-testdouble && npm install
node fake-dsh.mjs            # 127.0.0.1:3080，只绑回环 → 必须经隧道才到得了
PORT=2222 node server.mjs    # 0.0.0.0:2222，日志里有主机指纹与客户端公钥指纹
bash device-tunnel-test.sh   # 推 device-tunnel-test.sh 到设备，run-as 里跑
```

### 18.2 真机证据（2026-11-14，vivo X200s / Android 15）

设备经 Tailscale（`100.99.226.70`）连电脑，全程 `run-as` 在 App 的 prefix 里跑，
不经过 UI、不抢前台。

| 环节 | 实测结果 |
| --- | --- |
| bundle 里的 ssh 可执行 | `OpenSSH_10.6p2, OpenSSL 3.6.3` |
| 客户端密钥 | 新生成 ed25519，`SHA256:R72D7mGQA5EcDsHA1WDDlizxkCkOr5jZ7jyI3yydFfM` |
| 服务端看到的客户端公钥 | `SHA256:R72D7mGQ…`（与服务端日志一致 → 确实是本机这把） |
| known_hosts 行首形态 | `[100.99.226.70]:2222 ssh-ed25519 AAAA…`（非 22 端口必须带方括号） |
| `StrictHostKeyChecking=yes` | 通过（没有「未知主机」提示） |
| direct-tcpip | 服务端日志 `direct-tcpip 127.0.0.1:3080 ← 127.0.0.1:37656` + `✓ 隧道已建立` |
| 经隧道取页面 | `HTTP 200 ✓ 含 FAKE 3080 标记`，页面里 `host=127.0.0.1:3081` |

最后一行是关键：页面看到的 Host 是**手机侧隧道口** `127.0.0.1:3081`，而内容来自
电脑的回环 3080 —— 只有真的走了通道才会同时成立。

### 18.3 被揪出的缺口：bundle 里没有 `ssh-keyscan`

`fetch-openssh.sh` 的二进制白名单是 `ssh / ssh-keygen / scp / sftp`，
`02b-harvest-bundle.sh` 的 tar 清单照抄了这四个 —— **`ssh-keyscan` 从来没进过包**
（真机 `ls files/usr/bin` 已确认）。后果：

1. `DshSshTunnel.scanKeyLine` 第一行 `keyscan.isFile()` 为假，直接返回 `null`；
2. 调用方把 `null` 呈现成「取不到主机公钥，确认主机可达/sshd 在运行/同网段」——
   方向错了：包缺一个二进制，用户怎么查网络都不会好；
3. `SshHostKey.decide(null, …)` 按「实际指纹为 null ⇒ MISMATCH」保守处理，
   于是真要往下走只会得到「指纹不一致，已拒绝」，更难自查。

所以 r122 做了三件事：

- **补包**：两个清单都加上 `ssh-keyscan`，`BUNDLE_REV` `v6-…-ssh2` → `v7-…-ssh3`
  （内容变了不推 REV，老安装会命中旧戳跳过重解包，新二进制永远装不上）。
- **兜底**：`scanKeyLine` 在 keyscan 缺失、或 keyscan 扫不到时，改走
  `scanKeyLineViaConnect()` —— 用一次 `StrictHostKeyChecking=no` 的**试探连接**
  让 ssh 自己把对端公钥写进临时 `known_hosts`，再读出来。
  这**不是安全降级**：host key 在密钥交换阶段就完成校验并落盘，**早于**用户认证，
  信任模型与 `ssh-keyscan` 完全一致（都信任「第一次看到的那把」）；真正的比对
  （`FIRST_SEEN` / `MATCH` / `MISMATCH`）仍在调用方，正式连接永远
  `StrictHostKeyChecking=yes` + 正式 `known_hosts`。
- **说清原因**：失败文案区分「包里没有 keyscan 且试探也没成」与「对端不可达」。

兜底已在真机单独验过（`tests/ssh-testdouble/device-keyscan-fallback-test.sh`）：
临时文件里得到 `[100.99.226.70]:2222 ssh-ed25519 AAAAC3…`，指纹
`SHA256:MRPEZ8PGUIxK+z0xHpFf+hIsd1XnrpD89umRoA1ZtEs` 与服务端一致，
ssh 输出 `Warning: Permanently added … to the list of known hosts` 后
`Permission denied (publickey)` —— 认证失败是**预期**的（试探用的用户名不存在）。

### 18.4 仍未做/未验

- **断线自动重连没写**：现在进程退出只报错，用户重进才重连。接法是
  `running()` 之外加守护轮询，1s→2s→4s…→30s 退避，重连成功不需要重建 WebView
  （回环端口不变）。
- 配置入口与 UI 全链路见下一节（r123 已补）。

## 19. 第四块原生页：SSH 主机配置（r123）

### 19.1 缺的到底是什么

`HostProfile.setSsh(host, port, user, localPort)` 在 r115 就写好了，但**全仓库没有调用方**：
没有页面、没有表单、切换器只列 Relay 侧主机。隧道层通不通都得先能填地址，所以这条比
「缺一台 sshd」更前置 —— 用户连"配错"的机会都没有。

### 19.2 页面构成

四个输入框（主机 / SSH 端口 / 用户名 / 本机端口）+ 两个动作：

| 动作 | 做的事 | 失败时 |
| --- | --- | --- |
| 测试连接 | 取主机公钥 → 指纹比对 → 生成/读取密钥 → 写 known_hosts → 起 `ssh -L` → 等本地端口 accept → 停掉 | 报**那一步**的原因，不合并成「连接失败」 |
| 保存并连接 | `setSsh` → `setCurrent(KIND_SSH)` → `ProfileSession.resetRuntime()` → 拉起 MainActivity（带 `enterRemote=true`） | 档案保存失败时明确说「重启后再试」 |

「测试连接」按真连接验，因为 SSH 档的失败几乎都发生在后两步（公钥没被接受 / 转发没建立），
只探端口证伪不了。中途把本机公钥摊在页面上（`setTextIsSelectable`，能长按复制）——
用户要把它贴到对端 `~/.ssh/authorized_keys`，这是密钥认证唯一的开通动作。

**测试阶段不钉住首次见到的主机指纹**：钉住属于「保存并连接」。否则一次手滑的测试会留下
错误钉子，之后每次连接只会报「指纹不一致」，而真正的原因早已埋在第一次测试里。

### 19.3 校验抽成纯函数（`SshForm`）

校验规则写错时的症状是「连不上但说不清哪错了」，所以不放在 Activity 里靠手点验：

- 主机栏只准主机名/IP：`ssh://` 前缀、`user@host`、冒号带端口、前导 `-`（`-oProxyCommand=`
  这类参数注入）全部拒绝 —— 它们都会拼出与用户意图不同的命令。
- 端口 1–65535；本机端口 1024–65535（更低需要系统权限）且不得与 SSH 端口相同。
- 非法数字返回 `-1` 交给校验报错，**不静默回落默认端口**：把 `2222` 打成 `22a2` 时
  回落 22 会真的连到另一台机器的 22 口上，比报错危险。

`SshForm` 在桌面 JDK 下有 25 条断言（`scripts/test-java.sh` 的纯逻辑段，总断言 78 → 101）。

### 19.4 三处入口

| 页面 | 按钮 | 为什么在这里 |
| --- | --- | --- |
| 主机切换器 | 连 SSH 主机 | 已在「连哪台电脑」的心智里 |
| 配对页 | 改用 SSH 连一台机器 | 未配对的人停在这里；SSH 不需要配对码 |
| 离线页 | 连 SSH 主机 | 那台电脑连不上时，用户最可能在这页找出路 |

SSH 不走 Relay、不需要配对码，所以入口不能只挂在配对流程里 —— 否则「只想连一台能 ssh
的机器」的人得先假装配一次对。

### 19.5 顺带修掉的两个结构性问题

1. **清单注入的外层守卫**：原来只看 `HostSwitcherActivity` 是否存在就整块跳过，
   于是**新增**的页永远进不了注入块（已存在的页挡住还没注册的页）。改为逐页全查，
   本次运行输出 `✓ 注册原生页: SshConfigActivity` 即是证据。
2. **`waitLocalPort` 一处实现**：原先是 `MainActivity` 的私有方法，配置页要验端口就得抄一份。
   下沉为 `DshSshTunnel.waitLocalPort(int, int)`，主界面与配置页共用同一份就绪判据。

## 20. 三处认知偏差的纠正（r124）

真机试用后反馈了三个问题，逐个查证的结果如下 —— 其中两处是**代码错**，一处是**文案错**。

### 20.1 「SSH 连接失败」：四层原因，一层比一层靠前

| 层 | 事实 | 证据 |
| --- | --- | --- |
| 1 | `100.99.226.70:2222` 是联调用的**测试替身**端口，联调结束就停了 | 无进程监听 2222 |
| 2 | 这台电脑**没有 sshd** | `Test-NetConnection 127.0.0.1 -Port 22` → False；Windows 的 OpenSSH Server 是可选的系统能力，安装需要管理员提权（当前会话 `elevated=False`） |
| 3 | 隧道远端端口**写死 3080** 是错的 | 这台 Windows 上 dsh 监听 **61167**（`GET /` → 401），3080 上什么都没有 |
| 4 | dsh web 对**回环请求也要令牌** | `curl http://127.0.0.1:61167/` → `401 Unauthorized` |

第 1、2 层属于环境：没有 sshd 就谈不上 SSH 档，这不是代码能绕过的（除了自己实现一个 sshd，
那是另一套东西）。第 3、4 层是产品缺陷，r124 修掉：

- 档案新增 `targetPort`（远端 dsh 端口，默认 3080）：`-L 127.0.0.1:<localPort>:127.0.0.1:<targetPort>`。
  原来写死 3080 是拿手机端引擎端口想当然 —— 隧道 "通" 了也只是打到空端口，症状是端口 accept
  了、页面空白或连接被重置。
- 档案新增 `token`：加载地址改由 `SshForm.sshUrl(localPort, token)` 生成（空令牌不拼查询串，
  非空拼 `?token=` 并 URL 编码）。
- 「测试连接」在端口通之后**再取一次 HTTP 状态码**，把三种"通了但进不去"分开报：

| 状态码 | 说明 |
| --- | --- |
| 200 | 通了且能进 |
| 401 / 403 | 隧道通了，电脑侧 dsh 拒绝访问 —— 令牌不对或没填 |
| 0 / -1 | 隧道通了但请求没应答 —— 多半是远端端口填错 |
| 其它 | 那头有服务，但不像是 dsh 的界面 |

### 20.2 「连接到电脑」这一页什么时候出现

`OfflineActivity` 的 choice 模式（标题「连接到电脑」）。触发条件写在 `MainActivity` 里：

```
当前档案是远端（relay / ssh）
  且 这次启动没带 enterRemote=true
  且 autoEnterRemote() 为 false（默认）
  → 立刻跳本页并 finish 自己
```

换句话说：**只要档案停在远端、而你是"重新打开应用"进来的**，就会先看到它。这是 r113
「按需连接」的设计 —— 打开应用多数意味着"用手机上的 dsh"，自动连一台可能离线的电脑只会
停在白屏。带 `enterRemote=true` 的路径只有两条：本页的「进入这台电脑」、切换器切换成功后
的自动跳转。

页面上「电脑在线（1 台）」是真的：`adb reverse tcp:8899 tcp:8899` 把手机的
`127.0.0.1:8899` 接到了电脑上的 Relay，所以 `/health` 探测确实通了。这是联调期的接线，
不是产品形态。

### 20.3 「当前使用中」到底是什么意思（文案错，已改）

那个标记来自 Relay `GET /api/hosts` 的 `bound` 字段，服务端定义（`server.js`）：

```js
row.bound = d.deviceId === owner.boundHostId
```

即「这台手机在 Relay 侧**绑定的目标主机**」，跟"此刻正在使用"没有关系：档案切回本机时，
绑定记录照样存在，标记也照样亮。把归属读成实时状态是纯粹的误导 —— 已改为
「这台手机的绑定目标」，并在页首写明「绑定是归属；在线只看电脑最近一次心跳（20 秒内没心跳
就显示离线）」。

顺带修掉一处同源措辞：点击已绑定行时的提示，从「已经是当前电脑」改为
「已经是这台手机的绑定目标（不代表此刻正在使用）」。
