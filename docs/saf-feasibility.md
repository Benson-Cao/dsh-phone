# A1：SAF（Storage Access Framework）落地可行性评估

> 状态：**方案 A 已落地（2026-10-13）**，方案 B 维持不做，方案 C 作为「一次性导出」保留。
> 对应 [`contract.md`](contract.md) §8「SAF 存储桥接」。
> 已落地部分：方案 C 见本文 §6，方案 A 见本文 §7。
>
> 2026-10-13 修订：§4 的结论（"不建议做"）已被推翻，推翻的理由不是"想做了"，
> 而是**两个前提变了**，详见 §7.1。

---

## 1. 为什么现在做不了（机制层面的硬阻塞）

`contract.md` §8 的判断经复核**依然成立**，而且比原文更严重一点：

### 阻塞 1：node 进程根本触不到 Android 的 content API

dsh 跑在 Termux 的 **独立 Linux 子进程**（`DshProcessManager` → bash → `exec node`）。
SAF 的全部能力（`openInputStream`、`takePersistableUriPermission`）都在
**JVM 的 `ContentResolver`** 上。node 侧没有 `ContentResolver`，拿不到 `content://` Uri。

### 阻塞 2：现有唯一通道是单向的

现状通道只有一条：**运行时 → Java 的 stdout / `dsh-web.log`**（`DSH_STATE:` 单向上报）。
**没有** Java → 运行时的调用通道。SAF 需要的是后者（Java 读写文件、node 请求读写）。

### 阻塞 3（新增，原文没提）：已有的 `DshBridge` 帮不上忙

容易误以为"已经有 JS↔Java 桥了，复用一下就行"——**不行**，两者对象不同：

| | `DshBridge` | SAF 需要 |
|---|---|---|
| 调用方 | WebView 里的 JS（`window.dshNative`） | **node 进程**（dsh 服务端） |
| 载体 | `@JavascriptInterface` | 需要独立 IPC |
| 可用性 | 仅 WebView 在前台时 | node 常驻后台，无 WebView 依赖 |

`DshBridge` 依赖 Activity 存活（`WeakReference<Activity>`），而 dsh 是前台服务常驻的
——两者生命周期根本对不上。**不能复用。**

---

## 2. 真正可行的方案

`contract.md` §8 提的方向正确，但可以更具体。推荐**方案 A**：

### 方案 A：Java 本地 HTTP 代理 + node FS 路由适配（推荐）

```
node (dsh)
  │  fs.readFile('/data/user/0/com.dsh.harness/files/saf/notes/a.md')
  ▼
[FS 路由适配层]  ← 判断路径是否落在 SAF 挂载点
  │  改写成 HTTP 请求
  ▼
http://127.0.0.1:<port>/read?path=...
  │
  ▼
Java SafProxyServer（LocalServerSocket 或 NanoHTTPD）
  │  ContentResolver.openInputStream(Uri)
  ▼
外部存储真实文件
```

**为什么推荐这个**：node 侧只需替换 `fs` 层的路径解析，不改 dsh 任何业务代码。

### 方案 B：Unix domain socket

比 A 更"干净"（省掉 HTTP 头、无端口冲突），但 Termux bundle 里 node 用
`net.connect(path)` 走 UDS 需要确认该 API 在 Android 构建下可用——**这点需要先验证**，
本仓库暂无相关验证代码。

### 方案 C：不做 SAF，改用 Termux 私有目录 + 系统分享

如果真实需求只是"让用户能在文件管理器里看到 dsh 的文件"，那么
**私有目录 + `FileProvider` 导出单个目录**就能满足，成本是 SAF 代理的 1/10。
**建议先确认需求到底是哪一种** —— 这决定了要不要做整个代理子系统。

---

## 3. 工程量估算（基于现有代码结构）

| 工作项 | 涉及文件 | 预估 | 风险 |
|---|---|---|---|
| 用户选目录 + 持久化授权 | 新增 `SafConfig.java`；`MainActivity` 加 `onActivityResult` | 中 | Android 11+ 分区存储，Uri 权限易失效 |
| Java 侧代理服务 | 新增 `SafProxyServer.java`（约 300–400 行） | 大 | 需常驻；前台服务里跑 |
| 授权信息持久化 | `saf_config.json` + 读回 | 小 | JSON 解析已有 `org.json` 可用 |
| node 侧 FS 路由 | bundle 内 dsh 源码改动 | **大** | dsh 是 npm 包，改动会被升级覆盖 |
| 重启后授权存活 | Uri 权限重建 | 中 | 进程被杀后权限可能需重新获取 |

**合计：显著大于本轮其余全部工作之和**，且最后两项依赖上游 dsh 的可维护性。

---

## 4. 结论与建议

**结论：维持"暂缓"是正确的判断，不建议现在做。**

三条理由：

1. **代价不对称**：要做完整套 IPC 代理 + 改 node 侧 FS 层，
   而当前**没有任何已确认的功能需求**在等它。
2. **上游依赖**：node 侧的 FS 路由要改 `@deepseek-ai/dsh` 包内代码，
   每次升级 dsh 都要重做——这是持续的维护债，不是一次性成本。
3. **有更便宜的替代**：如果需求只是"文件可被外部访问"，
   **方案 C（`FileProvider`）** 能解决绝大部分，代价极小。

### 建议的下一步（不是"开始做"，是"先确认"）

在动手前先回答一个问题：**要解决的具体痛点是什么？**

- 「用户想把 dsh 的数据存到手机公共目录 / 用其他 App 编辑」→ 走 **方案 C**
- 「dsh 需要读写海量第三方 App 产生的文件（DCIM、下载目录等）」→ 才需要 **方案 A**

两者的实现量差一个数量级。在需求确认前投入代理子系统，大概率是白做。

---

## 5. 现状自检（可随时复核）

```bash
grep -rn "StorageAccessFramework\|DocumentsContract\|request_saf" \
     android-java/ --include=*.java
```

**预期结果：无匹配**（`request_saf` 在 [`contract.md`](contract.md) §8 里仅作为
"提示"语义保留，Java 侧未实现）。任何命中都意味着本评估已过期，需要重新评估。

>注：不要把 `content://` 放进这条 grep —— 方案 C 落地后它会正常命中
> （FileProvider 的 Uri 就是 `content://`），那是**预期**的，不是"SAF 代理回来了"。

---

## 6. 方案 C 已落地（2026-10-13）

需求确认结论：先做「用户主动把 dsh 数据导出/另存」这一档，方案 A 的代理子系统继续不做。

### 新增文件

| 文件 | 作用 |
|---|---|
| `android-java/com/dsh/harness/DshExporter.java` | 白名单校验 + `content://` Uri 生成 + 分享/另存 Intent |
| `android-java/com/dsh/harness/DshSafBridge.java` | 页面入口（JS 桥 `window.dshSaf`），另含`completeSave` |
| `android-java/res/xml/dsh_file_paths.xml` | FileProvider 路径白名单 |

### 改动文件

- `MainActivity.java`：注册 `dshSaf` 桥；新增 `REQ_SAVE_AS` + `startSaveAs()` + `onActivityResult` 分支。
- `scripts/01-modify-termux.sh`：新增 5.6（拷 paths xml）、6.5（注册 provider，**独立幂等**）、
  6.6（provider 与 xml 成对自检，缺则报错退出）。

### 三条硬性安全约束

1. **只导出白名单目录**（`home/`与 `home/.dsh/`），**不开 `filesDir` 根**——
   那里面有 700MB 运行时、node 二进制、软链；导出等于把可执行文件交出去。
   另外显式拒绝 `node_modules` 与 `usr/`。
2. **`android:exported="false"`**（FileProvider 默认），只有我们显式
   `FLAG_GRANT_READ_URI_PERMISSION` 授权的 App 能读，且**逐文件授权**、不做目录树共享。
3. **纵深防御**：导出属"把数据交出去"的高危操作，调用前用
   `localOriginAllowed()`（双来源判定，同 `DshBridge`）确认 WebView 此刻仍停在本地 dsh。

### 为什么 provider 注册要独立成一步（踩过的坑）

`01-modify-termux.sh` 里注入 MainActivity 的 Python 块**只在「还没注册过」时才跑**。
若把 provider 注册塞进那个块，第二次构建就会走 `else` 分支跳过 → provider 消失 →
运行期 `getUriForFile` 抛 `IllegalArgumentException`（只在真机暴露）。
所以 provider 注册独立判断，保证"跑一次"与"重复跑"结果一致。

### targetSdk=28 的影响

当前 `targetSdkVersion=28`，因此**不受 Android 10+ 分区存储限制**：
无需 `MANAGE_EXTERNAL_STORAGE` 等敏感权限，也无需 `requestLegacyExternalStorage`。
用户选好位置后，Java 侧可以直接把私有文件写过去。
这是项目**已有的取舍**（见 `DshService` 类注释），本次不改动。

### 页面入口（r74，已落地）

- 抽屉里新增一行「**导出数据**」（紧跟「插件」行，视觉节奏完全对齐），点击弹出导出面板。
- 面板列出 `listExportable()` 返回的文件（名称 + 大小），每行两个动作：
  - **分享** → 系统分享面板（`ACTION_SEND`，带 `FLAG_GRANT_READ_URI_PERMISSION`）
  - **另存为** → 系统保存界面（`ACTION_CREATE_DOCUMENT`），选中后由
    `DshSafBridge.completeSave()` 写入，面板自动关闭。
- 原生桥未就绪时**这一行不显示**（不给点不动的死按钮）。

---

## 7. 方案 A 已落地（2026-10-13）

### 7.1 为什么 §4 的「不建议做」不再成立

§4 列了三条理由，逐条复核的结果是**两条失效、一条依然有效**：

| §4 的理由 | 复核结论 |
|---|---|
| ① 代价不对称（无需求在等） | **失效** —— 需求明确了：让 dsh 把工作区放到用户可见的外部目录 |
| ② 上游依赖（改 npm 包 = 持续维护债） | **失效** —— 下面 7.2 的做法完全不碰 npm 包 |
| ③ 工程量显著大于其余工作之和 | **依然有效** —— 确实是大工程，但理由②消失后代价是一次性的，不再是"债" |

关键转折在②。动手前实测确认了两件事：

1. **dsh 的本地文件访问只有一个实现**：`@deepseek-ai/dsh-fs-local`。
   所有包（`dsh-tool-fs` / `dsh-session-persistence-jsonl` / `dsh-skill-filesystem` …）
   都走 `@deepseek-ai/dsh-fs` 这层抽象，落到 dsh-fs-local 上。
2. **dsh-fs-local 的 node fs 依赖面很窄且全是异步**：
   `chmod/link/lstat/mkdir/open/readFile/readdir/realpath/rename/rm/stat`
   （来自 `node:fs/promises`）+ `createReadStream`（来自 `node:fs`）。

既然只用到 12 个 API，就不需要"改 dsh 源码"——
在 **node 的 fs 模块对象**上打补丁即可，dsh 升级不受影响。

### 7.2 补丁怎么打到 ESM 上（本方案最容易翻车的一处）

`dsh-fs-local` 是 ESM，写法是 `import { readFile } from "node:fs/promises"`。
ESM 从 CJS 内建模块做**具名导入**时，值在 facade 首次求值那一刻被**快照**，
之后改 `fs.promises.readFile` 对它无效。

对策（`android-java/assets/dsh/saf-fs-shim.js` 末尾）：
打完补丁**立刻 `import()` 一次** `node:fs` 与 `node:fs/promises`，
强制 facade 在补丁已生效的当下定型；dsh-fs-local 之后 import 的是同一个被缓存的
facade，因此必然拿到包装版。定型结果会打到 stdout（进 `dsh-web.log`）：

```
[dsh-saf] FS 路由已装载（ESM facade: promises=true fs=true）
```

**真机排查第一步就看这一行**：只要有一个是 `false`，说明 facade 早于补丁定型，
SAF 会静默退化成"不生效"。

### 7.3 组件

| 文件 | 作用 |
|---|---|
| `android-java/com/dsh/harness/SafConfig.java` | 授权 + 挂载点持久化；写 `$HOME/.dsh/saf_config.json` 给 node 看 |
| `android-java/com/dsh/harness/SafTree.java` | 相对路径 → document Uri；列目录 / 读写 / 建目录 / 删除 / 改名 |
| `android-java/com/dsh/harness/SafProxyServer.java` | `127.0.0.1:<随机端口>` 上的最小 HTTP 服务，token 鉴权 |
| `android-java/assets/dsh/saf-fs-shim.js` | node 预加载补丁（FS 路由） |

改动文件：`DshBootstrap`（拷 shim 到 `$PREFIX/libexec/` + `NODE_SHIM` 里 `try{require}`）、
`MainActivity`（`REQ_SAF_DIR` + 选择器）、`DshSafBridge`（`safStatus/safPickDir/safClear/safSelfTest`）、
`DshService`（代理随服务起停）、`mobile.js` + `mobile.css`（抽屉「存储目录」行与面板）。

### 7.4 四道安全闸

1. **只绑回环**：`ServerSocket` 显式绑 `127.0.0.1`，端口取 0 由内核分配（不抢 3080）。
2. **对端再验一次**：accept 后核对是回环地址 —— 绑定不等于外部一定连不进来。
3. **token 鉴权**：每次请求校验 `x-dsh-token`，定长比较。同机任何进程都能连回环端口，
   没有 token 等于把文件柜敞开。
4. **路径必须在挂载点内**：node 侧算一次相对路径，Java 侧 `safeRel()` 再拒一次 `..`。

### 7.5 已知边界（有意不为，不是遗漏）

- **同步 API（`*Sync`）只做保守处理**：读类按 ENOENT 处理，写类 fire-and-forget。
  理由：dsh-fs-local 全部走异步，没有任何调用方需要同步；真要支持得再起 worker 线程
  做 `Atomics.wait` 阻塞式等待，现在不付这个复杂度。
- **单次读上限 32MB**（`SafProxyServer.MAX_READ`）：SAF 侧是整文件取回内存。
- **`rename` 不支持跨目录**：SAF 的 `renameDocument` 只能在同父目录改名，
  跨目录返回 EXDEV。dsh 的原子写是「`x.tmp` → `x`」，正好落在支持范围内。
- **挂载根 `$HOME/saf` 是真实的空目录**：它的存在只是为了让 dsh 的目录浏览能选中它，
  内容全部由 SafTree 提供。

### 7.6 怎么验证

不需要真机就能验 shim 的逻辑（起一个内存版假代理，跑 dsh-fs-local 实际会调的 API）：

```bash
node D:/work/dsh-saf-test/run-shim-test.js
```

覆盖：读写 / `stat` 与 `lstat(bigint)`（含 `info.mode & 511n` 这个 BigInt 断言）/
`readdir(withFileTypes)` / `open('r')` 顺序读游标 / `open('wx')→writeFile→rename` 原子写形态 /
`createReadStream` / 挂载点外仍走原生 fs / **ESM facade 定型**。

### 7.7 自检（取代 §5 那条已过期的命令）

§5 的 `grep DocumentsContract` 现在会命中 `SafTree.java` —— 那是**预期**的，
不是"评估过期"。改用这条做完整性自检：

```bash
ls android-java/com/dsh/harness/Saf{Config,Tree,ProxyServer}.java \
   android-java/assets/dsh/saf-fs-shim.js
grep -n "saf-shim" android-java/com/dsh/harness/DshBootstrap.java   # 应为 3 行
```

`DshBootstrap` 里那 3 行的分工，缺任何一行 shim 都不会生效：

| 行 | 作用 |
|---|---|
| `require('./dsh-saf-shim.js')` | 让 node 预加载它（用 `try{}` 包住，缺失也不影响启动） |
| `copyAssetToFile(ctx, "dsh/saf-fs-shim.js", …)` | 把它从 assets 落到 `$PREFIX/libexec/` |
| 剩下 1 行 | 注释 |
- 文件名经 `escapeHtml` 转义后才进 DOM；路径不进 DOM（只用序号索引回查）。

**遵循 `mobile.js` 既有铁律**：不搬不删 React 拥有的节点，只插入自己的行/浮层、
用 `insertAdjacentElement` 挂载、每轮 500ms 轮询复查（React 重排会摘掉注入节点）。

实测（Edge headless，`--allow-file-access-from-files`）：
导出行注入✓ 面板打开✓ 列表有数据✓ 分享/另存均正确传入路径✓ 另存后关闭✓
XSS 未触发✓ 窄屏无横向溢出（`scrollWidth == clientWidth`）✓

### 局限（务必知情）

- 方案 C 是「用户主动导出」，**不是**「dsh 透明读写外部目录」。若将来要后者，才回到方案 A。
- 未做导出进度/大文件分块（当前面向配置、笔记等小文件；几百 MB 的数据集不适合走此路径）。
- 列表有 200 条上限（`listExportable` 的 `max`），超出会截断且无提示。