package com.dsh.harness;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * dsh Web 服务的“体检”工具。
 *
 * 为什么需要它：手机无法用 adb，logcat 看不到。WebView 只给一句
 * net::ERR_HTTP_RESPONSE_CODE_FAILURE，无法区分是 401 / 404 / 400 / 500。
 * 这里用 Java 侧自己走一遍 HTTP 握手，把每一步的**原始状态码、响应头、响应体**
 * 抓出来显示在界面上 —— 这是唯一能拿到真凭实据的渠道。
 *
 * 顺带解决一个隐患：dsh 的鉴权是「访问 /?token=xxx → 303 + Set-Cookie → 跳回 /」
 * 的跳转链。完全依赖 WebView 自己处理 303/Set-Cookie/SameSite 行为有风险，
 * 所以这里 Java 侧把 Set-Cookie 拿到手，显式塞进 CookieManager，
 * 之后直接加载 / —— 行为完全可控。
 */
public final class DshProbe {

    private static final String TAG = "DshProbe";
    private static final int TIMEOUT_MS = 12000;

    private DshProbe() {}

    /** 一次握手的结果。 */
    public static final class Result {
        /** Java 侧是否已成功拿到 200 的页面（拿到就可以直接进 WebView）。 */
        public boolean rootOk;
        /** cookie 键值对（"dsh-auth-xxx=yyy"），已剥掉属性。 */
        public String cookiePair;
        /** 给用户看的完整报告。 */
        public String report = "";
    }

    private static String baseUrl() {
        return "http://127.0.0.1:" + DshProcessManager.DSH_PORT;
    }

    /** 一行的响应摘要：状态码 + Content-Type + Location + Set-Cookie + 响应体前 N 字符。 */
    private static String describe(String label, String url, String cookiePair) {
        HttpURLConnection c = null;
        StringBuilder sb = new StringBuilder();
        sb.append(label).append('\n').append("  ").append(url).append('\n');
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml");
            if (cookiePair != null) c.setRequestProperty("Cookie", cookiePair);

            int code = c.getResponseCode();
            sb.append("  状态码: ").append(code).append(' ').append(nullSafe(c.getResponseMessage())).append('\n');
            sb.append("  Content-Type: ").append(nullSafe(c.getHeaderField("Content-Type"))).append('\n');
            String loc = c.getHeaderField("Location");
            if (loc != null) sb.append("  Location: ").append(loc).append('\n');
            String sc = c.getHeaderField("Set-Cookie");
            if (sc != null) sb.append("  Set-Cookie: ").append(trim(sc, 160)).append('\n');

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

    /** 从 Set-Cookie 里剥出 "name=value"（去掉 Path/Expires/SameSite 等属性）。 */
    private static String cookiePairOf(String setCookie) {
        if (setCookie == null) return null;
        int semi = setCookie.indexOf(';');
        String pair = (semi < 0 ? setCookie : setCookie.substring(0, semi)).trim();
        return pair.isEmpty() ? null : pair;
    }

    /** 检查前端 dist 是否真的落在手机上 —— 缺了它，/ 会返回 404 空响应体。 */
    private static String checkDist(Context ctx) {
        File dist = new File(DshBootstrap.home(ctx),
                "node_modules/@deepseek-ai/dsh-web-frontend/dist");
        StringBuilder sb = new StringBuilder();
        sb.append("【前端 dist 落盘检查】\n");
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
        // 顺便看一眼 node_modules 里究竟有没有这 240 个 @deepseek-ai 包
        File dsp = new File(DshBootstrap.home(ctx), "node_modules/@deepseek-ai");
        String[] pkgs = dsp.isDirectory() ? dsp.list() : null;
        sb.append("  @deepseek-ai 包数: ").append(pkgs == null ? 0 : pkgs.length).append('\n');
        return sb.toString();
    }

    /**
     * 走一遍完整握手：
     *   1. GET tokenUrl（不跟随跳转）→ 期望 303 + Set-Cookie
     *   2. GET /（不带 cookie）    → 期望 401
     *   3. GET /（带 cookie）      → 期望 200 text/html
     * 只有第 3 步拿到 200 才会让 WebView 上场。
     */
    public static Result run(Context ctx, String tokenUrl) {
        Result r = new Result();
        StringBuilder sb = new StringBuilder();

        sb.append("=== dsh 握手体检 ===\n\n");
        sb.append("target: ").append(baseUrl()).append('\n');
        sb.append("token URL: ").append(tokenUrl == null ? "(未抓到)" : trim(tokenUrl, 120)).append("\n\n");

        String setCookie = null;
        if (tokenUrl != null) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(tokenUrl).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(TIMEOUT_MS);
                c.setReadTimeout(TIMEOUT_MS);
                int code = c.getResponseCode();
                setCookie = c.getHeaderField("Set-Cookie");
                sb.append("(1) 带 token 的请求 → ").append(code)
                  .append("  Location=").append(nullSafe(c.getHeaderField("Location")))
                  .append("  ").append(setCookie == null ? "Set-Cookie=(无)" : "Set-Cookie=" + trim(setCookie, 60)).append("\n\n");
            } catch (Throwable e) {
                sb.append("(1) 带 token 的请求异常: ").append(e).append("\n\n");
            } finally {
                if (c != null) c.disconnect();
            }
        }
        r.cookiePair = cookiePairOf(setCookie);

        sb.append(describe("(2) 不带 cookie 访问 /", baseUrl() + "/", null)).append('\n');
        String rootReport = describe("(3) 带 cookie 访问 /", baseUrl() + "/", r.cookiePair);
        sb.append(rootReport).append('\n');

        r.rootOk = rootReport.contains("状态码: 200");

        sb.append(checkDist(ctx)).append('\n');

        sb.append("【设备与解包状态】\n");
        long free = DshBootstrap.usableBytes(ctx);
        sb.append("  可用空间: ").append(free < 0 ? "未知" : (free / 1048576) + " MB").append('\n');
        String note = DshBootstrap.getRepairNote();
        sb.append("  上次解包: ").append(note == null ? "正常（未触发自动修复）" : note).append("\n\n");

        sb.append("【dsh 服务日志末 25 行】\n");
        String log = DshProcessManager.tailLog(ctx, 25);
        sb.append(log.isEmpty() ? "  (无日志)\n" : log).append('\n');

        r.report = sb.toString();
        Log.i(TAG, "体检完成 rootOk=" + r.rootOk + " cookie=" + (r.cookiePair != null));
        return r;
    }

    private static String nullSafe(String s) { return s == null ? "-" : s; }

    private static String trim(String s, int max) {
        if (s == null) return "-";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
