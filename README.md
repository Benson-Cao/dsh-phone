# dsh Phone —— DeepSeek Harness 打包成 Android App

把手机 Termux 上跑的本地 AI 服务 **DeepSeek Harness（dsh）**，打包成一个可安装的独立
Android APK：装完自带 Node 运行时 + `node_modules` + 全部依赖 `.so`，首启自动拉起
Web 服务，用 WebView 打开 `http://127.0.0.1:3080`。

方案是 **Fork Termux 魔改**（B1）：改包名 / App 名 → 预置运行环境 → 首启解包打补丁 →
后台起 dsh → WebView 套壳。App 用 Java 写（Termux v0.118.0 **没有 Kotlin 插件**，
`.kt` 会被完全忽略、不编进 dex）。

---

## 架构

```
github.com/termux/termux-app  (v0.118.0, fork)
   │
   ├─ applicationId → com.dsh.harness        (v0.118.0 原本没有 applicationId，
   │                                          缺了会退回 namespace=com.termux)
   ├─ App 名       → DeepSeek Harness        (manifestPlaceholders 只对 Manifest 生效，
   │                                          strings.xml 的实体必须 sed 替换)
   ├─ assets/dsh-bundle.tar.gz  (736MB: node + bash + lib/*.so + node_modules)
   └─ app/src/main/java/com/dsh/harness/*.java
                │
                ▼   编译 → APK (~270MB, arm64-v8a)
                │
   ┌────────────▼─────────────────────────────────────────┐
   │ 首启（全部在后台线程，界面显示进度而非黑屏）           │
   │  1. DshBootstrap.setupIfNeeded()                    │
   │       完整性抽查 → 解包 tar → chmod → 补软链 →        │
   │       写 openssl.cnf → 铺 koffi 原生模块 → 生成启动脚本│
   │  2. DshProcessManager.start()                       │
   │       bash usr/bin/dsh-web.sh → node --expose-internals
   │  3. DshProbe.run()   Java 侧自己走鉴权握手            │
   │       拿到 200 才把 cookie 写进 CookieManager 并加载   │
   │       拿不到 → 把原始状态码/响应头/日志打在屏幕上      │
   │  4. WebView 加载 http://127.0.0.1:3080               │
   └──────────────────────────────────────────────────────┘
```

---

## 目录

```
android-java/com/dsh/harness/     我们自己的 8 个 Java 文件（唯一需要维护的代码）
  DshBootstrap.java               解包 / 完整性自愈 / 环境修护 / 生成启动脚本
  DshProcessManager.java           起停服务 / 端口探测 / 从日志抓 token URL
  DshProbe.java                   手机端体征自检（状态码/响应头/dist/日志）
  DshChromeClient.java            附件：接上系统文件选择器
  MobileTuning.java               手机布局适配（注入 CSS + 自动折叠侧栏）
  DshWebViewClient.java           内外链分流 + 错误页
  MainActivity.java               加载界面 + 启动编排 + 体征自检展示
  ShellRunner.java                环境变量拼装

scripts/
  00-build-apk.sh                 PC：一键构建（clone → 改造 → 编译）
  01-modify-termux.sh             PC：改造 termux-app 源码（幂等，可重复跑）
  02-build-bundle.sh              手机 Termux：打包 dsh 运行环境
  04-repack-bundle-layout.py      PC：把 bundle 重排成 Termux 布局 + 补软链
  patch.sh                        首启后自动执行的补丁

docs/排障记录.md                  17 轮真机排障沉淀下来的坑（必读）
```

---

## 前置条件

| 项 | 说明 |
|---|---|
| **NDK 29.0.14206865** | **必须**。Termux 的 app / terminal-emulator / termux-shared 三个模块都走 `ndkBuild` |
| JDK 17 | 如 `D:\AndroidSDK\jdk17` |
| Android SDK | `platforms;android-36` + `build-tools;36.0.0`（compileSdk 36 / targetSdk **28**，后者故意压低，别升） |
| 一台手机 Termux | 用来跑 `02-build-bundle.sh` 打包运行环境 |
| 工程路径**不能含空格** | `ndk-build.cmd` 是批处理，含空格就报 "APP_BUILD_SCRIPT points to an unknown file"。**junction 无效，必须 `mv`** |

---

## 构建

### 第 1 步 · 手机 Termux 打出运行环境

```bash
bash 02-build-bundle.sh
# 产出 ~/storage/shared/dsh-build/dsh-bundle.tar.gz，传到 PC
```

⚠️ 两个硬要求：
- **不要加 `-h/--dereference`**。node/bash 的 NEEDED 是短名（`libz.so.1`、`libicuuc.so.78`），
  真实文件带完整版本号，短名在 Termux 里正是**符号链接**；一旦 dereference 软链就全丢 →
  运行时 `library not found`。
- 布局必须是 Termux 约定：`usr/bin/{node,bash}`、`usr/lib/*.so`、`home/node_modules/`。

如果拿到的 bundle 是**扁平布局**（`bin/`、`lib/`、`node_modules/`）或**软链丢失**，
用 `04-repack-bundle-layout.py` 重排一次（见该脚本头部说明）。

### 第 2 步 · PC 编译

```bash
bash scripts/00-build-apk.sh                                   # 默认 ./termux-app
REPO=/d/work/termux-app bash scripts/00-build-apk.sh \
     BUNDLE=/path/dsh-bundle-gnu-v2.tar.gz                    # 指定 bundle
PROXY=127.0.0.1:51651 bash scripts/00-build-apk.sh            # 需要代理时
INSTALL=1 bash scripts/00-build-apk.sh                        # 编完自动 adb install
```

产物：`termux-app/app/build/outputs/apk/debug/termux-app_apt-android-7-debug_arm64-v8a.apk`

### 第 3 步 · 装到手机

直接安装，覆盖安装即可（升级不会重新解包，除非触发完整性自愈）。
包名 `com.dsh.harness`，versionCode 999。

---

## 手机端能力

| 能力 | 说明 |
|---|---|
| Web UI | 完整 dsh Web 界面，桌面/手机自适应 |
| **附件上传** | 自接 `WebChromeClient.onShowFileChooser` → 系统文件选择器（WebView 默认不实现，不接就是"点了没反应"） |
| 自动折叠侧栏 | 窄屏（<1024px）下走 dsh 自己的状态机折叠侧栏，主区全宽 |
| 软键盘 | `windowSoftInputMode=adjustResize`，输入区不被遮挡 |
| 明文流量 | `usesCleartextTraffic=true`（targetSdk 28 对 127.0.0.1 同样拦） |
| 自愈 | 关键文件缺失 → 自动清理重解包；解包写失败 → **不写安装标记**，下次重试 |
| 自检 | 服务异常时把状态码/响应头/响应体/dist 状态/服务日志直接显示在屏幕上（无 adb 时唯一渠道） |

---

## 已知限制

- **侧栏展开态仍是并排网格**（dsh 上游没有覆盖层形态），我们只限宽到 `min(58vw, 260px)`。能用，不算原生手感。
- APK 体积 ~270MB（bundle 本身就 736MB，装后解压约 740MB），**手机需留 ≥900MB 空间**（不足会明确报错，不会留残档）。
- 只验证过 **arm64-v8a** 真机。

---

## 排障

遇到问题先看 **[docs/排障记录.md](docs/排障记录.md)** —— 里面记了 17 轮真机排障的真实坑，
包括几个「看起来像 A，实际是 B」的误判（curl 走代理导致假 404、Windows Node 不认 `HOME`、
`File.list()` 只统计顶层导致完整性校验永远失败等）。照着它能省掉重复劳动。

最快的自检通道：**装上后如果没进 UI，屏幕会直接显示状态码 / 响应头 / dist 检查 / 服务日志**，
截那张图就等于有了 logcat。