# NAS 存储 / 桌面端同步：可行性评估

> 需求：把 dsh phone 的存储目录改到 NAS，方便与 PC 端 DSH Desktop 同步。
>
> **状态：已按方案 1 落地（r75，2026-10-13）** —— 不是"目录改 NAS"，而是
> **只把会话/工作区数据同步到 NAS**。配置指引见 [`fnnas-setup.md`](fnnas-setup.md)。
> 本文保留完整评估与不可行原因，供日后回看。

---

## 0. 最终选型（用户确认后落地）

| 决策点 | 选择 |
|---|---|
| 实现位置 | 内置到 dsh phone（Java WebDAV 客户端） |
| 冲突策略 | 保留双份 + 手动合并 |
| 网络范围 | 仅局域网（FN Connect 外网访问已在配置文档中给出） |

新增文件：`DshWebDavClient.java`（纯 JDK HttpURLConnection，无第三方库）、
`DshSync.java`（同步逻辑 + 冲突策略 + 配置）、UI 在 `DshSafBridge` + `mobile.js`。

---

## 1. 先厘清：要同步的到底是哪些数据

实测本机 DSH Desktop 的数据布局（`C:\Users\Administrator\.dsh\`）：

```
.dsh/
├── storages/
│   └── workspace.json        ← 199 字节：工作区、会话索引、归档列表 ★ 真正要同步的
└── profiles/
    ├── node_modules/         96 KB
    └── web/
        ├── cordis.yml / cordis.patch.yml / package.json / pnpm-workspace.yaml
        └── node_modules/      0（空）
```

手机侧 `DshBootstrap.home()` = `filesDir/home/`，结构同构（`.dsh/profiles/web/...`）。

**结论：真正需要跨端同步的只有 `storages/*.json` 这类小文件（KB 级）。**
`node_modules`、`cordis.yml`、软链、二进制**都是平台相关的**（Termux 的 .so 与 Windows 的 .node 根本无法互换），
同步它们只会互相破坏。

> 这一条极其重要：**如果按"整个 .dsh 目录双向同步"来做，一定会把两端的 node_modules 搞坏。**
> 必须按文件白名单同步，这也是为什么方案必须"选择性同步"而非"目录共享"。

---

## 2. 为什么「把目录改成 NAS」不可行

三条硬约束，逐条说明：

### 约束 1：Android 没有「把网络文件系统挂成普通目录」的能力（对普通 App）

- `mount -t nfs/cifs` 需要 root；Android 应用无 root 就**做不到**。
- 应用能拿到的远程文件访问方式只有 **SAF（Storage Access Framework）**——
  而 SAF 只支持 **DocumentsProvider**（云盘、文件管理器提供的 provider），
  **SMB / NFS 不在支持范围内**。系统没有内置 SMB DocumentsProvider。
- 你在手机文件管理器里能连 SMB，那是**文件管理器 App 自己实现了协议栈**，不是系统能力。

### 约束 2：Termux userland 挂载需要 root，且 App 崩溃即失效

- Termux 可以 `pkg install` 后自己实现 SMB 客户端，但那是**在 Termux 自己的 userland 里**，
  而 dsh phone 是 **Termux fork 的独立 App**，它的 `filesDir` 是 Android 应用私有目录。
- 即使能用 Termux 的方式连 NAS，那也是**另一个 App 的能力**，我们这个 fork 无法直接复用。
- 且挂载点随进程/开机失效，需要守护进程重挂——对一个"常驻前台服务"是可行的，
  但引入root/用户空间文件系统依赖后，稳定性与安全边界都要重新评估。

### 约束 3：Android 11+ 分区存储限制公共目录

- 本项目 `targetSdkVersion=28`，不受分区存储强约束（这是当前的有利条件）。
- 但即便能写公共目录，**远程 NAS 仍然不在公共目录模型内**——约束 1 才是根本问题。

---

## 3. 可行方案（按推荐度排序）

### 方案 1：选择性文件同步（推荐）★

**思路**：数据仍留本地，通过一个**同步进程**把选定的少量文件推/拉到 NAS。

- 手机侧：dsh 仍用本地 `filesDir/home/.dsh/storages/`（node 需要高速本地 IO，不适合放网络盘）；
- 同步器：定时（如 5s/30s）或事件触发，把 `storages/*.json` 通过 **WebDAV / FTP / SMB** 同步到 NAS；
- PC 侧 DSH Desktop 同样加一个同步器指向同一个 NAS。

**优点**：不碰 Android 存储限制、不动 dsh 本体、跨平台一致；
**缺点**：不是实时，两端同时改同一文件会冲突（但可用"时间戳+冲突副本"缓解）。

**关键优势**：完全不用改 dsh 上游代码、不破坏 node_modules。

### 方案 2：SAF 代理（= 之前的方案 A，工作量大）

即 `docs/saf-feasibility.md` 里的方案 A：Java 起代理 + node FS 路由。
它能让 dsh "透明读写"用户授权的目录，但：
- 用户授权的是 SAF provider；**NAS 需要先以 WebDAV 形式暴露**，且系统里要有对应 DocumentsProvider；
- 要改 dsh npm 包内代码，**每次升级 dsh 都要重做**；
- 工程量远大于方案 1，且依赖上游。

**结论：除非 NAS 明确只通过 SAF 可达且你愿意每次升级 dsh 都重做，否则不选。**

### 方案 3：Termux userland 挂载 + root（不推荐）

技术最"直接"（真·挂载 NAS 成普通目录），但：
- 要root 或 `fuse` 用户空间挂载，安全性/稳定性差，Android 上FUSE 支持脆弱；
- 网络盘延迟会让 node 加载模块变慢甚至卡死；
- 与项目"不引入 root 依赖"的现状冲突。

---

## 4. 落地前必须先确认的两个问题

**这决定了选哪个方案，也是"目标模糊导致资源浪费"的高发区：**

1. **同步的粒度**：只同步 `workspace.json`（会话/配置索引，KB 级）？
   还是也要同步会话内容/工作区文件（可能更大）？
   → 前者：方案 1 足够；后者：要评估冲突策略。

2. **"实时"的要求**：能接受分钟级延迟（定时同步）？
   还是必须秒级一致（那才需要方案 2/3，成本高一个量级）？

3. **NAS 的协议**：SMB？WebDAV？NFS？
   → 不同协议在 Android 侧的可用方案差别很大（WebDAV 最容易，NFS 最难）。

---

## 5. 与当前已完成工作的关系

本轮已落地的**方案 C（FileProvider 导出）其实是方案 1 的一个特例**：
它已经能把dsh 的数据文件导出到手机任意位置/分享给其他 App。
如果 NAS 通过 WebDAV/SMB 的文件管理器暴露，**方案 C + 外部同步工具**可能就够你用了，
不需要动dsh 一行代码。

---

## 附：相关文件位置

- 手机侧数据根：`DshBootstrap.home()` → `filesDir/home/`（`.dsh/storages/`、`.dsh/profiles/web/`）
- 桌面端数据根：`%USERPROFILE%\.dsh\`
- 方案 C（已实现，可作为"导出到任意位置"基础）：`DshExporter.java` / `DshSafBridge.java`
- 完整 SAF 方案 A 评估：[`saf-feasibility.md`](saf-feasibility.md)