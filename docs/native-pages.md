# 原生页面与设备身份（r111）

> 对应 wb-cloud-sync 的 v2.3 P3 并行前置项：手机壳侧的三块原生页面、设备公钥持久化、省电放行。
> 本文记录**已落地**的部分、口径与未落地部分的阻塞原因，避免下一轮重新推导。

## 1. 三块原生页面的状态

| 页面 | 状态 | 文件 | 说明 |
|---|---|---|---|
| 配对页 | 已落地 | `android-java/com/dsh/harness/PairActivity.java` | 电脑地址 + 6 位配对码，两步配对，失败按错误码给处置 |
| PC 离线页 | 已落地 | `android-java/com/dsh/harness/OfflineActivity.java` | 探 `/health`，区分「地址不通 / Relay 在但电脑没上线 / 在线 N 台」 |
| 主机切换器 | 未落地 | — | 阻塞点：切换器需要 CP 的 `/api/hosts` 与设备令牌，令牌归属 `client-core` 的存储层；App 内目前没有 `client-core` 运行时 |

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
