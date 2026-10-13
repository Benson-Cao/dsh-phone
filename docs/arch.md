# 架构简图与修改指南

一句话：**Java 壳负责解包 / 修环境 / 拉进程 / 托管 WebView，node 运行时负责真正的 dsh 服务。**
两者唯一的通信通道是 `dsh-web.log` 里的结构化状态行（`DSH_STATE:` 协议）。

---

## 1. 目录与职责

```
dsh-harness-android/                ← 本仓库：harness 真相源（无gradle 工程）
├── version.ini                ★ 版本单一来源（versionCode / 版本号 / dsh 版本）
├── android-java/com/dsh/harness/   ← Android 壳 Java 源码（会被拷进 termux-app）
│   ├── MainActivity.java    WebView 宿主、启动页、DSH_STATE 消费
│   ├── DshService.java      前台服务（保活，让 dsh 跨 App 生命周期存活）
│   ├── DshBootstrap.java    解包 / 完整性校验 / 软链修护 / 生成 launcher
│   ├── DshProcessManager.java 进程启停 / 端口探测 / 日志 tail / 状态扫描
│   ├── DshProbe.java        就绪探测（**只有根路径真返回 200 才算就绪**）
│   ├── DshBridge.java       WebView JS ↔ Java 桥
│   ├── DshOrigin.java       本地来源判定（fail-safe：不确定就判非本地）
│   ├── DshExporter.java     方案 C：白名单校验 + FileProvider Uri（导出 / 另存为）
│   ├── SafConfig.java       方案 A：SAF 授权 + 挂载点持久化，写 saf_config.json
│   ├── SafTree.java         方案 A：相对路径 → document Uri，读写 / 列目录 / 改名
│   ├── SafProxyServer.java  方案 A：127.0.0.1 回环 HTTP 代理（token 鉴权）
│   ├── ShellRunner.java     执行 shell / 组装环境变量
│   └── ...
├── assets/dsh/                    Web 资源（market / mobile 样式等）
│   ├── mobile.js  mobile.css  market.css  market-chrome.css
│   └── saf-fs-shim.js       方案 A：node 预加载 FS 路由补丁（拷进 $PREFIX/libexec）
├── scripts/                        宿主机构建脚本（见 scripts/README.md）
└── docs/                           本文档

termux-app/                        ← Gradle 工程（Android Studio 打开这个）
└── app/src/main/
    ├── java/com/dsh/harness/  ← ★ 由脚本从上面拷入，不要在这里直接改
    ├── java/com/termux/...    Termux 原生源码（改包名由脚本完成）
    └── assets/                   dsh-bundle.tar.gz + patch.sh（脚本放入）
```

> ⚠️ **两份仓库，别混**：
> `dsh-harness-android` 是 harness 源码的真相源；`termux-app` 是真正能编译的 Gradle 工程。
> 它的 `com/dsh/harness/` 是**拷贝产物**，直接改会在下次同步时被覆盖。
>
> `AndroidManifest.xml` **只存在于 termux-app**，由 `01-modify-termux.sh` 幂等改造
> （改包名 / 注入 LAUNCHER / 注册 FileProvider）。**不要**从任何地方反向拷贝覆盖它。

---

## 2. 启动时序

```
MainActivity.onCreate
  └─ 后台线程
      ├─ DshService.start()          前台服务保活（dsh 跨 App 存活的关键）
      ├─ 端口已开？ ── 是 ─→直接进 WebView（"8s → 亚秒级"的关键分支）
      └─ 否
         ├─ DshBootstrap.setupIfNeeded()
         │   ├─ emitState precheck      空间 / 完整性抽查
         │   ├─ emitState extracting    解包 700MB（首次 1–3 分钟）
         │   ├─ 软链 / ICU / koffi 修护
         │   ├─ patch.sh（从 assets 落地后执行）
         │   └─ createLauncher()      ★ 生成 bin/dsh-web.sh
         └─ DshProcessManager.start()
             └─ bash dsh-web.sh
                 ├─ DSH_STATE start_dsh     ┐
                 ├─ DSH_STATE termux_init   ┘ 写进 dsh-web.log
                 └─ exec node ... web       ← node 接管 PID（保活基础）
                      └─ 打印 token URL → DshProcessManager 规范化为 ready
                           └─ DshProbe.tryReady 轮询到 200 → showWebView
```

**为什么 node 用 `exec` 接管 PID**：`ProcessBuilder` 起的子进程会随调用栈结束被回收，
只有 `exec` 让 node 成为进程组长，才能跨 App 存活。这是 `DshProcessManager.start()`
注释里「不能用 `cmd &` + waitFor」的原因。

---

## 3. Java ↔ 运行时通信（DSH_STATE 协议）

完整规范见 [`contract.md`](contract.md)。三条最容易踩的：

1. **单行 JSON**，禁止换行。
2. **必须写进 `dsh-web.log`**（stdout/stderr 都重定向到它），不用 stderr。
3. **两套来源，一个观察者**：
   - 运行时（launcher/node）→ 写 `DSH_STATE:` 行 → 文件扫描 → `StateObserver`；
   - Java 自身（precheck/extracting）→ `DshProcessManager.emitState()` 直接进程内回调。
     原因见 `contract.md` §6.1：bootstrap 阶段的日志只进 Logcat，不进 `dsh-web.log`。
   - `emitState()` 内部统一切主线程，调用方可在任意线程调用。

---

## 4. 修改指南：改 X 要动哪里

| 想改什么 | 动哪里 | 注意 |
|---|---|---|
| **升级 dsh / node_modules** | 手机 Termux 跑 `scripts/02-build-bundle.sh` 重打 bundle | ① 产物传到 PC；② **递增 `DshBootstrap.BUNDLE_REV`**（否则旧安装跳过解包，新 bundle 装不上）；③ 递增 `version.ini` 的 `version_code` |
| **改 Web 界面 / 样式** | `android-java/assets/dsh/` | 直接改，`00-build-apk.sh` 会带进 assets |
| **改启动流程 / 解包逻辑** | `DshBootstrap.java` | 改完记得 `scripts/sync-from-source.sh` 同步进 termux-app |
| **改 WebView 交互 / JS 桥** | `DshBridge.java`、`DshChromeClient.java` | 文件选择走 `onActivityResult` 回灌，别只写一半 |
| **改进程保活 / 端口** | `DshProcessManager.java`、`DshService.java` | 端口只有一个定义源：`DshBootstrap.DSH_PORT` |
| **改就绪判定** | `DshProbe.java` | **不要**改成「端口开了就算就绪」——frontend-static 注册 fallback 前会404 空响应体 |
| **改包名 / App 名** | `scripts/01-modify-termux.sh` | 不要手改 termux-app，会被脚本覆盖 |
| **改版本号** | `version.ini` | 唯一来源。`version_code` 必须递增，否则系统判降级拒绝安装 |
| **加新的启动状态** | `contract.md` §3 枚举 → 对应执行方 | 枚举是**固定**的，新增要同步改契约；优先复用现有枚举值 |

---

## 5. 明确禁止的改动

来自 `maintainability-plan.md` §七，写在这里是为了让后来者（包括 AI）别"顺手优化"：

- ❌ **不引入 Kotlin / Compose / 重第三方 Android 库**（Termux v0.118 是纯 Java，无 Kotlin 插件，`.kt` 会被完全忽略、不编进 dex）。
- ❌ **不把 shell 业务逻辑迁到 Java**（当前职责划分是对的：Java 管编排，脚本极小且由 Java 生成）。
- ❌ **不把 `android-java` 合并回仓库根**。
- ❌ **不在 `termux-app` 里直接改 harness 源码**（会被同步覆盖）。
- ❌ **不用 `usesCleartextTraffic=true` 全局放行明文**（那等于对任意域名开明文）；用 `network_security_config` 收窄到 127.0.0.1/localhost。
- ❌ **不把 FileProvider 的 `exported` 改成 true**，也不把 `dsh_file_paths.xml` 扩到
  `filesDir` 根目录或含 `node_modules`/`usr`——那等于把 700MB 运行时与 node 二进制
  交给任意第三方 App。详见 [`saf-feasibility.md`](saf-feasibility.md) §6。