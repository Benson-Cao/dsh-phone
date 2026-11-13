package com.dsh.harness;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * 极简 WebDAV 客户端（只实现同步必需的四个动作）。
 *
 * ## 为什么自己写客户端（两层原因）
 *
 * 1. 不引第三方库：这个 bundle 是**刻意极简**的（项目里反复强调「没有 coreutils、
 *    没有 pkill/ps」）。为了同步功能引入 okhttp / sardine 之类，会让 bundle 体积和
 *    依赖树重新膨胀，而实际只需要 PUT / GET / MKCOL / PROPFIND 四个动词。
 * 2. **不能用 HttpURLConnection**：Android 与桌面 JDK 的实现都对 method 有白名单
 *    （OPTIONS/GET/HEAD/POST/PUT/DELETE/TRACE/PATCH），PROPFIND/MKCOL 会被
 *    **客户端本地**直接抛异常（v30.29 真机实锤：fnOS「连接并测试」报
 *    {@code Expected one of [OPTIONS, GET, ...] but was PROPFIND}，服务器根本没收到请求）。
 *    所以传输层换成自写的裸 Socket 客户端 {@link DshHttp}（任意 method、零依赖）。
 *
 * ## 目标端
 *
 * 飞牛 NAS（fnOS）的 WebDAV 服务，默认端口 HTTP 5005 / HTTPS 5006。
 * 开启方式：飞牛后台 → 系统设置 → 文件服务 → WebDAV → 启用。
 * ⚠️ 团队文件夹还要单独勾「允许通过文件共享协议挂载到其他设备」，否则连上了也看不到目录
 * （这是 fnOS 特有的安全机制，不是本类的bug）。
 *
 * ## 安全
 *
 * - 只支持 {@code http://}（局域网）+ {@code https://}；明文仅限局域网使用。
 * - Basic 认证走 HTTPS 时是加密的；局域网 HTTP 下等同明文密码，
 *   所以**不建议把 WebDAV 端口暴露到公网**。
 * - 凭证存在SharedPreferences（见 {@link DshSync}），不写日志。
 */
final class DshWebDavClient implements DshSync.NasTransport {

    private static final String TAG = "DshWebDavClient";

    private final String baseUrl;      // 形如 http://192.168.1.10:5005
    private final String user;
    private final String pass;

    /** 最近一次失败的是人类可读原因（v30.6：设置页「连接并测试」直接展示，不再吞成一句"失败"）。 */
    private String lastError = "";

    @Override
    public String lastError() { return lastError; }

    DshWebDavClient(String baseUrl, String user, String pass) {
        // 去掉末尾斜杠，避免拼出 // 导致部分服务端路径解析异常
        String b = baseUrl == null ? "" : baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.baseUrl = b;
        this.user = user == null ? "" : user;
        this.pass = pass == null ? "" : pass;
    }

    boolean isConfigured() {
        return baseUrl.length() > 0
                && (baseUrl.startsWith("http://") || baseUrl.startsWith("https://"));
    }

    /** 把相对路径安全地编码成 URL path 段（保留 / 作分隔）。 */
    private static String encodePath(String relPath) {
        String[] parts = relPath.split("/");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.length() == 0) continue;
            if (sb.length() > 0) sb.append('/');
            try {
                // URLEncoder 会把空格变 +；WebDAV 路径里应该是 %20
                sb.append(URLEncoder.encode(p, "UTF-8").replace("+", "%20"));
            } catch (Exception e) {
                sb.append(p);
            }
        }
        return sb.toString();
    }

    /** 拼完整 URL。构造函数已剥掉 baseUrl 末尾斜杠，而 urlPath 通常不以 / 开头
     *  （如 "dsh-phone/storages"）——直接拼接会拼成 "http://host:5005dsh-phone/..."，
     *  这是个会让所有请求 404 的隐藏 bug（v30.28 已修）。此处补斜杠，
     *  同时兼容调用方已传 "/" 开头（如 ping 的 "/"）或 baseUrl 已带 "/" 的情况。 */
    private URL urlFor(String urlPath) throws IOException {
        String sep = (baseUrl.endsWith("/") || urlPath.startsWith("/")) ? "" : "/";
        return new URL(baseUrl + sep + urlPath);
    }

    private String basicAuth() {
        // android.util.Base64：标准 JRE 的 java.util.Base64 要 API 26+，本项目 minSdk=21
        String raw = user + ":" + pass;
        return "Basic " + android.util.Base64.encodeToString(
                raw.getBytes(java.nio.charset.Charset.forName("UTF-8")),
                android.util.Base64.NO_WRAP);
    }

    /** PROPFIND 必须带 Depth 头（WebDAV 语义）；通过 DshHttp 的附加头参数下发。 */
    private DshHttp.Resp requestWithDepth(String method, String encodedPath, String depth,
                                          byte[] body) throws IOException {
        return DshHttp.request(method, urlFor(encodedPath), basicAuth(), null, body,
                DshHttp.CONNECT_TIMEOUT_MS, DshHttp.READ_TIMEOUT_MS, "Depth", depth);
    }

    /** 创建目录（已存在时服务端返回 405，视为成功）。 */
    @Override
    public boolean mkdirs(String relPath) {
        String acc = "";
        String[] parts = relPath.split("/");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].length() == 0) continue;
            acc += "/" + parts[i];
            try {
                DshHttp.Resp r = DshHttp.request("MKCOL", urlFor(encodePath(acc)), basicAuth(),
                        null, (byte[]) null, DshHttp.CONNECT_TIMEOUT_MS, DshHttp.READ_TIMEOUT_MS);
                // 201 Created / 405 Method Not Allowed（已存在）都算成功
                if (r.code != 201 && r.code != 405 && r.code != 301 && r.code != 409) {
                    Log.w(TAG, "MKCOL " + acc + " -> " + r.code);
                }
            } catch (IOException e) {
                Log.w(TAG, "MKCOL 失败 " + acc + ": " + e.getMessage());
                lastError = "创建目录失败：" + e.getMessage();
                return false;
            }
        }
        return true;
    }

    /**
     * 上传本地文件。
     *
     * @return true 表示服务端已接受（201/204/200）
     */
    @Override
    public boolean put(String relPath, File local) {
        if (!local.isFile()) return false;
        try {
            DshHttp.Resp r = DshHttp.request("PUT", urlFor(encodePath(relPath)), basicAuth(),
                    "application/octet-stream", local,
                    DshHttp.CONNECT_TIMEOUT_MS, DshHttp.READ_TIMEOUT_MS);
            return r.code == 201 || r.code == 204 || r.code == 200;
        } catch (IOException e) {
            Log.w(TAG, "PUT 失败 " + relPath + ": " + e.getMessage());
            lastError = "上传失败：" + e.getMessage();
            return false;
        }
    }

    /** 下载远端文件到本地；本地已存在则覆盖。 */
    @Override
    public boolean get(String relPath, File dest) {
        try {
            DshHttp.Resp r = DshHttp.request("GET", urlFor(encodePath(relPath)), basicAuth(),
                    null, (byte[]) null, DshHttp.CONNECT_TIMEOUT_MS, DshHttp.READ_TIMEOUT_MS);
            if (r.code != 200) {
                lastError = "下载失败（HTTP " + r.code + "）";
                return false;
            }
            File parent = dest.getParentFile();
            if (parent != null) parent.mkdirs();
            OutputStream out = new FileOutputStream(dest);
            try {
                out.write(r.body);
                out.flush();
            } finally {
                try { out.close(); } catch (IOException ignored) { }
            }
            return true;
        } catch (IOException e) {
            Log.w(TAG, "GET 失败 " + relPath + ": " + e.getMessage());
            lastError = "下载失败：" + e.getMessage();
            return false;
        }
    }

    /**
     * 用 PROPFIND 列出远端某个目录下的条目名（只取文件名，不含路径）。
     *
     * <p>实现取舍：服务端返回 XML，但我们**不引 XML 解析库去正确处理命名空间**
     * （WebDAV 的 {@code D:href} 在各家服务端前缀不一——{@code <D:href>}、
     * {@code <d:href>} 甚至 {@code <href>} 都有）。改为宽松的文本提取：先把 href
     * 标签归一化成 {@code <D:href>} 再抽取。够用、零依赖。拿不到就返回空集合
     * （调用方按"远端为空"处理，不会误删本地）。
     *
     * @return 文件名集合；失败返回空集合
     */
    @Override
    public java.util.Set<String> list(String relPath) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        try {
            DshHttp.Resp r = requestWithDepth("PROPFIND", encodePath(relPath), "1",
                    ("<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                            + "<D:propfind xmlns:D=\"DAV:\"><D:prop><D:href/></D:prop></D:propfind>")
                            .getBytes("UTF-8"));
            if (r.code != 207 && r.code != 200) {
                Log.w(TAG, "PROPFIND " + relPath + " -> " + r.code);
                return names;
            }
            String xml = new String(r.body, "UTF-8");
            // 归一化 href 标签：<d:href>、<href>、</d:href> 等 → <D:href>、</D:href>
            xml = xml.replaceAll("(?i)<(/?)(?:[A-Za-z0-9]+:)?href>", "<$1D:href>");
            for (String m : between(xml, "<D:href>", "</D:href>")) {
                String href = m;
                int q = href.indexOf('?');
                if (q >= 0) href = href.substring(0, q);      // 去掉查询串
                // 去掉协议主机头，剩下的就是相对路径
                int p = href.indexOf("//");
                if (p >= 0) {
                    int slash = href.indexOf('/', p + 2);
                    href = slash >= 0 ? href.substring(slash) : "/";
                }
                href = trimSlash(decodeMaybe(href));
                if (href.length() == 0) continue;
                String[] parts = href.split("/");
                String last = parts[parts.length - 1];
                if (last.length() == 0 || last.equals("..")) continue;
                // 只取直接子级：路径深度必须正好比 relPath 多一层
                String parent = trimSlash(decodeMaybe(encodePath(relPath)));
                if (!parent.isEmpty() && !href.startsWith(parent + "/")) continue;
                if (href.substring(parent.length() + 1).contains("/")) continue;
                names.add(last);
            }
        } catch (Exception e) {
            Log.w(TAG, "PROPFIND 异常 " + relPath + ": " + e.getMessage());
        }
        return names;
    }

    /**
     * 取远端文件的最后修改时间（毫秒）。
     *
     * <p>实现：用 {@code HEAD} 而不是 PROPFIND —— HEAD 响应头里的
     * {@code Last-Modified} 由服务端直接给出，比解析 PROPFIND XML 稳得多，
     * 且省一次完整响应体。
     *
     * @return 毫秒时间戳；取不到返回 0（调用方须把 0 视为"未知"，据此放弃覆盖）
     */
    @Override
    public long lastModified(String relPath) {
        try {
            DshHttp.Resp r = requestWithDepth("HEAD", encodePath(relPath), "0", null);
            if (r.code != 200 && r.code != 204) return 0L;
            // 有些服务端（尤其自建/精简实现）不回Last-Modified，改用 Date 兜底意义不大，
            // 直接返回 0让调用方跳过 —— 宁可不同步，也不冒险用错误时间覆盖用户数据。
            String lm = r.header("Last-Modified");
            if (lm == null) return 0L;
            java.text.SimpleDateFormat[] fmts = {
                new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US),
                new java.text.SimpleDateFormat("EEEE, dd-MMM-yy HH:mm:ss zzz", Locale.US),
            };
            for (java.text.SimpleDateFormat f : fmts) {
                try {
                    return f.parse(lm).getTime();
                } catch (java.text.ParseException ignored) { }
            }
            return 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 连通性探测（PROPFIND Depth:0）。用于设置页「测试连接」。 */
    @Override
    public boolean ping() {
        lastError = "";
        try {
            DshHttp.Resp r = requestWithDepth("PROPFIND", "/", "0",
                            ("<?xml version=\"1.0\"?><D:propfind xmlns:D=\"DAV:\">"
                                    + "<D:prop><D:resourcetype/></D:prop></D:propfind>").getBytes("UTF-8"));
            // 207 Multi-Status 是 WebDAV 正常应答；200 兜底。
            // ⚠️ 401 不再当成功：那是"服务器可达但凭证不对"，测试页必须如实报错。
            if (r.code == 207 || r.code == 200) return true;
            if (r.code == 401) {
                lastError = "服务器可达，但账号或密码不对（HTTP 401）";
            } else if (r.code == 403) {
                lastError = "账号或密码不对，或该账号无 WebDAV 权限（HTTP 403）";
            } else if (r.code == 404) {
                lastError = "服务端没有 WebDAV 根路径（HTTP 404），确认 fnOS 已开启 WebDAV 服务";
            } else {
                lastError = "服务器响应异常（HTTP " + r.code + "）";
            }
            Log.w(TAG, "ping -> " + r.code);
            return false;
        } catch (javax.net.ssl.SSLHandshakeException e) {
            lastError = "HTTPS 证书校验失败：" + e.getMessage();
            return false;
        } catch (java.net.SocketTimeoutException e) {
            lastError = "连接超时——确认手机与 NAS 在同一网络，且 IP/端口正确";
            return false;
        } catch (java.net.ConnectException e) {
            lastError = "无法建立连接——确认 NAS IP 正确、对应服务已开启";
            return false;
        } catch (IOException e) {
            lastError = "网络请求失败：" + e.getMessage();
            Log.w(TAG, "ping 失败: " + e.getMessage());
            return false;
        } catch (Exception e) {
            lastError = "连接失败：" + e.getClass().getSimpleName();
            Log.w(TAG, "ping 异常: " + e.getMessage());
            return false;
        }
    }

    /** 释放底层连接（裸 Socket 每请求自管连接，这里为空实现，仅满足接口契约）。 */
    @Override
    public void close() { }

    // ---- 小工具 ----
    private static String trimSlash(String s) {
        while (s.startsWith("/")) s = s.substring(1);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String decodeMaybe(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /** 取出所有 <tag>...</tag> 之间的内容（宽松文本提取，不做 XML 解析）。 */
    private static java.util.List<String> between(String text, String open, String close) {
        java.util.List<String> out = new java.util.ArrayList<>();
        int i = 0;
        while (true) {
            int a = text.indexOf(open, i);
            if (a < 0) break;
            a += open.length();
            int b = text.indexOf(close, a);
            if (b < 0) break;
            out.add(text.substring(a, b));
            i = b + close.length();
        }
        return out;
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) try { c.close(); } catch (IOException ignored) { }
    }
}
