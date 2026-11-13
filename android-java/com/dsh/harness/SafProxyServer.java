package com.dsh.harness;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 方案 A 的本地代理：把 SAF 的读写能力暴露成 {@code 127.0.0.1} 上的一个小 HTTP 服务，
 * 供 node 侧的 {@code saf-fs-shim.js} 调用。
 *
 * ## 为什么是 HTTP 而不是 Unix domain socket
 *
 * {@code docs/saf-feasibility.md} §2 把方案 B（UDS）列为"更干净但要先验证"：
 * Termux 版 node 在 Android 下能否 {@code net.connect(unixPath)} 本仓库没人验过。
 * HTTP 的可用性是已被验证过的（{@code DshProbe} 天天在探 3081），
 * 且 {@code http} 模块在 node 里零依赖可用。先选能跑通的那条。
 *
 * ## 四道闸（缺一不可）
 *
 * <ol>
 *   <li><b>只绑回环</b>：{@code ServerSocket} 显式绑 127.0.0.1，端口取 0（内核分配空闲端口，
 *       避免与 dsh 的 3081 或别的本地服务抢）。</li>
 *   <li><b>来源再验一次</b>：accept 后核对对端是回环地址 —— 绑定不等于外部一定连不进来
 *       （部分 ROM 的 iptables 行为不可信）。</li>
 *   <li><b>token 鉴权</b>：每次请求校验 {@code x-dsh-token}。挂载点下是用户数据，
 *       同机任何进程都能连回环端口，没有 token 等于把文件柜敞开。</li>
 *   <li><b>路径必须在挂载点内</b>：相对路径由 node 侧算好后传来，这里再拒一次
 *       {@code ..}（纵深防御，别信调用方）。</li>
 * </ol>
 *
 * ## 生命周期
 *
 * 随 {@code DshService} 起（{@link #start}），{@link #stop} 时关闭。
 * 授权目录变更<b>不需要</b>重启服务 —— 每次请求都现读 {@link SafConfig}。
 */
public final class SafProxyServer {

    private static final String TAG = "SafProxyServer";
    private static final Object LOCK = new Object();

    /** 单文件读取上限（32MB）。SAF 侧是整文件取回内存，太大不划算。 */
    private static final long MAX_READ = 32L * 1024 * 1024;

    private static volatile SafProxyServer inst = null;

    private final Context ctx;
    private final String token;
    private ServerSocket sock;
    private int port = 0;
    private Thread acceptThread;
    private volatile boolean running = false;

    private SafProxyServer(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.token = newToken();
    }

    // ===================== 生命周期 =====================

    public static boolean isRunning() {
        SafProxyServer s = inst;
        return s != null && s.running && s.port > 0;
    }

    public static int port() {
        SafProxyServer s = inst;
        return (s == null) ? 0 : s.port;
    }

    public static String token() {
        SafProxyServer s = inst;
        return (s == null) ? "" : s.token;
    }

    public static String url() {
        SafProxyServer s = inst;
        if (s == null || s.port <= 0) return "";
        return "http://127.0.0.1:" + s.port;
    }

    /**
     * 启动代理（幂等）。已配置过存储目录才真正起；未配置时只刷新 node 侧配置
     * （把 {@code enabled:false} 写清楚，免得 shim 拿着过期 token 撞墙）。
     */
    public static void start(Context ctx) {
        synchronized (LOCK) {
            SafConfig.ensureMountDir(ctx);
            if (isRunning()) {
                SafConfig.writeRuntimeConfig(ctx);
                return;
            }
            if (!SafConfig.isConfigured(ctx)) {
                Log.i(TAG, "未配置存储目录，代理不起");
                SafConfig.writeRuntimeConfig(ctx);
                return;
            }
            try {
                SafProxyServer s = new SafProxyServer(ctx);
                s.begin();
                inst = s;
                Log.i(TAG, "SAF 代理已启动: " + url());
            } catch (Throwable t) {
                Log.e(TAG, "SAF 代理启动失败", t);
                inst = null;
            }
            SafConfig.writeRuntimeConfig(ctx);
        }
    }

    /** 重新挑一次配置（用户换目录后调用）。当前实现无需重启，仅写配置。 */
    public static void refresh(Context ctx) {
        synchronized (LOCK) {
            SafConfig.writeRuntimeConfig(ctx);
        }
    }

    public static void stop() {
        synchronized (LOCK) {
            SafProxyServer s = inst;
            inst = null;
            if (s != null) s.end();
        }
    }

    private void begin() throws Exception {
        sock = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        port = sock.getLocalPort();
        running = true;
        acceptThread = new Thread(new Runnable() {
            @Override public void run() {
                acceptLoop();
            }
        }, "dsh-saf-proxy");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void end() {
        running = false;
        if (sock != null) try { sock.close(); } catch (Throwable ignored) { }
        sock = null;
        port = 0;
        if (acceptThread != null) try { acceptThread.interrupt(); } catch (Throwable ignored) { }
        acceptThread = null;
    }

    private void acceptLoop() {
        while (running) {
            Socket s = null;
            try {
                s = sock.accept();
                if (!isLoopback(s)) {
                    Log.w(TAG, "拒绝非回环连接: " + s.getInetAddress());
                    s.close();
                    continue;
                }
                final Socket conn = s;
                s = null;
                Thread t = new Thread(new Runnable() {
                    @Override public void run() { handle(conn); }
                }, "dsh-saf-conn");
                t.setDaemon(true);
                t.start();
            } catch (Throwable t) {
                if (s != null) try { s.close(); } catch (Throwable ignored) { }
                if (!running) return;
                try { Thread.sleep(200); } catch (InterruptedException ie) { return; }
            }
        }
    }

    private static boolean isLoopback(Socket s) {
        InetAddress a = s.getInetAddress();
        return a != null && a.isLoopbackAddress();
    }

    private static String newToken() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            int v = b[i] & 0xFF;
            sb.append(v < 16 ? "0" : "").append(Integer.toHexString(v));
        }
        return sb.toString();
    }

    // ===================== 请求处理 =====================

    private void handle(Socket s) {
        try {
            s.setSoTimeout(30000);
            BufferedInputStream in = new BufferedInputStream(s.getInputStream());
            String line = readLine(in);
            if (line == null || line.length() == 0) return;
            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String path = parts[1];

            Map<String, String> headers = new HashMap<String, String>();
            while ((line = readLine(in)) != null && line.length() > 0) {
                int i = line.indexOf(':');
                if (i > 0) {
                    headers.put(line.substring(0, i).trim().toLowerCase(),
                        line.substring(i + 1).trim());
                }
            }
            int len = 0;
            try {
                String cl = headers.get("content-length");
                if (cl != null) len = Integer.parseInt(cl.trim());
            } catch (Throwable ignored) { }
            byte[] body = (len > 0) ? readFully(in, len) : new byte[0];

            // 鉴权放在最前：任何未带正确 token 的请求都拿不到路径信息
            String got = headers.get("x-dsh-token");
            if (got == null || !constantTimeEquals(got, token)) {
                respond(s, 401, "application/json", "{\"error\":\"EAUTH\"}");
                return;
            }
            dispatch(s, method, path, body);
        } catch (Throwable t) {
            Log.w(TAG, "连接处理异常: " + t.getMessage());
        } finally {
            try { s.close(); } catch (Throwable ignored) { }
        }
    }

    private void dispatch(Socket s, String method, String path, byte[] body) {
        try {
            if ("/ping".equals(path)) {
                respond(s, 200, "application/json", "{\"ok\":true}");
                return;
            }
            if (!"POST".equals(method)) {
                respond(s, 405, "application/json", "{\"error\":\"EMETHOD\"}");
                return;
            }
            JSONObject req;
            try {
                req = new JSONObject(new String(body, "UTF-8"));
            } catch (Throwable t) {
                respond(s, 400, "application/json", "{\"error\":\"EBADJSON\"}");
                return;
            }
            String rel = safeRel(req.optString("path", ""));

            if ("/stat".equals(path)) {
                SafTree.Stat st = SafTree.stat(ctx, rel);
                JSONObject o = new JSONObject();
                o.put("exists", st.exists);
                o.put("isDir", st.isDir);
                o.put("size", st.size);
                o.put("mtimeMs", st.mtimeMs);
                o.put("name", st.name == null ? "" : st.name);
                respond(s, 200, "application/json", o.toString());
                return;
            }
            if ("/list".equals(path)) {
                List<SafTree.Entry> es = SafTree.list(ctx, rel);
                JSONArray arr = new JSONArray();
                for (SafTree.Entry e : es) {
                    JSONObject o = new JSONObject();
                    o.put("name", e.name);
                    o.put("isDir", e.isDir);
                    o.put("size", e.size);
                    o.put("mtimeMs", e.mtimeMs);
                    arr.put(o);
                }
                JSONObject o = new JSONObject();
                o.put("entries", arr);
                respond(s, 200, "application/json", o.toString());
                return;
            }
            if ("/read".equals(path)) {
                byte[] data = SafTree.read(ctx, rel);
                if (data == null) {
                    respond(s, 404, "application/json", "{\"error\":\"ENOENT\"}");
                    return;
                }
                if (data.length > MAX_READ) {
                    respond(s, 413, "application/json", "{\"error\":\"ETOOBIG\"}");
                    return;
                }
                respondBytes(s, 200, "application/octet-stream", data);
                return;
            }
            if ("/write".equals(path)) {
                byte[] data = decodeBase64(req.optString("data", ""));
                boolean ok = SafTree.write(ctx, rel, data);
                respond(s, ok ? 200 : 500, "application/json",
                    ok ? "{\"ok\":true}" : "{\"error\":\"EIO\"}");
                return;
            }
            if ("/mkdir".equals(path)) {
                boolean ok = SafTree.mkdirs(ctx, rel);
                respond(s, ok ? 200 : 500, "application/json",
                    ok ? "{\"ok\":true}" : "{\"error\":\"EIO\"}");
                return;
            }
            if ("/delete".equals(path)) {
                // 不存在也算成功：node 侧 rm(force) 的语义就是不报错
                SafTree.delete(ctx, rel);
                respond(s, 200, "application/json", "{\"ok\":true}");
                return;
            }
            if ("/rename".equals(path)) {
                String to = safeRel(req.optString("to", ""));
                boolean ok = SafTree.rename(ctx, rel, to);
                respond(s, ok ? 200 : 500, "application/json",
                    ok ? "{\"ok\":true}" : "{\"error\":\"EXDEV\"}");
                return;
            }
            respond(s, 404, "application/json", "{\"error\":\"ENOTFOUND\"}");
        } catch (Throwable t) {
            Log.w(TAG, "处理 " + path + " 失败: " + t.getMessage());
            try {
                respond(s, 500, "application/json", "{\"error\":\"EIO\"}");
            } catch (Throwable ignored) { }
        }
    }

    /**
     * 纵深防御：node 侧已经算过一次相对路径，这里再挡 {@code ..} 与绝对路径。
     */
    private static String safeRel(String rel) {
        if (rel == null) return "";
        String s = rel.replace('\\', '/');
        if (s.startsWith("/")) s = s.substring(1);
        StringBuilder sb = new StringBuilder();
        for (String seg : s.split("/")) {
            if (seg.length() == 0 || seg.equals(".") || seg.equals("..")) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(seg);
        }
        return sb.toString();
    }

    // ===================== HTTP 小工具 =====================

    private static void respond(Socket s, int code, String type, String body) throws Exception {
        respondBytes(s, code, type, body.getBytes("UTF-8"));
    }

    private static void respondBytes(Socket s, int code, String type, byte[] body)
            throws Exception {
        OutputStream out = s.getOutputStream();
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(code).append(' ')
         .append(code == 200 ? "OK" : "Error").append("\r\n");
        h.append("Content-Type: ").append(type).append("\r\n");
        h.append("Content-Length: ").append(body.length).append("\r\n");
        h.append("Cache-Control: no-store\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes("UTF-8"));
        out.write(body);
        out.flush();
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') bos.write(c);
        }
        if (c < 0 && bos.size() == 0) return null;
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static byte[] readFully(InputStream in, int len) throws Exception {
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) break;
            off += n;
        }
        return (off == len) ? buf : java.util.Arrays.copyOf(buf, off);
    }

    /** 定长比较，避免 token 被逐字节计时猜出来（本地服务，聊胜于无）。 */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }

    private static byte[] decodeBase64(String s) {
        try {
            return android.util.Base64.decode(s, android.util.Base64.DEFAULT);
        } catch (Throwable t) {
            return new byte[0];
        }
    }
}
