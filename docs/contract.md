# dsh-phone Java ↔ Shell 标准化状态输出协议（贴合现状版）

> 目标：统一 dsh 启动/运行阶段 **Java** 与 **运行时（shell + node）** 之间的状态上报，
> 让 App 能结构化展示进度与错误，而 **Java 不承载业务判断**。
>
> 本版基于 `dsh-harness-android` 真实代码修订（对照 `DshBootstrap` / `DshProcessManager` /
> `DshService` 及 `scripts/`），修正了原草案与现状不符的多处问题。关键差异见文末 §9。

---

## 0. 现状基线（修订前提，必须先看）

原草案把现状描述成「Java 薄壳 + shell 巨型启动脚本」，与真实代码不符：

1. **启动编排主要在 Java**：
   - `DshBootstrap`：解包 assets bundle、完整性抽查、软链/ICU/koffi 修复、生成 launcher；
   - `DshProcessManager`：进程启停 / 重启 / 端口探测 / token URL 抓取；
   - `DshService`：前台保活服务（跨 UID 进程组存活，冷启 8s → 亚秒级）。
2. **运行时脚本极小且由 Java 生成**：实际只有 `DshBootstrap.createLauncher` 生成的
   `bin/dsh-web.sh`（~20 行 env 导出 + `exec node ...`）和 `patch.sh`。
   **不存在原草案想拆分的「运行时巨型脚本」**。
3. **宿主机构建脚本**在 `scripts/`（`00-build-apk` / `01-modify-termux` / `02-build-bundle` /
   `patch` / `push` / `sync-from-source` / `verify-sync`），这才是真正会变面条的地方。
4. **传输现实**：`DshProcessManager.start()` 把进程 stdout/stderr 重定向到
   `${HOME}/dsh-web.log`（`ProcessBuilder.Redirect.appendTo`），Java 通过 `tailLog` /
   `readTokenUrl` **读文件**获取输出，而非捕获进程 stdout 流。
5. **鉴权现实**：WebView 加载的不是裸 `web_url`，而是带 `?token=...` 的 URL，
   由 Java 从日志正则抓取（`readTokenUrl` 匹配 `http://127.0.0.1:3080/?token=...`）。

---

## 1. 核心约定

1. **协议前缀**：所有协议行以固定前缀 `DSH_STATE:` 开头。解析方仅解析带此前缀的行，
   其余原样作为普通日志。
2. **单行 JSON**：一行一个状态对象，禁止多行 JSON，便于按行解析。
3. **普通日志**：不带前缀；分级前缀 `[LOG]`（普通）/ `[WARN]`（警告）/ `[ERR]`（错误），
   Java 据此着色。
4. **编码**：UTF-8；字符串转义由封装函数处理（见 §5，纯 bash，不依赖 `jq`）。
5. **谁可以发协议行（单边上报）**：本协议是 **shell/node → Java** 的上报通道，
   **也可以由 Java 自己桥接发出**（例如把 bootstrap 解包进度也写成 `DSH_STATE:` 行，
   便于日志面板集中展示）。无论哪边发，都必须写入 **同一条被 Java 捕获的输出**。
6. **传输层（与现状一致）**：当前走文件 `dsh-web.log`。协议 **不假设 live stdout 流**；
   将来若 Java 改为直接捕获进程 stdout，只要 `DSH_STATE:` 行出现在被捕获的流里即可，**协议本身不变**。
   所有协议行与普通日志都进同一条被捕获输出（当前即 `dsh-web.log`），**不使用第二条流 / stderr**。

---

## 2. 字段规范（基础字段）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `state` | string | ✅ | 状态枚举，固定取值，见 §3 |
| `msg` | string | ✅ | 人类可读提示，App 界面展示 |
| `progress` | number | ❌ | 0~100，仅启动/进度阶段；其他状态可省略 |
| `code` | string | ❌ | 错误码，仅 `error` 状态必填；格式 `MODULE-ERR_NAME` |
| `data` | object | ❌ | 可选附加数据（如服务地址、版本），按需携带 |

---

## 3. state 枚举（固定，**已删除 `booting`**）

```
init        → 初始化开始
precheck    → 前置环境校验（空间 / 架构 / 权限）
extracting  → 解包 assets 环境包
termux_init → Termux 文件系统 / 软链 / ICU 初始化
start_node  → 启动 Node 进程
start_dsh   → 拉起 DSH 服务
ready       → DSH Web 服务就绪，可打开 WebView
retrying    → 服务异常，正在重试
error       → 不可恢复错误，启动失败
stopped     → DSH 服务正常停止
crashed     → 进程意外崩溃
```

> **状态上报方约定**：启动阶段的细粒度状态由 **实际执行方** 上报——
> 解包 / 校验（`precheck` / `extracting`）由 Java 执行 `DshBootstrap` 时上报；
> 运行时阶段（`start_dsh` → `ready`）由 node/launcher 上报。
> **不使用 `booting` 合并态**（原草案示例误用了 `booting`，本版删除）。

---

## 4. 错误码命名规范 `{模块}-{语义错误名}`

示例（与现状对应）：

- `PRECHECK-NO_SPACE`：存储空间不足（`DshBootstrap.MIN_FREE_BYTES` 校验）
- `PRECHECK-ARCH_MISMATCH`：CPU 架构不匹配
- `EXTRACT-FAIL`：解压失败（`TarGz.skippedCount > 0` 或完整性抽查未过）
- `TERMUX-INIT_FAIL`：环境初始化失败
- `DSH-PORT_BIND`：端口占用
- `DSH-START_TIMEOUT`：DSH 启动超时

---

## 5. Shell 侧封装（纯 bash，无 `jq` 依赖）

> ⚠️ 原草案 `logger.sh` 依赖 `jq` 并写「runtime 预装 jq」。但本项目的 bundle 是
> **刻意极简**的（代码注释反复强调「没有 coreutils」「没有 pkill/ps」），把 `jq`
> 列为协议硬依赖风险高。下方用纯 bash 实现，**零额外依赖**；`data` 参数须由调用方
> 保证为合法 JSON 片段。同时修正了原草案 `logger.sh` 在失败时写 `>&2`（违反「不用 stderr」）的矛盾。

```bash
#!/usr/bin/env bash
# dsh_state <state> <msg> [progress] [code] [data_json]
# 输出：DSH_STATE:{...} 到 stdout（被 Java 捕获，落 dsh-web.log）
dsh_state() {
    local state="$1" msg="$2" progress="$3" code="$4" data="$5"
    _je() {  # JSON 字符串转义：反斜杠、双引号、换行、tab
        local s="$1"
        s="${s//\\/\\\\}"; s="${s//\"/\\\"}"
        s="${s//$'\n'/\\n}"; s="${s//$'\t'/\\t}"
        printf '%s' "$s"
    }
    local json="{\"state\":\"$(_je "$state")\",\"msg\":\"$(_je "$msg")\""
    [ -n "$progress" ] && json="$json,\"progress\":$progress"
    [ -n "$code" ]     && json="$json,\"code\":\"$(_je "$code")\""
    [ -n "$data" ]     && json="$json,\"data\":$data"   # data 须为合法 JSON
    json="$json}"
    printf 'DSH_STATE:%s\n' "$json"
}

dsh_log()  { printf '[LOG] %s\n'  "$1"; }   # 普通日志（原样进日志面板）
dsh_warn() { printf '[WARN] %s\n' "$1"; }   # 警告（Java 黄色）
dsh_err()  { printf '[ERR] %s\n'  "$1"; }   # 错误（Java 红色；同样进 dsh-web.log）
```

调用示例：

```bash
dsh_state "ready" "DSH 服务已启动" "" "" '{"web_url":"http://127.0.0.1:3080/?token=xxxx"}'
dsh_state "error" "存储空间不足" "" "PRECHECK-NO_SPACE"
dsh_log  "开始执行前置检查"
dsh_warn "存储空间较低，剩余不足 100MB"
```

---

## 6. Java 侧接收逻辑（极简，不改业务判断）

Java 只做：**读行 → 前缀匹配 → 解析 → 更新 UI**；复杂业务（保活/重启）仍由 Java 现有机制负责。

1. 逐行读取 `dsh-web.log`（当前实现）或进程 stdout（未来切换），判断行是否以 `DSH_STATE:` 开头：
   - 是：截取 JSON，`org.json`（安卓自带，无额外依赖）解析，更新进度条 / 状态文本；
   - 否：按 `[ERR]`/`[WARN]`/其余 着色追加到日志面板。
2. `ready` → 取 `data.web_url`：
   - **推荐**：运行时 `ready` 时直接把带 `?token=` 的完整 URL 放进 `data.web_url`，Java 优先用它加载 WebView；
   - **向后兼容**：若 `data` 无 token，则回退到现有 `readTokenUrl()` 正则抓取（保留，不删）。
3. `error` → 展示 `msg` + `code`。
4. **Java 仍保留** `DshService` 保活 与 `DshProcessManager` 重启 / 端口探测——
   这些是有充分 Android 动机的「进程存活管理」，**不迁移到 shell**（原草案要求「重试交给 shell」是错误的，见 §9）。

解析伪代码（纯 Java）：

```java
void onReadLine(String line){
    if(line.startsWith("DSH_STATE:")){
        String jsonStr = line.substring("DSH_STATE:".length());
        try{
            JSONObject obj = new JSONObject(jsonStr);
            String state = obj.getString("state");
            String msg   = obj.getString("msg");
            updateStatusText(msg);
            if(obj.has("progress")) updateProgressBar(obj.getInt("progress"));
            if("ready".equals(state)){
                JSONObject data = obj.optJSONObject("data");
                String url = data != null ? data.optString("web_url", null) : null;
                if(url != null){
                    runOnUiThread(() -> webView.loadUrl(url));   // 优先用带 token 的 URL
                }else{
                    String tok = DshProcessManager.readTokenUrl(ctx); // 回退正则抓取
                    if(tok != null) runOnUiThread(() -> webView.loadUrl(tok));
                }
            }else if("error".equals(state)){
                showErrorDialog(msg + "\nCode:" + obj.optString("code","UNKNOWN"));
            }
        }catch(JSONException e){
            appendLog("Invalid state msg:"+line);   // 解析失败当普通日志，不崩溃
        }
    }else{
        LogLevel lv = line.startsWith("[ERR]") ? ERROR : line.startsWith("[WARN]") ? WARN : INFO;
        appendLog(line, lv);
    }
}
```

---

### 6.1 Java 集成落地方案（解决传输层决策）

原草案假设「Java 捕获进程 stdout 流」，与现状不符。落地时 **推荐如下**，无需改动已工作的 `ProcessBuilder` 重定向：

- **不改动捕获方式**：`DshProcessManager.start()` 继续把 stdout/stderr `Redirect.appendTo(dsh-web.log)`（保留 `tailLog` / `readTokenUrl` 不变）。`DSH_STATE:` 行由运行时（node / launcher / patch）写入同一文件即可被捕获。
- **新增增量扫描**：在 `DshProcessManager` 中增加 `scanStates(Consumer<JSONObject> cb)` —— 用「记住上次读取偏移」的方式 `tail -F` 式读取 `dsh-web.log`，逐行匹配 `DSH_STATE:` 后 `org.json` 解析并回调。**不轮询整文件**，只追增量，避免启动期 IO 放大（参照现有 `tailLog` 的实现思路）。
- **驱动点**：由 `DshService.ensureDshRunning`（保活线程）或 `MainActivity` 在 `tryReady` 成功后启动该扫描线程；回调在 `runOnUiThread` 中刷新进度条 / 状态文本 / 错误对话框。
- **Java 自有 bootstrap 状态（precheck / extracting）**：由 `DshBootstrap` 在执行解包时通过现有进程内回调直接更新 UI，**不**走 `DSH_STATE:`（这些日志在 Logcat，不进 `dsh-web.log`）。`DSH_STATE:` 主要用于 **运行时（node / launcher / patch）→ Java** 的上报。
- **ready / token**：`ready` 状态**只更新启动页状态文案，不在 Java 侧加载 WebView**。原因：本项目 WebView 加载必须以「根路径真返回 200」为准（`DshProbe.tryReady`），token 打印 / `ready` 信号本身不可信（frontend-static 注册 fallback 前打印 token 会导致 404 空响应体）。`ready.data.web_url` 若带 token 可预先缓存，但最终加载仍由 `tryReady` 把关；回退仍用 `readTokenUrl`。（已实现：`MainActivity.handleDshState` 对 `ready` 仅 `setStatus`，绝不 `showWebView`。）
- **token URL 行规范化**：node 启动成功会打印一行 `dsh web: http://127.0.0.1:3080/?token=...`，但运行时目前未显式发 `DSH_STATE ready`。为避免「改运行时才能看到 ready 文案」的耦合，`DshProcessManager.processLine` 在扫描日志时**主动把该行规范化为 `ready` 事件**（`state=ready`、`progress=100`、`data.web_url=<tokenURL>`），仅驱动状态文案，不触发 WebView。真正加载用的 token 仍由 `readTokenUrl()` 提供，二者互不依赖。（对应 P3/P4 落地。）
- **error**：`MainActivity` 在 WebView 未进入（`webShown == false`）时，用 `msg + code` 弹结构化诊断页；已进入则只更新文案，不覆盖正常界面。
- **观察者接线**：`MainActivity.onCreate` 调 `DshProcessManager.setObserver(...)`，`onDestroy` 清为 `null`（避免持有 Activity 引用）；`DshProcessManager.start()` 拉起进程后自动 `startStateScan`，`stop()` 时 `stopStateScan()`。
- **progress**：当前启动页无真实进度条（被品牌渐变线替代），`progress` 附到 `statusText` 文案后（如「正在启动 (35%)」），不强制改 UI 控件。
- **未来若切到捕获进程 stdout**：只需把 `startStateScan` 的扫描源从文件换成进程流，解析逻辑不变（协议 §1.6）。

这样协议层与现状零冲突，且为后续「运行时细粒度状态上报」预留了统一入口。

## 7. 协议约束 & 避坑规则

1. ❌ 禁止在 JSON 内输出换行；所有消息必须单行。
2. ❌ 不在 JSON 中放大量调试堆栈；详细堆栈放普通日志。
3. ❌ Java **禁止**根据 `state` 做业务重试/重启——但「前台保活 + 进程存活管理」属 Android 机制，保留在 Java（见 §6）。
4. ✅ 状态是 **事件推送**，非轮询；运行时主动推送，Java 被动接收。
5. ✅ 向后兼容：新增字段只允许可选字段，**不改已有字段含义**。
6. ✅ 所有协议行进同一条被捕获输出（文件或 stdout），**不用 stderr**。

---

## 8. SAF 存储桥接（诚实修订：需 IPC，**建议暂缓**）

原草案的 `SafFileOperator`（JVM 侧 `openInputStream/openOutputStream` 操作 `content://` Uri）
**在当前进程模型下不可实现**：

- dsh/node 跑在 **独立的 Linux 子进程（Termux userland）**，无法调用 JVM 的 content Uri API；
- 现有唯一通道是 **shell/node → Java 单向 stdout**，没有 Java → 运行时的文件读写 RPC；
- 因此「通过 `SafFileOperator` 桥接」缺少任何机制支撑，连「直接使用文档路径」也不行（node 打不开 `content://`）。

**可行的真实方案（需新建子系统，绝非「轻量可选」）—— 已于 2026-10-13 落地：**

1. Java 侧起本地代理（abstract unix socket 或 `127.0.0.1` HTTP），暴露 content Uri 的读 / 写 / 枚举；
2. node/dsh 的数据目录访问改为走该代理（FS 路由适配器）；
3. `request_saf` 状态保留，仅用于提示用户去设置页授权；
4. 配置落 `saf_config.json`，运行时启动时读取以决定是否走代理。

> **结论（2026-10-13 修订）**：上面的第 1、2、4 条**已实现**。
> 实现要点：走 `127.0.0.1` HTTP + token 鉴权；node 侧用 `NODE_OPTIONS=--require`
> 预加载补丁，**不改 dsh 的 npm 包**（因此升级 dsh 不需要重做）。
> 第 3 条的 `request_saf` 仍是提示语义 —— 现在它由页面「存储目录」面板承载。
> 详见 [`saf-feasibility.md`](saf-feasibility.md) §7。
> 原草案的 `SafFileOperator`（直接把 content Uri 操作接进 node）**依然不可行**，不要那样做。

```Plain Text
DSH_STATE:{"state":"request_saf","msg":"尚未配置存储目录，请前往设置选择数据存储位置","data":{"required":true}}
```

---

## 9. 与原草案差异（修订说明）

| # | 原草案 | 本版修正 |
|---|---|---|
| 1 | 假设「Java 薄壳 + shell 巨型启动脚本」 | 明确编排主要在 Java，运行时脚本极小且由 Java 生成 |
| 2 | 传输层「Java 读进程 stdout 流」 | 改为与现状一致「读 `dsh-web.log` 文件」，并说明未来可平滑切 stdout |
| 3 | `logger.sh` 依赖 `jq` | 改为纯 bash 封装，零依赖；并修正失败时写 `>&2` 的自相矛盾 |
| 4 | 示例用 `booting` 态 | 删除 `booting`，与枚举规则一致 |
| 5 | 鉴权 `web_url` 裸地址 | 保留 token 抓取，并推荐 `ready.data.web_url` 直接带 token URL |
| 6 | 「重试 / 重启交给 shell，Java 不做」 | 撤回；保活 / 重启留在 Java（有 Android 动机） |
| 7 | SAF 标为「轻量可选模块」 | 改为「需 IPC，暂缓」，不再声称轻量可选 |
