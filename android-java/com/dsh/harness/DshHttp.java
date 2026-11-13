package com.dsh.harness;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 极简 HTTP/1.1 客户端（裸 Socket，零依赖，**任意 method**）。
 *
 * ## 为什么不用 HttpURLConnection
 *
 * Android 的 HttpURLConnection 对 method 有白名单
 * （OPTIONS/GET/HEAD/POST/PUT/DELETE/TRACE/PATCH），PROPFIND / MKCOL 这类
 * WebDAV 动词**客户端本地就直接抛异常**：
 * {@code "Expected one of [OPTIONS, GET, HEAD, ...] but was PROPFIND"}
 * （v30.28 真机实锤，fnOS 连接测试失败即此因）。桌面 JDK 同样有此限制。
 * 反射绕过在现代 Android 上受 hidden-API 限制，引入 okhttp 又违背本项目
 * 「零第三方依赖」的取舍 —— 裸 Socket 写 HTTP/1.1 不到 200 行，全部可控。
 *
 * ## 范围与取舍
 *
 * - 每请求一条连接（{@code Connection: close}），不维护连接池 —— 局域网
 *   NAS 场景建连 <5ms，简单性远比复用重要。
 * - 支持 Content-Length / chunked 两种响应体；HEAD / 204 / 304 无体。
 * - HTTPS 走自签名放行的 SSLSocketFactory（与旧行为一致，仅限本客户端）。
 * - 响应体整体读入内存 —— 本客户端只承载 storages 的 JSON 文件（KB~MB 级），
 *   大文件上传走 {@link #request(String, URL, String, String, File, int, int)} 流式版本。
 * - 裸 Socket **不受** Android network_security_config 明文限制（那是
 *   平台 HTTP 栈的策略检查）；本项目 v30.6 起已对 LAN 放行明文，实际行为不变。
 */
final class DshHttp {

    /** 单次请求超时。局域网正常应 <1s；留足余量但不允许无限等。 */
    static final int CONNECT_TIMEOUT_MS = 8000;
    static final int READ_TIMEOUT_MS = 15000;

    private static final String UA = "dsh-phone/1";

    /** 响应：状态码 + 原始头区 + 响应体。 */
    static final class Resp {
        final int code;
        final String headers;
        final byte[] body;

        Resp(int code, String headers, byte[] body) {
            this.code = code;
            this.headers = headers;
            this.body = body;
        }

        /** 大小写不敏感取响应头；没有返回 null。 */
        String header(String name) {
            String[] lines = headers.split("\r\n");
            for (int i = 1; i < lines.length; i++) {   // 第 0 行是状态行
                int c = lines[i].indexOf(':');
                if (c <= 0) continue;
                if (lines[i].substring(0, c).trim().equalsIgnoreCase(name)) {
                    return lines[i].substring(c + 1).trim();
                }
            }
            return null;
        }
    }

    private static volatile SSLSocketFactory sTrustAllFactory;

    /** 自签名放行（仅本客户端；不做全局信任库改动）。 */
    private static SSLSocketFactory trustAllFactory() throws IOException {
        SSLSocketFactory f = sTrustAllFactory;
        if (f != null) return f;
        synchronized (DshHttp.class) {
            if (sTrustAllFactory == null) {
                TrustManager[] tm = new TrustManager[]{
                    new X509TrustManager() {
                        public void checkClientTrusted(java.security.cert.X509Certificate[] ch, String a) { }
                        public void checkServerTrusted(java.security.cert.X509Certificate[] ch, String a) { }
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new java.security.cert.X509Certificate[0];
                        }
                    }
                };
                try {
                    SSLContext sc = SSLContext.getInstance("TLS");
                    sc.init(null, tm, new java.security.SecureRandom());
                    sTrustAllFactory = sc.getSocketFactory();
                } catch (Exception e) {
                    throw new IOException("TLS 初始化失败: " + e.getMessage(), e);
                }
            }
            return sTrustAllFactory;
        }
    }

    /** 发送请求，body 为内存字节数组（PROPFIND/MKCOL 等小请求用）。 */
    static Resp request(String method, URL u, String basicAuth, String contentType,
                        byte[] body, int connectTimeoutMs, int readTimeoutMs) throws IOException {
        return doRequest(method, u, basicAuth, contentType, body, null,
                connectTimeoutMs, readTimeoutMs, null, null);
    }

    /** 同上，附带一个自定义请求头（WebDAV 的 Depth 等）。 */
    static Resp request(String method, URL u, String basicAuth, String contentType,
                        byte[] body, int connectTimeoutMs, int readTimeoutMs,
                        String extraHeaderName, String extraHeaderValue) throws IOException {
        return doRequest(method, u, basicAuth, contentType, body, null,
                connectTimeoutMs, readTimeoutMs, extraHeaderName, extraHeaderValue);
    }

    /** 发送请求，body 从本地文件流式读出（PUT 大文件用，不整读进内存）。 */
    static Resp request(String method, URL u, String basicAuth, String contentType,
                        File bodyFile, int connectTimeoutMs, int readTimeoutMs) throws IOException {
        return doRequest(method, u, basicAuth, contentType, null, bodyFile,
                connectTimeoutMs, readTimeoutMs, null, null);
    }

    private static Resp doRequest(String method, URL u, String basicAuth, String contentType,
                                  byte[] body, File bodyFile,
                                  int connectTimeoutMs, int readTimeoutMs,
                                  String extraHeaderName, String extraHeaderValue) throws IOException {
        boolean tls = "https".equalsIgnoreCase(u.getProtocol());
        int defPort = tls ? 443 : 80;
        int port = u.getPort() > 0 ? u.getPort() : defPort;

        Socket s = tls ? trustAllFactory().createSocket() : new Socket();
        try {
            s.connect(new InetSocketAddress(u.getHost(), port), connectTimeoutMs);
            s.setSoTimeout(readTimeoutMs);
            if (tls) ((SSLSocket) s).startHandshake();

            // 请求行：path 已由调用方 URL 编码（encodePath），直接使用
            String path = u.getFile();
            if (path == null || path.length() == 0) path = "/";
            StringBuilder head = new StringBuilder(256);
            head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            head.append("Host: ").append(u.getHost());
            if (u.getPort() > 0 && port != defPort) head.append(':').append(u.getPort());
            head.append("\r\n");
            if (basicAuth != null && basicAuth.length() > 0) {
                head.append("Authorization: ").append(basicAuth).append("\r\n");
            }
            head.append("User-Agent: ").append(UA).append("\r\n");
            if (extraHeaderName != null && extraHeaderValue != null) {
                head.append(extraHeaderName).append(": ").append(extraHeaderValue).append("\r\n");
            }
            head.append("Connection: close\r\n");
            boolean hasBody = body != null || bodyFile != null;
            if (hasBody) {
                long len = body != null ? body.length : bodyFile.length();
                if (contentType != null && contentType.length() > 0) {
                    head.append("Content-Type: ").append(contentType).append("\r\n");
                }
                head.append("Content-Length: ").append(len).append("\r\n");
            }
            head.append("\r\n");

            OutputStream os = s.getOutputStream();
            os.write(head.toString().getBytes("ISO-8859-1"));   // 头区纯 ASCII
            if (body != null) os.write(body);
            if (bodyFile != null) {
                InputStream fin = new FileInputStream(bodyFile);
                try {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = fin.read(buf)) > 0) os.write(buf, 0, n);
                } finally {
                    try { fin.close(); } catch (IOException ignored) { }
                }
            }
            os.flush();

            InputStream in = new BufferedInputStream(s.getInputStream(), 8192);

            // ---- 读头区（逐行到空行）----
            String statusLine = readLine(in);
            if (statusLine == null || !statusLine.startsWith("HTTP/")) {
                throw new IOException("非 HTTP 响应: " + statusLine);
            }
            StringBuilder hdr = new StringBuilder(512).append(statusLine).append("\r\n");
            String line;
            while ((line = readLine(in)) != null && line.length() > 0) {
                hdr.append(line).append("\r\n");
            }
            int code;
            try {
                String[] p = statusLine.split(" ");
                code = Integer.parseInt(p[1]);
            } catch (Exception e) {
                throw new IOException("状态行解析失败: " + statusLine);
            }

            // ---- 读响应体 ----
            byte[] respBody;
            boolean noBody = "HEAD".equals(method) || code == 204 || code == 304;
            String te = lower(headerOf(hdr.toString(), "Transfer-Encoding"));
            if (noBody) {
                respBody = new byte[0];
            } else if (te != null && te.contains("chunked")) {
                respBody = readChunked(in);
            } else {
                String cl = headerOf(hdr.toString(), "Content-Length");
                if (cl != null) {
                    respBody = readExact(in, Long.parseLong(cl.trim()));
                } else {
                    respBody = readUntilEof(in);   // Connection: close，读到对端关连接
                }
            }
            return new Resp(code, hdr.toString(), respBody);
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    // ---- 小工具 ----

    /** 读一行（到 \n），去掉结尾 \r；流结束返回 null。仅用于头区/chunk 尺寸行（文本）。 */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(64);
        while (true) {
            int c = in.read();
            if (c < 0) return b.size() == 0 ? null : b.toString("ISO-8859-1");
            if (c == '\n') {
                String s = b.toString("ISO-8859-1");
                return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
            }
            b.write(c);
        }
    }

    private static byte[] readExact(InputStream in, long len) throws IOException {
        if (len < 0 || len > Integer.MAX_VALUE - 8) throw new IOException("响应体长度异常: " + len);
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(len, 1 << 20));
        byte[] buf = new byte[16384];
        long left = len;
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(left, buf.length));
            if (n < 0) throw new EOFException("响应体在读取 " + (len - left) + "/" + len + " 字节后中断");
            out.write(buf, 0, n);
            left -= n;
        }
        return out.toByteArray();
    }

    private static byte[] readUntilEof(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /** chunked 解码：读每块 16 进制尺寸 → 数据 → CRLF；0 块后是 trailer（读到空行）。 */
    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
        byte[] buf = new byte[16384];
        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) throw new EOFException("chunked 响应在块尺寸行处中断");
            int semi = sizeLine.indexOf(';');
            String hex = (semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim();
            int n;
            try {
                n = Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                throw new IOException("chunked 尺寸解析失败: " + sizeLine);
            }
            if (n == 0) {
                String t;
                while ((t = readLine(in)) != null && t.length() > 0) { /* 丢弃 trailer */ }
                return out.toByteArray();
            }
            long left = n;
            while (left > 0) {
                int r = in.read(buf, 0, (int) Math.min(left, buf.length));
                if (r < 0) throw new EOFException("chunked 块数据中断");
                out.write(buf, 0, r);
                left -= r;
            }
            readLine(in);   // 块尾 CRLF
        }
    }

    /** 从原始头区文本取头值（大小写不敏感）；没有返回 null。 */
    private static String headerOf(String headers, String name) {
        String[] lines = headers.split("\r\n");
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c <= 0) continue;
            if (lines[i].substring(0, c).trim().equalsIgnoreCase(name)) {
                return lines[i].substring(c + 1).trim();
            }
        }
        return null;
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(java.util.Locale.US);
    }
}
