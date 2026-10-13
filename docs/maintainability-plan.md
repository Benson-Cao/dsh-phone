# 在保持 dsh-phone 简洁风格前提下提升可维护性（贴合现状版）

> 修订自原草案。原草案把现状描述成「Java 薄壳 + shell 巨型脚本」，与 `dsh-harness-android`
> 真实代码不符（编排主要在 Java，运行时脚本极小且由 Java 生成）。本版基于真实结构给出可落地项。

---

## 0. 真实架构基线

- **安卓壳** `android-java/com/dsh/harness/`：Java 承担解包、完整性校验、软链 / ICU / koffi 修复、
  进程管理、前台保活、WebView、token 抓取。
- **运行时**：`DshBootstrap.createLauncher` 生成 `bin/dsh-web.sh`（launcher），`patch.sh` 做补丁；
  node 跑 `@deepseek-ai/dsh`。
- **宿主机构建** `scripts/`：`00-build-apk` / `01-modify-termux` / `02-build-bundle` / `patch` /
  `push` / `sync-from-source` / `verify-sync`。
- **禁止项仍成立**：不引入 Kotlin / Compose / 重后台 Service；不把业务逻辑塞进 Java；
  不把 `android-java` 合并回仓库根。

核心原则不变：**维护性投入放在「契约、脚本拆分、日志、文档、构建流水线」，而不是增加 Java 业务代码**。

---

## 一、契约先行（最重要，且要贴合现状）

- 落地 `docs/contract.md`（已建，贴合现状版）：统一 Java ↔ 运行时状态协议。
- 把 Java 已承担的编排（解包 / 校验 / 保活 / 重启）**如实**写进契约的「职责边界」，
  而不是假设它们都在 shell（原草案的最大前提错误）。

---

## 二、真正该拆的脚本：宿主机构建脚本

原草案想拆的「运行时巨型脚本」**不存在**；真正会变面条的是 **宿主机构建脚本**
（sed 改 Termux 源码、打 bundle、重打包等）。建议：

- `scripts/` 已按 `00/01/02` 编号，继续职责单一化：
  - `01-modify-termux.sh`：只改源码 / 包名 / manifest（当前已含大量 sed，可再拆出「包名替换」与「manifest 注册」小节）；
  - `02-build-bundle.sh`：只产 bundle；
  - 新增 `03-package-asset.sh`：只打包进 APK assets。
- 把硬编码的 `versionCode 999`（`01-modify-termux.sh` 第 40 行附近）抽到单一来源 `version.ini`。

---

## 三、日志与错误体系（保留，并接协议）

- 现有 `dsh-web.log` 已是统一落盘点；把 `contract.md` 的分级 `[LOG]`/`[WARN]`/`[ERR]`
  与 `DSH_STATE:` 接入，Java 侧 `tailLog` 据此着色与解析。
- 错误码统一写入 `contract.md`，不另起炉灶。
- Java 侧只做：转发 stdout 日志到 App 日志面板 + 按前缀着色，**不做日志内容解析逻辑**（除 `DSH_STATE:` 与 token 正则）。

---

## 四、构建规范化 + 最小 CI（保留，并接 `version.ini`）

1. **版本单一来源** `version.ini`（仓库根）：存版本号 / dsh 版本 / node 版本；
   - 构建脚本读取并写入 assets 配置；
   - Gradle 侧读取设 `versionCode` / `versionName`，替换硬编码 `999`。
2. `scripts/README.md` 写清构建前置依赖 + `scripts/check-build-env.sh` 一键自检。
3. 轻量 CI（GitHub Actions）两步：打包 assets → 编 APK；配置放 `.github/workflows/`。
4. 构建产物 hash 校验，防 assets 损坏。
5. 本地：Linux / macOS 直跑 `scripts/`；Windows 用 WSL（保持 shell 纯粹，不引 PowerShell 移植）。

---

## 五、文档 / 注释（保留，低成本）

- `docs/`：`contract.md`（已建）、`arch.md`（架构简图 + 修改指南，例：升级 DSH 改哪里）、
  `build.md`（编译步骤 + 踩坑）、`troubleshooting.md`（启动失败清单）。
- 注释：Java 类补简短 Javadoc；脚本头部写用途 / 输入输出。
- `scripts/lint-sh.sh`（shellcheck）提交前自检。

---

## 六、SAF（暂缓，见 `contract.md` §8）

按现状不可行，需先建 Java ↔ 运行时文件 IPC 代理，暂缓落地。

---

## 七、要避免的改动（保留）

- 不新增 Kotlin / Compose / 大第三方 Android 库；
- 不把 shell 业务逻辑迁移到 Java 层；
- 不把 `android-java` 目录合并回仓库根目录。

---

## 八、与原草案差异

1. 重写架构基线，删除「Java 只做系统交互 / shell 巨型脚本」错误前提；
2. 第二节从「拆运行时脚本」改为「拆宿主机构建脚本」；
3. 删 SAF 的「轻量可选」定位，改为暂缓；
4. 其余（契约、日志、版本单一来源、CI、文档）保留，并指明与 `contract.md` / 真实代码的接驳点。
