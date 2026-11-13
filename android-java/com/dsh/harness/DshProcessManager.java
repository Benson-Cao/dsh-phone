package com.dsh.harness;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * dsh 后台服务的守护：启 / 停 / 重启 / 端口探测 / 日志 tail。
 *
 * 启动用 setsid + nohup 让服务脱离 app 进程独立存活。
 */
public final class DshProcessManager {

    private static final String TAG = "DshProcessManager";
    // 单一常量源：端口只定义在 DshBootstrap.DSH_PORT，这里转发避免两处漂移
    public static final int DSH_PORT = DshBootstrap.DSH_PORT;
    private static final String LOG_NAME = "dsh-web.log";

    /**
     * DSH_STATE: 状态行观察者。运行时（node / launcher / patch）把结构化状态写进
     * dsh-web.log（与现有重定向一致），本类增量扫描该文件并回调此观察者。
     * 观察者由 UI 层（MainActivity）设置，必须用 runOnUiThread 切回主线程再改 UI。
     */
    public interface StateObserver {
        /** 解析成功的 DSH_STATE 对象（绝不为 null）。 */
        void onState(JSONObject state);
        /** 普通日志行；level: 0=info, 1=warn, 2=err。 */
        void onLog(String line, int level);
    }

    private static volatile StateObserver observer = null;
    private static volatile boolean scanning = false;

    public static void setObserver(StateObserver o) { observer = o; }

    /** 停止增量扫描（进程停止时调用）。 */
    public static void stopStateScan() { scanning = false; }

    /**
     * Java 侧直接投递一条状态给观察者（**不经过日志文件**）。
     *
     * <p>用于 bootstrap 阶段的 {@code precheck} / {@code extracting}：
     * 这两个状态由 Java 自己执行 {@link DshBootstrap} 时产生，日志只进 Logcat、
     * 不进 {@code dsh-web.log}，所以不能走 {@code DSH_STATE:} 行扫描
     * （见 contract.md §6.1「Java 自有bootstrap 状态不走 DSH_STATE」）。
     *
     * <p>此方法是进程内回调，与文件扫描那条路径共用同一个 {@link StateObserver}，
     * 因此 UI 层无需为两套来源写两套处理逻辑。
     *
     * <p><b>线程安全</b>：内部统一切到主线程再回调，因此调用方可以在任意线程
     * 调用（bootstrap 在后台线程、restart 在保活线程，都不需要自己post）。
     *
     * @param state    状态枚举，见 contract.md §3
     * @param msg      人类可读提示
     * @param progress 0~100，可为 -1 表示不带该字段
     */
    public static void emitState(final String state, final String msg, final int progress) {
        try {
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.post(new Runnable() {
                @Override public void run() {
                    try {
                        JSONObject o = new JSONObject();
                        o.put("state", state);
                        o.put("msg", msg);
                        if (progress >= 0) o.put("progress", progress);
                        StateObserver ob = observer;
                        if (ob != null) ob.onState(o);
                    } catch (JSONException ignored) {
                        // 状态上报失败绝不能影响启动主流程
                    }
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "emitState 失败: " + e.getMessage());
        }
    }

    private DshProcessManager() {}

    /**
     * 拉起 dsh 服务，**只负责启动，不等待就绪**。
     *
     *就绪判定统一交给 {@link com.dsh.harness.DshProbe#tryReady} 的轮询 ——
     * 端口打开 ≠ 服务可用（实测：端口已开但 frontend-static 还没注册 fallback，
     * 此时任何路径都是 404 空响应体）。启动与等待拆开也让启动更快。
     */
    /**
     * 拉起 dsh 服务（布尔版，向后兼容 {@link DshService} 等调用方）。
     * 只负责启动，不等待就绪；失败返回 false（异常被吞，日志可见）。
     */
    public static boolean start(Context ctx) {
        try {
            running = launch(ctx);
            return running != null;
        } catch (Exception e) {
            Log.e(TAG, "启动 dsh 失败", e);
            return false;
        }
    }

    /**
     * r152（官方对齐）：以 {@link RuntimeProvider} 接口形式拉起 dsh 引擎进程。
     * 直接返回进程句柄（调用方负责保活 / 销毁），失败抛异常由上层兜底。
     */
    public static Process launch(Context ctx) throws Exception {
        File launcher = new File(DshBootstrap.prefix(ctx), "bin/dsh-web.sh");
        if (!launcher.exists()) {
            throw new IllegalStateException("启动脚本不存在，请先跑 DshBootstrap.setupIfNeeded()");
        }
        // 日志目录必须先建好，否则重定向失败、什么都看不到
        File homeDir = DshBootstrap.home(ctx);
        homeDir.mkdirs();
        final File logFile = new File(homeDir, LOG_NAME);
        File node = new File(DshBootstrap.prefix(ctx), "bin/node");
        Log.i(TAG, "启动 dsh: node=" + node.exists() + " script=" + launcher.exists()
            + " home=" + homeDir.exists());

        // 不用 setsid/nohup（bundle 里没有 coreutils）。
        // 关键：不能用 "cmd &" + waitFor —— Runtime.exec 的子进程会随调用方结束被回收。
        // 改成直接 ProcessBuilder 启动 bash 脚本本体，进程脱离调用栈继续活着。
        String bash = new File(DshBootstrap.prefix(ctx), "bin/bash").getAbsolutePath();
        String[] env = ShellRunner.buildEnv(ctx);
        ProcessBuilder pb = new ProcessBuilder(bash, launcher.getAbsolutePath());
        pb.directory(homeDir);
        pb.environment().clear();
        for (String kv : env) {
            int i = kv.indexOf('=');
            if (i > 0) pb.environment().put(kv.substring(0, i), kv.substring(i + 1));
        }
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
        pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile));
        final long scanStart = logFile.exists() ? logFile.length() : 0;
        running = pb.start();
        Log.i(TAG, "bash 进程已启动");
        startStateScan(ctx, scanStart);   // 增量扫描 dsh-web.log，解析 DSH_STATE: 行回调 UI
        return running;
    }

    private static Process running = null;

    public static void stop(Context ctx) {
        // bundle 里没有 pkill/ps，直接 kill 我们持有的进程引用
        if (running != null) {
            try { running.destroy(); } catch (Exception ignored) { }
            running = null;
        }
        stopStateScan();
        // r174：闸门必须跟着清零，否则 stop 之后的 restart 会被自己拦成 ALREADY_STARTING
        resetEngineGate();
    }

    public static boolean restart(Context ctx) {
        // r73（A2）：上报 retrying —— 「服务异常，正在重试」是契约 §3 的既有枚举值，
        // 但之前从没有生产者（见 docs/真机验证清单 §10）。
        // ⚠️ emitState 内部已统一切主线程，这里直接调用即可。
        emitState("retrying", "服务异常，正在重启…", -1);
        stop(ctx);
        try { Thread.sleep(500); } catch (InterruptedException ignored) { }
        return start(ctx);
    }

    /**
     * 读取 dsh web 打印出来的鉴权 URL。
     *
     * 服务启动后 stdout 会有一行：
     *   dsh web: http://127.0.0.1:3081/?token=xxxxxxxxxxxx
     * 裸的根路径会返回 401（"dsh web authentication required"），
     * 所以 WebView 必须加载这个带 token 的 URL —— 它会 303 下发了 dsh-auth cookie 再跳回 /。
     * token 每次启动都不同，所以每次都要从日志里重新抓最后一次出现的那条。
     */
    public static String readTokenUrl(Context ctx) {
        File log = new File(DshBootstrap.home(ctx), LOG_NAME);
        if (!log.exists() || log.length() == 0) return null;
        try {
            String text = new String(IoUtil.readAllBytes(log), "UTF-8");
            // ⚠️ 日志是子进程追加写的，我们可能在它写到一半时读取。
            // 如果末尾没有换行符，说明最后一行可能不完整 —— 必须丢掉，
            // 否则会拿到"半截 token"，长度对不上 → 鉴权永远失败。
            int end = text.length();
            if (end > 0 && text.charAt(end - 1) != '\n') {
                int nl = text.lastIndexOf('\n', end - 2);
                end = (nl < 0) ? 0 : nl + 1;
            }
            if (end == 0) return null;

            String[] lines = text.substring(0, end).split("\n", -1);
            java.util.regex.Pattern pat = java.util.regex.Pattern.compile(
                "http://127\\.0\\.0\\.1:(\\d+)/\\?token=([A-Za-z0-9_\\-]+)");
            String last = null;
            for (String line : lines) {
                java.util.regex.Matcher m = pat.matcher(line);
                while (m.find()) last = "http://127.0.0.1:" + m.group(1) + "/?token=" + m.group(2);
            }
            return last;
        } catch (Exception e) {
            Log.w(TAG, "读取 token URL 失败: " + e.getMessage());
            return null;
        }
    }

    public static boolean isPortOpen(int port) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    // ==================================================================
    // 启动闸门（r174）：冷启动只允许一条路径真正 spawn 引擎
    // ==================================================================

    /**
     * 引擎启动的串行闸门。
     *
     * <p><b>要解决什么</b>：MainActivity 与 DshService 冷启时都会走
     * 「端口没开 → bootstrap → startEngine」。而 {@link #isPortOpen} 回答的是
     * 「**此刻**开没开」，node 从 spawn 到 listen 要十几秒 —— 这十几秒里两条
     * 路径都判定"没开"，于是各拉起一个 node；后到的那只在 listen 时被
     * {@code EADDRINUSE} 顶掉并退出。
     *
     * <p>副产物比"多起一个进程"更糟：死实例的日志会混进 dsh-web.log，而
     * {@link #readTokenUrl} 取的是**最后一条** token（为的是重启后拿到新 token），
     * 于是应用拿着死实例的 token 去换 cookie，永远 401 —— 首页卡在
     * 「服务还没就绪」，点重试也不行。
     *
     * <p><b>闸门规则</b>：同一时刻只允许一条路径执行 bootstrap + startEngine；
     * 已经 spawn 过的，在 {@link #SPAWN_GRACE_MS} 内其他路径只等不拉，避免
     * "刚 spawn 完、端口还没开"被误判成需要再拉一次。引擎被 stop/restart 时
     * 闸门清零。
     */
    private static final Object ENGINE_GATE = new Object();
    /** spawn 之后的等待宽限期：覆盖 node 冷启动最坏情况，超过才允许再拉。 */
    private static final long SPAWN_GRACE_MS = 120_000L;
    private static long spawnedAt = 0L;
    private static boolean spawnInFlight = false;
    private static volatile String lastStartError = null;

    /** {@link #ensureEngineStarted} 的结果。 */
    public enum StartOutcome {
        /** 端口已在监听，复用了已有进程 */
        REUSED,
        /** 本次真正 spawn 了一个引擎进程 */
        STARTED,
        /** 另一条路径正在启动，本次没有重复拉起 */
        ALREADY_STARTING,
        /** 失败：解包报错或 spawn 抛异常，原因见 {@link #getLastStartError()} */
        FAILED
    }

    /** 最近一次启动失败的原因（成功时为 null）。 */
    public static String getLastStartError() {
        return lastStartError;
    }

    /**
     * 幂等、线程安全地保证引擎在跑。
     *
     * <p>Activity 与 Service 都可以直接调用；谁先到谁负责拉起，后来的那个
     * 拿到 {@link StartOutcome#ALREADY_STARTING} 并自己进入等待。
     */
    public static StartOutcome ensureEngineStarted(Context ctx) {
        synchronized (ENGINE_GATE) {
            if (isPortOpen(DSH_PORT)) return StartOutcome.REUSED;
            final long now = System.currentTimeMillis();
            if (spawnInFlight || (spawnedAt > 0 && now - spawnedAt < SPAWN_GRACE_MS)) {
                // 有人正在拉、或刚拉完还没监听：不重复拉起，让调用方去等端口
                return StartOutcome.ALREADY_STARTING;
            }
            spawnInFlight = true;
            lastStartError = null;
            try {
                String err;
                try {
                    DshRuntimeSelector.get(ctx).bootstrap(ctx, null);
                    err = DshBootstrap.getLastError();
                } catch (Throwable e) {
                    Log.e(TAG, "运行底座解包异常", e);
                    err = "运行底座解包异常: " + e.getMessage();
                }
                if (err != null) {
                    lastStartError = err;
                    return StartOutcome.FAILED;
                }
                try {
                    DshRuntimeSelector.get(ctx).startEngine(ctx, null);
                } catch (Throwable e) {
                    Log.e(TAG, "拉起 dsh 失败", e);
                    lastStartError = "拉起 dsh 失败: " + e.getMessage();
                    return StartOutcome.FAILED;
                }
                spawnedAt = System.currentTimeMillis();
                return StartOutcome.STARTED;
            } finally {
                spawnInFlight = false;
            }
        }
    }

    /** 引擎被停掉后清空闸门，让下一次 ensureEngineStarted 能重新拉起。 */
    private static void resetEngineGate() {
        synchronized (ENGINE_GATE) {
            spawnedAt = 0L;
            spawnInFlight = false;
        }
    }

    /** 读取服务日志末尾 n 行。 */
    public static String tailLog(Context ctx, int n) {
        File log = new File(DshBootstrap.home(ctx), LOG_NAME);
        if (!log.exists() || log.length() == 0) {
            // 日志文件都没生成 → 说明 bash 根本没跑起来。给出可自查的环境信息。
            StringBuilder sb = new StringBuilder("(日志文件未生成，bash 可能未执行)\n\n");
            File home = DshBootstrap.home(ctx);
            File bash = new File(DshBootstrap.prefix(ctx), "bin/bash");
            File node = new File(DshBootstrap.prefix(ctx), "bin/node");
            File script = new File(DshBootstrap.prefix(ctx), "bin/dsh-web.sh");
            File lib = new File(DshBootstrap.prefix(ctx), "lib/libc++_shared.so");
            File icu = new File(DshBootstrap.prefix(ctx), "lib/libicuuc.so.78");
            sb.append("HOME 目录: ").append(home.exists() ? "存在" : "缺失").append('\n');
            sb.append("bash:      ").append(bash.exists() ? "有 " + bash.length() + " 字节" : "缺失").append('\n');
            sb.append("node:      ").append(node.exists() ? "有 " + node.length() + " 字节" : "缺失").append('\n');
            sb.append("启动脚本:  ").append(script.exists() ? "存在" : "缺失").append('\n');
            sb.append("libc++:    ").append(lib.exists() ? "有" : "缺失").append('\n');
            sb.append("libicuuc:  ").append(icu.exists() ? "有" : "缺失").append('\n');
            return sb.toString();
        }
        try {
            List<String> lines = IoUtil.readAllLines(log);
            int from = Math.max(0, lines.size() - n);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < lines.size(); i++) sb.append(lines.get(i)).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return "(读取日志失败: " + e.getMessage() + ")";
        }
    }

    /**
     * 增量扫描 dsh-web.log，解析 DSH_STATE: 行并回调 {@link StateObserver}。
     *
     * 设计要点（与 contract.md §6.1 一致）：
     * - 不改动 ProcessBuilder 重定向（仍 appendTo dsh-web.log），只新增一个 tail 式读取；
     * - 从 startOffset（本次启动前日志文件长度）开始扫描，跳过上一轮残留；
     *   按字节偏移增量读取，正确处理"写到一半的行"；
     * - 守护线程，随进程退出；用 scanning 标志去重，避免重复启动。
     *
     * @param ctx 上下文（用于定位 HOME 日志目录）
     * @param startOffset 扫描起始字节偏移，一般传启动前日志长度
     */
    public static void startStateScan(final Context ctx, final long startOffset) {
        if (scanning) return;
        synchronized (DshProcessManager.class) {
            if (scanning) return;
            scanning = true;
        }
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                File log = new File(DshBootstrap.home(ctx), LOG_NAME);
                long pos = startOffset;
                // 防御：若启动前日志不存在却传了 >0 偏移，回退到从 0 开始
                if (pos > 0 && !log.exists()) pos = 0;
                StringBuilder leftover = new StringBuilder();
                while (scanning) {
                    try {
                        if (!log.exists()) { Thread.sleep(250); continue; }
                        long len = log.length();
                        if (len < pos) pos = 0;   // 日志被截断/重建
                        if (len > pos) {
                            FileInputStream fis = new FileInputStream(log);
                            try {
                                fis.skip(pos);
                                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                                byte[] buf = new byte[8192];
                                int n;
                                while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
                                pos += bos.size();
                                String chunk = leftover.append(
                                    new String(bos.toByteArray(), "UTF-8")).toString();
                                leftover.setLength(0);
                                int lineStart = 0;
                                for (int i = 0; i < chunk.length(); i++) {
                                    if (chunk.charAt(i) == '\n') {
                                        processLine(chunk.substring(lineStart, i));
                                        lineStart = i + 1;
                                    }
                                }
                                if (lineStart < chunk.length()) {
                                    leftover.append(chunk.substring(lineStart));
                                }
                            } finally {
                                fis.close();
                            }
                        }
                        Thread.sleep(200);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        try { Thread.sleep(500); } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        }, "dsh-state-scan");
        t.setDaemon(true);
        t.start();
    }

    /**
     * node 启动成功后会打印一行带 token 的 web URL：
     *   dsh web: http://127.0.0.1:3081/?token=xxxxxxxxxxxx
     * 生产者（运行时）目前没显式发 DSH_STATE ready，这里把它规范化为 ready 事件，
     * 让启动页状态文案能即时更新（contract.md §3）。注意：
     * - ready 只更新状态文本，绝不触发 WebView 加载 —— WebView 仍由
     *   DshProbe.tryReady 的「根路径真返回 200」把关，避免"token 已打印但服务未就绪"假就绪；
     * - 真正加载 WebView 用的 token URL 仍由 readTokenUrl() 提供，不依赖此规范化。
     * - [^\s]+ 在空白处截断，正好剥掉行尾可能的 \r（CRLF）。
     */
    private static final java.util.regex.Pattern TOKEN_URL_PAT =
            java.util.regex.Pattern.compile("http://127\\.0\\.0\\.1:\\d+/\\?token=[^\\s]+");

    /** 处理单行：token URL → 规范化 ready；DSH_STATE: → 解析回调；其余按前缀分级回调。 */
    private static void processLine(String line) {
        // (3)(4) 生产者侧：node 打印的 token URL 规范化成 ready 事件。
        // 必须在 DSH_STATE 分支之前判定，否则会被当普通日志。
        java.util.regex.Matcher tm = TOKEN_URL_PAT.matcher(line);
        if (tm.find()) {
            String webUrl = tm.group();
            try {
                JSONObject obj = new JSONObject();
                obj.put("state", "ready");
                obj.put("msg", "DSH 服务已启动");
                obj.put("progress", 100);
                JSONObject data = new JSONObject();
                data.put("web_url", webUrl);
                obj.put("data", data);
                StateObserver o = observer;
                if (o != null) o.onState(obj);
            } catch (JSONException ignored) { }
            return;
        }

        if (line.startsWith("DSH_STATE:")) {
            try {
                JSONObject obj = new JSONObject(line.substring("DSH_STATE:".length()));
                StateObserver o = observer;
                if (o != null) o.onState(obj);
            } catch (JSONException je) {
                StateObserver o = observer;
                if (o != null) o.onLog("Invalid state msg: " + line, 2);
            }
        } else {
            int level = 0;
            if (line.startsWith("[ERR]")) level = 2;
            else if (line.startsWith("[WARN]")) level = 1;
            StateObserver o = observer;
            if (o != null) o.onLog(line, level);
        }
    }
}
