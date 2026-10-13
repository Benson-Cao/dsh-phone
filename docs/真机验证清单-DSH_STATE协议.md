# DSH_STATE 协议 · 真机验证清单

## 0. 背景（本次改了什么）
本包 `DSH-Phone-v999-r95-protocol-arm64-v8a-debug.apk` 在启动页接入了结构化状态协议
（设计详见 `docs/contract.md`）。本次改动的可观测点：

- 启动页状态文案新增两个细分态：`正在拉起 DSH 服务`（来自 `DSH_STATE:start_dsh`）、
  `DSH 服务已启动 (100%)`（来自 node token URL 行被规范化为 `ready`）；
- `DshProcessManager` 新增增量扫描器，解析 `dsh-web.log` 里的 `DSH_STATE:` 行并回调 UI；
- `ready` **只更新文案，不触发 WebView 加载**；WebView 仍由 `DshProbe.tryReady`
  （根路径真返回 200）把关，避免「token 已打印但服务未就绪」的假就绪。

## 1. 前置条件
- 手机已开「USB 调试」，且 `adb devices` 能看到设备。
  （此前无线 5555 曾离线，建议用有线或先 `adb connect` 重连。）
- 电脑上 APK 路径：
  `D:\Program AI\DSH Phone\apk-out\DSH-Phone-v999-r95-protocol-arm64-v8a-debug.apk`
- 旧版（r95 无协议）若已安装，本包 `versionCode` 同为 999，`adb install -r` 可覆盖。

## 2. 安装
```bash
adb install -r "D:\Program AI\DSH Phone\apk-out\DSH-Phone-v999-r95-protocol-arm64-v8a-debug.apk"
```
- 期望：`Success`。
- 若报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（包名/签名不一致）：
  `adb uninstall com.dsh.harness` 后再装。

## 3. 观察启动页状态流转（核心）
**冷启**验证最干净：设置 → 应用 → DeepSeek Harness → 清除数据（或直接卸载重装），
启动 App，盯住启动页**中间的状态文字**，期望**依次**出现：

1. `正在准备运行环境…` / `正在解包运行环境（首次约需 1–3 分钟）…`（首启解包时）
2. `正在启动 dsh 服务…`
3. **`正在拉起 DSH 服务`** ← 协议新增（对应 `DSH_STATE:start_dsh`）
4. **`DSH 服务已启动 (100%)`** ← 协议新增（token URL 行规范化成 `ready`）
5. 随后 WebView 加载，进入 dsh Web 界面（首页/工作台）

**判定**：第 3、4 步**至少各闪现一次**即通过。
⚠️ 注意：启动满 1 秒后，`DshProbe.tryReady` 轮询会每 250ms 把文案刷成
`正在启动 dsh 服务…（已用 N 秒）`，可能和协议文案交叠。若肉眼看不清，用第 4/5 步的
logcat / 文件佐证即可。

## 4. 用 logcat 确认扫描器在工作
```bash
adb logcat -c
adb shell am force-stop com.dsh.harness
adb shell am start com.dsh.harness/.MainActivity
adb logcat -s DshProcessManager MainActivity | grep -iE "bash 进程已启动|Invalid state|DSH_STATE"
```
期望：
- 出现 `bash 进程已启动` → 说明 `DshProcessManager.start()` 跑了、扫描线程已启动；
- **不**出现 `Invalid state msg` → 说明 `DSH_STATE:` 行解析正常（无格式错误）。

## 5. 确认运行时真的在 emit（看 dsh-web.log）
```bash
# 先用 run-as 找日志路径（debuggable 包才能进 data 目录）
adb shell run-as com.dsh.harness find /data/data/com.dsh.harness -name dsh-web.log
# 拿到路径后（例如 .../files/dsh-web.log）
adb shell run-as com.dsh.harness cat <上面路径> | grep "DSH_STATE"
```
期望能看到：
```
DSH_STATE:{"state":"start_dsh","msg":"正在拉起 DSH 服务"}
```
（由 `DshBootstrap.createLauncher` 在 `exec node` 之前打印，bash 尚未被 node 取代，故能进日志。）

## 6. 进 dsh Web UI，确认无回归
WebView 加载后：
- 能进入 dsh 首页/工作台，token 鉴权正常（不会卡 401 / 404 白屏）；
- 随便点几个页面/功能，App 不崩；
- 返回键层级正常（设置面板/抽屉 → 返回 → 退出 App 符合预期）。

## 7. 异常路径（可选，当前未由运行时触发）
- `error` 诊断页逻辑已实现，但**当前运行时还没 emit `DSH_STATE:error`**
  （只有收到该态才会弹结构化诊断页）。本次不要求触发，仅记录：
  若未来运行时报错，应能在未进 WebView 时看到诊断页，而非黑屏/无限转圈。
- 若启动直接失败（如 node 起不来），启动页应停在 `dsh 启动失败` 文案或诊断页。

## 8. 通过标准（打勾）
- [ ] 第 3 步出现 `正在拉起 DSH 服务` 与 `DSH 服务已启动 (100%)`；
- [ ] 第 4 步 logcat 无 `Invalid state`，且能看到 `bash 进程已启动`；
- [ ] 第 5 步 `dsh-web.log` 含 `DSH_STATE:start_dsh`；
- [ ] 第 6 步 WebView 正常进 dsh UI、无崩溃无白屏。

## 9. 失败排查
- **完全看不到协议文案** → 先做第 4/5 步，确认扫描器启动、`dsh-web.log` 已生成；
  并确认装的是 `*-protocol-*` 包（不是旧 r95）。
- **卡白屏 / 401** → 与协议无关，是 `tryReady`/token 老问题，看 `DshProbe.diagnose` 输出。
- **装不上** → 见第 2 步 `UPDATE_INCOMPATIBLE` 处理。

## 10. 已知未覆盖 / 后续
- 运行时尚未 emit `extracting` / `termux_init` / `retrying` / `error` 等态
  （目前仅 `start_dsh` 显式 + token→ready 合成）。
- SAF IPC 代理设计、`version.ini` 单一版本源等仍待做（见 `maintainability-plan.md`）。
