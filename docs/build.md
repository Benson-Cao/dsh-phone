# 编译步骤与踩坑

## 最短路径（改Java/Web 资源后出包）

```bash
cd D:/work/dsh-harness-android
bash scripts/sync-from-source.sh              # harness Java → termux-app

cd D:/work/termux-app
export JAVA_HOME=D:/AndroidSDK/jdk17 ANDROID_HOME=D:/AndroidSDK
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY
./gradlew :app:assembleDebug --offline
```

产物：`app/build/outputs/apk/debug/termux-app_apt-android-7-debug_<abi>.apk`

---

## 为什么必须 `--offline`

**本机实测**：Gradle 的 Java TLS 握手会被代理中断，`maven.google.com` 返回 502，
在线构建必然失败。但依赖**早已缓存在 `~/.gradle/caches`**，加上两项静态资源已就位：

- `termux-app/app/src/main/cpp/bootstrap-*.zip`（termux-packages release）
- `termux-app/app/src/main/assets/dsh-bundle.tar.gz`（dsh 运行环境）

所以 `--offline` 完全能出包，且**不碰网络**。这是本机唯一可靠的构建方式。

> 曾经有个错误结论："沙箱/本环境编不了 Android"。**那是错的** —— 本机完全能离线编，
> 只是 `/d/work/dsh-harness-android` 这个副本没有 gradle 工程，而在线代理又挡着google()。

---

## 代理的两个坑

### 1. `gradle.properties` 里写死的端口是死的

`termux-app/gradle.properties` 里有 `systemProp.http(s).proxyHost=127.0.0.1:51778` ——
某次会话的临时端口，早已失效。**不必改它**（`--offline` 直接绕开）；要在线构建才需要覆盖：

```bash
PROXY=127.0.0.1:<现查端口> bash scripts/00-build-apk.sh
```

端口随时会变，用 `env | grep -i proxy` 现查。

### 2. ⚠️ 端口能连 ≠ 代理可用

沙箱/会话会**复用端口**，`51778` 可能被别的服务占着 —— `curl` 探活会返回"可连"，
但经它访问 `maven.google.com` 立刻失败。`scripts/check-build-env.sh` 会发真实请求验证，
不要只探活端口。

### 3. MSBuild / cmake 前必须清代理变量

环境里同时有 `HTTPS_PROXY` 和 `https_proxy` 时，.NET 字典键冲突：
`MSB6001 ... 已添加项`。跑 MSBuild/cmake 前先 `unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY`。

---

## 会浪费几十分钟的坑

### ❌ 绝不要 `./gradlew clean`

会触发 bootstrap 资源重下，卡 30+ 分钟。只想增量就删：

```
app/build/outputs/apk
app/build/intermediates/assets
app/build/intermediates/compressed_assets
```

### ❌ 工程路径不能含空格

`ndk-build.cmd` 是批处理，路径含空格时 Gradle 报
`APP_BUILD_SCRIPT points to an unknown file`（NDK 找不到明明存在的 Android.mk）。

**建junction 无效** —— Gradle 会把路径解析回含空格的原始路径。唯一可靠做法：
`mv` 到无空格路径（`D:\work\termux-app`）。

### ❌ 改了 bundle 却没递增 `BUNDLE_REV`

`DshBootstrap.java:37` 的 `BUNDLE_REV` 是解包 stamp 的键。旧安装命中 stamp 就
**跳过重新解包**，新 bundle 永远装不上，而且**没有任何报错**。

改 bundle（升级 dsh / 改布局）→ 必须递增 `BUNDLE_REV`。

### ❌ 忘了递增 `version_code`

Android 用 versionCode 判「降级安装」并直接拒绝。版本唯一来源是 `version.ini`。

---

## Windows 沙箱环境备忘

| 事项 | 说明 |
|---|---|
| `cmd.exe` | 被安全策略禁用（Bash 和 PowerShell 都拦），不能用它跑 `vcvarsall.bat` |
| MSVC 环境 | 手动 export `INCLUDE` / `LIB` / `PATH`（MSVC bin + Windows SDK `bin\10.0.x\x64`，`rc.exe`/`mt.exe` 在后者） |
| 原生程序路径 | `git.exe` / `cmake.exe` 不认 Git Bash 的 `/d/...`，参数用 `D:/...`；但 PATH 里要用 POSIX 风格 |
| 测 localhost | 必须 `curl --noproxy '*'`，否则 `http_proxy` 会接管并返回代理的 404/502 |
| 下载大文件 | `codeload.github.com` 可能完全不通；用 `https://gh-proxy.com/<原始URL>` |
| 删除 | 删除被安全 shim 接管，失败时改用资源管理器右键删除 |

---

## 自检

```bash
bash scripts/check-build-env.sh          # 构建前 3 秒知道能不能编
bash scripts/lint-sh.sh --fix-hints      # 脚本语法 + 本项目高频坑自查
```