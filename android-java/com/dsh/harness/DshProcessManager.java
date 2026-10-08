package com.dsh.harness;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

/**
 * dsh 后台服务的守护：启 / 停 / 重启 / 端口探测 / 日志 tail。
 *
 * 启动用 setsid + nohup 让服务脱离 app 进程独立存活。
 */
public final class DshProcessManager {

    private static final String TAG = "DshProcessManager";
    public static final int DSH_PORT = 3080;
    private static final String LOG_NAME = "dsh-web.log";

    private DshProcessManager() {}

    /**
     * 拉起 dsh 服务，**只负责启动，不等待就绪**。
     *
     *就绪判定统一交给 {@link com.dsh.harness.DshProbe#tryReady} 的轮询 ——
     * 端口打开 ≠ 服务可用（实测：端口已开但 frontend-static 还没注册 fallback，
     * 此时任何路径都是 404 空响应体）。启动与等待拆开也让启动更快。
     */
    public static boolean start(Context ctx) {
        File launcher = new File(DshBootstrap.prefix(ctx), "bin/dsh-web.sh");
        if (!launcher.exists()) {
            Log.e(TAG, "启动脚本不存在，请先跑 DshBootstrap.setupIfNeeded()");
            return false;
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
        try {
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
            running = pb.start();
            Log.i(TAG, "bash 进程已启动");
        } catch (Exception e) {
            Log.e(TAG, "启动 bash 失败", e);
            return false;
        }
        return true;
    }

    private static Process running = null;

    public static void stop(Context ctx) {
        // bundle 里没有 pkill/ps，直接 kill 我们持有的进程引用
        if (running != null) {
            try { running.destroy(); } catch (Exception ignored) { }
            running = null;
        }
    }

    public static boolean restart(Context ctx) {
        stop(ctx);
        try { Thread.sleep(500); } catch (InterruptedException ignored) { }
        return start(ctx);
    }

    /**
     * 读取 dsh web 打印出来的鉴权 URL。
     *
     * 服务启动后 stdout 会有一行：
     *   dsh web: http://127.0.0.1:3080/?token=xxxxxxxxxxxx
     * 裸的根路径会返回 401（"dsh web authentication required"），
     * 所以 WebView 必须加载这个带 token 的 URL —— 它会 303 下发了 dsh-auth cookie 再跳回 /。
     * token 每次启动都不同，所以每次都要从日志里重新抓最后一次出现的那条。
     */
    public static String readTokenUrl(Context ctx) {
        File log = new File(DshBootstrap.home(ctx), LOG_NAME);
        if (!log.exists() || log.length() == 0) return null;
        try {
            String text = new String(java.nio.file.Files.readAllBytes(log.toPath()), "UTF-8");
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
            List<String> lines = java.nio.file.Files.readAllLines(log.toPath());
            int from = Math.max(0, lines.size() - n);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < lines.size(); i++) sb.append(lines.get(i)).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return "(读取日志失败: " + e.getMessage() + ")";
        }
    }
}
