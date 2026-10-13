# dsh-phone 构建脚本

宿主机构建（PC 侧）。**这些脚本只负责出包，不含任何运行时逻辑** —— 运行时逻辑在
`android-java/`（Java）与 `assets/dsh/`（Web 资源）里。

## 先跑这个

```bash
bash scripts/check-build-env.sh
```

它会在 3 秒内告诉你能不能构建、缺什么。上面那些 ⨯ 的项不修，构建一定失败。

## 脚本清单

| 脚本 | 在哪跑 | 作用 |
|---|---|---|
| `check-build-env.sh` | PC | 构建前自检：SDK/JDK/代理/bundle/脚本齐全性 |
| `00-build-apk.sh` | PC | **一键出包**：clone → 放 bundle → 改造源码 → 编译 → 版本化命名 + hash |
| `01-modify-termux.sh` | PC | 改造 Termux 源码：改包名 / versionCode / 注册 MainActivity（幂等，可重复跑） |
| `02-build-bundle.sh` | **手机 Termux** | 把 dsh 运行环境打成 `dsh-bundle.tar.gz` |
| `03-package-asset.sh` | PC | 只负责把 bundle + patch.sh 放进 APK assets（从 00 拆出，可单独重跑） |
| `lint-sh.sh` | PC | shellcheck；没装则退回 `bash -n` 语法检查 |
| `verify-sync.sh` | PC | 校验真源（本仓库）与构建树（termux-app）是否一致 |
| `sync-from-source.sh` | PC | 把本仓库 `android-java/` 同步进 termux-app |
| `verify-ondevice.sh` | PC + 手机 | 真机体检：连设备 → 装包 → 冷启 → 抓 logcat → 截图 |
| `remote-debug.sh` | PC + 手机 | **真机远程调试**：连设备 → 装包 → 冷启 → 建 WebView CDP 通道 → 拉**全量** `dsh-web.log`（诊断页只给末 25 行） |
| `cdp-eval.js` | PC | 零依赖 CDP 客户端（node ≥22 自带 WebSocket）：读/跑设备 WebView 里的 JS、盯 console 与未捕获异常 —— vivo 吞 logcat 时的唯一可靠通道 |
| `patch.sh` | — | 运行时补丁，由 App 解包后执行 |

## 前置依赖

- **PC**：bash（Git Bash / WSL）、git、curl、tar、sha256sum、python（注册 MainActivity 用，可选）
- **Android SDK**：`D:\AndroidSDK`（含 `build-tools`、`jdk17`）
- **Gradle 工程**：`D:/work/termux-app`（**路径不能含空格**，见下）

## 典型流程

### A. 只改了 Java / Web 资源（最常见）

```bash
cd D:/work/dsh-harness-android
bash scripts/sync-from-source.sh# harness Java → termux-app
cd D:/work/termux-app
export JAVA_HOME=D:/AndroidSDK/jdk17 ANDROID_HOME=D:/AndroidSDK
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY
./gradlew :app:assembleDebug --offline    # 离线，依赖已在 ~/.gradle/caches
```

### B. 从零出包（含归档）

```bash
bash scripts/check-build-env.sh
REPO=D:/work/termux-app bash scripts/00-build-apk.sh
# 有 bundle 时：
BUNDLE=/path/dsh-bundle.tar.gz REPO=D:/work/termux-app bash scripts/00-build-apk.sh
```

出包后会自动**归档 + 校验**：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `APK_OUT` | `D:/Program AI/DSH Phone/apk-out` | 归档目录；设为 `""` 跳过 |
| `APK_TAG` | `build` | 归档名里的内容标签，如 `export` / `protocol` |

产物命名：`DSH-Phone-<version_name>-r<build_rev>-<tag>-<abi>-debug.apk`
（版本来自 `version.ini`，**不再需要人工改名**）。归档后会重生成
`SHA256SUMS.txt` 并立刻自校验一次——258MB 的拷贝值得验。

> 为什么要归档：`app/build/outputs/apk/debug/` 会被 `./gradlew clean` 清掉，
> 且同名包会被覆盖、无法回退对比。归档按版本留档才有回退能力。

```bash
# 想手动归档到别处
APK_OUT=/d/other/dir APK_TAG=export bash scripts/00-build-apk.sh
```

### C. 真机远程调试 + 全量日志（vivo 吞 logcat 时的标准动作）

前提：手机开发者选项里「无线调试」已开（首次需 `adb pair <IP>:<配对端口> <6位码>`；
老式做法是先 USB 一次再 `adb tcpip 5555`）。

```bash
bash scripts/remote-debug.sh 192.168.0.55:41235                 # IP:连接端口
bash scripts/remote-debug.sh 192.168.0.55:41235 /path/xxx.apk   # 指定包
```

它一条龙做完：连设备 → `install -r -t` → 冷启 → 等 WebView 起来 →
`adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>` →
打印可调试页面清单 → 用 `run-as` 把**整份** `dsh-web.log` 拉到 `/d/work/ondevice/`，
并当场统计三项诊断页给不出来的东西：**启动器运行次数**、**EADDRINUSE 次数**、**第一处报错**。

拿到通道后（脚本末尾会再提示一遍）：

```bash
node scripts/cdp-eval.js --list                 # 有哪些页面
node scripts/cdp-eval.js --console 20           # 盯 console / 未捕获异常 20s
node scripts/cdp-eval.js "location.href"        # 直接问页面
node scripts/cdp-eval.js --file /tmp/check.js   # 跑一段断言脚本
```

图形界面党：`chrome://inspect` → 「配置网络目标」加 `localhost:9222`（转发已建好，
不需要在手机上点任何东西）。

## 五个会浪费几十分钟的坑

1. **工程路径不能含空格。** `ndk-build.cmd` 是批处理，含空格时 Gradle 报
   `APP_BUILD_SCRIPT points to an unknown file`。建 junction **无效** —— Gradle 会把路径
   解析回含空格的原始路径。唯一可靠做法：`mv` 到无空格路径。

2. **绝不要 `./gradlew clean`。** 会触发 bootstrap 资源重下，卡 30+ 分钟。只想增量就删
   `app/build/outputs/apk` 与 `app/build/intermediates/{assets,compressed_assets}`。

3. **`gradle.properties` 里写死的代理端口多半是死的。** 它是某次会话的临时端口，
   而代理端口会随会话变化。`check-build-env.sh` 会实测并在失效时给出两条出路。
   （注意：**端口能连 ≠ 是可用代理** —— 端口可能被别的服务占着，所以脚本发真实请求验证。）

4. **MSBuild / cmake 前必须清代理变量。** 环境里同时有 `HTTPS_PROXY` 和 `https_proxy` 时，
   .NET 字典键冲突 → `MSB6001 ... 已添加项`。

5. **改了 bundle 内容就要递增 `DshBootstrap.BUNDLE_REV`。** 否则旧安装命中 stamp
   **跳过重新解包**，新 bundle 永远装不上（且没有任何报错）。

## 版本管理

版本唯一来源是仓库根的 **`version.ini`**，脚本里不再有版本字面量。

出包后 `00-build-apk.sh` 会自动生成版本化副本并归档到 `APK_OUT`：

```
$APK_OUT/DSH-Phone-<version_name>-r<build_rev>-<tag>-<abi>-debug.apk
```

**不再需要人工改名** —— 过去靠改名讲批次，而 versionCode 恒为 999 时系统不会拦装错批次。

改 `version.ini` 时注意：
- `version_code` **必须递增**，否则系统判「降级安装」直接拒绝（当前 1000）；
- 每次出包建议递增 `build_rev`（当前 96），便于归档留档区分；
- 升级 dsh/node 版本时，**同时**递增 `DshBootstrap.BUNDLE_REV`（见坑 5）。