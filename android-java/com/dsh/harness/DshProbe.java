package com.dsh.harness;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * dsh Web 服务的就绪探测与失败诊断。
 *
 * ## 为什么要「等就绪」而不是「拿到 token 就进」
 * dsh 启动时往 stdout 打印带 token 的 URL，但**打印 token 不代表服务就绪**。
 * `dsh-web-app` 里是：
 * <pre>
 *   const settled = connectionCtx.get("loader")?.await();
 *   if (settled === void 0) announceReady();     // ← loader 不可见时立即打印
 * </pre>
 * 也就是说 token 可能在其余插件还没激活完时就打出来了。此时
 * `frontend-static` 尚未注册 fallback 座位，webserver 会对**所有**路径
 * 直接回 **404 + 空响应体** —— 这正是真机上"首次启动失败、重试就好"的成因
 * （重试时服务早已完全就绪）。
 *
 * 打印 token 这个信号**本身不可信**，所以只能以「根路径真的返回 200」为准。
 *
 * ## 分工
 * {@link #tryReady} —— 极简探测（1~2 个请求），返回 null 表示还没就绪，调用方继续等；
 * {@link #diagnose} —— 只在超时后才跑，产出给用户看的完整证据报告。
 */
public final class DshProbe {

    private static final String TAG = "DshProbe";
    private static final int TIMEOUT_MS = 8000;

    private DshProbe() {}

    /** 就绪结果：cookie 键值对（可能为 null，表示服务直接放行）。 */
    public static final class Ready {
        public final String cookiePair;
        Ready(String cookiePair) { this.cookiePair = cookiePair; }
    }

    private static String baseUrl() {
        return "http://127.0.0.1:" + DshProcessManager.DSH_PORT;
    }

    private static HttpURLConnection open(String url, String cookiePair) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setRequestProperty("Accept", "text/html,application/xhtml+html,*/*");
        if (cookiePair != null) c.setRequestProperty("Cookie", cookiePair);
        return c;
    }

    /** 从 Set-Cookie 里剥出"name=value"。 */
    private static String cookiePairOf(String setCookie) {
        if (setCookie == null) return null;
        int semi = setCookie.indexOf(';');
        String pair = (semi < 0 ? setCookie : setCookie.substring(0, semi)).trim();
        return pair.isEmpty() ? null : pair;
    }

    /**
     * 快速就绪探测。
     * @return 就绪时返回 cookie；未就绪返回 null（调用方应继续等待）。
     */
    public static Ready tryReady(Context ctx) {
        String tokenUrl = DshProcessManager.readTokenUrl(ctx);
        if (tokenUrl == null) return null;              // 服务还没打印 token
        HttpURLConnection c = null;
        try {
            c = open(tokenUrl, null);
            int code = c.getResponseCode();
            String cookie = cookiePairOf(c.getHeaderField("Set-Cookie"));
            c.disconnect();
            c = null;

            if (cookie == null) {
                // 没拿到 cookie：可能是 401（token 还没被接受）或 404（fallback 未注册），
                // 都视为"还没就绪"。
                return (code == 200) ? new Ready(null) : null;
            }
            HttpURLConnection c2 = null;
            try {
                c2 = open(baseUrl() + "/", cookie);
                if (c2.getResponseCode() == 200) return new Ready(cookie);
                return null;
            } finally {
                if (c2 != null) c2.disconnect();
            }
        } catch (Exception e) {
            // 连接被拒 / 超时 —— 服务还没起来
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 一行的响应摘要：状态码 + Content-Type + Location + Set-Cookie + 响应体前 N 字符。 */
    private static String describe(String label, String url, String cookiePair) {
        HttpURLConnection c = null;
        StringBuilder sb = new StringBuilder();
        sb.append(label).append('\n').append("  ").append(url).append('\n');
        try {
            c = open(url, cookiePair);
            int code = c.getResponseCode();
            sb.append("  状态码: ").append(code).append(' ')
              .append(nullSafe(c.getResponseMessage())).append('\n');
            sb.append("  Content-Type: ").append(nullSafe(c.getHeaderField("Content-Type"))).append('\n');
            String loc = c.getHeaderField("Location");
            if (loc != null) sb.append("  Location: ").append(loc).append('\n');
            String sc = c.getHeaderField("Set-Cookie");
            if (sc != null) sb.append("  Set-Cookie: ").append(trim(sc, 140)).append('\n');

            InputStream in = (code >= 400) ? c.getErrorStream() : c.getInputStream();
            if (in == null) {
                sb.append("  响应体: (空)\n");
            } else {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n, total = 0;
                while ((n = in.read(buf)) > 0 && total < 8192) {
                    bos.write(buf, 0, Math.min(n, 8192 - total));
                    total += n;
                }
                in.close();
                String body = new String(bos.toByteArray(), "UTF-8");
                sb.append("  响应体长度: ").append(total).append(total >= 8192 ? "+" : "").append('\n');
                if (body.trim().isEmpty()) {
                    sb.append("  响应体: (空 —— 浏览器遇到 4xx/5xx + 空响应体就会报\n"
                            + "           net::ERR_HTTP_RESPONSE_CODE_FAILURE)\n");
                } else {
                    sb.append("  响应体前 300 字:\n    ")
                      .append(trim(body.replace("\n", " ").replace("\r", " "), 300)).append('\n');
                }
            }
        } catch (Throwable e) {
            sb.append("  异常: ").append(e.getClass().getSimpleName())
              .append(": ").append(nullSafe(e.getMessage())).append('\n');
        } finally {
            if (c != null) c.disconnect();
        }
        return sb.toString();
    }

    /** 检查前端 dist 是否真的落在手机上 —— 缺了它，/ 会返回 404 空响应体。 */
    private static String checkDist(Context ctx) {
        File dist = new File(DshBootstrap.home(ctx),
                "node_modules/@deepseek-ai/dsh-web-frontend/dist");
        StringBuilder sb = new StringBuilder();
        sb.append("【前端dist 落盘检查】\n");
        sb.append("  目录: ").append(dist.getAbsolutePath()).append('\n');
        if (!dist.isDirectory()) {
            sb.append("  ✗ 目录不存在！\n");
            return sb.toString();
        }
        File index = new File(dist, "index.html");
        sb.append("  index.html: ")
          .append(index.isFile() ? "存在 " + index.length() + " 字节" : "✗ 缺失").append('\n');
        File assets = new File(dist, "assets");
        if (assets.isDirectory()) {
            String[] names = assets.list();
            sb.append("  assets/ 顶层条目: ").append(names == null ? 0 : names.length)
              .append("（含 fonts/、langs/ 两个子目录）\n");
            if (names != null) {
                for (int i = 0; i < names.length && i < 6; i++) {
                    sb.append("    - ").append(names[i]).append('\n');
                }
            }
        } else {
            sb.append("  ✗ assets/ 缺失\n");
        }
        sb.append(DshBootstrap.describeIntegrity(ctx));
        return sb.toString();
    }

    /**
     * 超时后才跑的完整诊断：把原始证据打在屏幕上（没有 adb 时唯一渠道）。
     *
     * @param elapsedMs 已经等待的时长
     */
    public static String diagnose(Context ctx, long elapsedMs) {
        String tokenUrl = DshProcessManager.readTokenUrl(ctx);
        StringBuilder sb = new StringBuilder();

        sb.append("=== dsh 握手体检 ===\n\n");
        sb.append("target: ").append(baseUrl()).append('\n');
        sb.append("已等待: ").append(elapsedMs / 1000).append(" 秒\n");
        sb.append("token URL: ").append(tokenUrl == null ? "(未抓到 —— 服务可能没启动失败)"
                : trim(tokenUrl, 120)).append("\n\n");

        String cookie = null;
        if (tokenUrl != null) {
            HttpURLConnection c = null;
            try {
                c = open(tokenUrl, null);
                int code = c.getResponseCode();
                String sc = c.getHeaderField("Set-Cookie");
                cookie = cookiePairOf(sc);
                sb.append("(1) 带 token 的请求 → ").append(code)
                  .append("  Location=").append(nullSafe(c.getHeaderField("Location")))
                  .append("  ").append(sc == null ? "Set-Cookie=(无)"
                        : "Set-Cookie=" + trim(sc, 50)).append("\n\n");
            } catch (Throwable e) {
                sb.append("(1) 带 token 的请求异常: ").append(e).append("\n\n");
            } finally {
                if (c != null) c.disconnect();
            }
        }

        sb.append(describe("(2) 不带 cookie 访问 /", baseUrl() + "/", null)).append('\n');
        sb.append(describe("(3) 带 cookie 访问 /", baseUrl() + "/", cookie)).append('\n');
        sb.append(checkDist(ctx)).append('\n');

        sb.append("【设备与解包状态】\n");
        long free = DshBootstrap.usableBytes(ctx);
        sb.append("  可用空间: ").append(free < 0 ? "未知" : (free / 1048576) + " MB").append('\n');
        String note = DshBootstrap.getRepairNote();
        sb.append("  上次解包: ").append(note == null ? "正常（未触发自动修复）" : note).append("\n\n");

        sb.append("【dsh 服务日志末 25 行】\n");
        String log = DshProcessManager.tailLog(ctx, 25);
        sb.append(log.isEmpty() ? "  (无日志)\n" : log).append('\n');
        Log.i(TAG, "诊断报告已生成，等待 " + elapsedMs + "ms");
        return sb.toString();
    }

    private static String nullSafe(String s) { return s == null ? "-" : s; }

    private static String trim(String s, int max) {
        if (s == null) return "-";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}