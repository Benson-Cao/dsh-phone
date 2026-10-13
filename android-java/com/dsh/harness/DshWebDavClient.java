package com.dsh.harness;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * 极简 WebDAV 客户端（只实现同步必需的四个动作）。
 *
 * ## 为什么用HttpURLConnection 而不是引第三方库
 *
 * 这个 bundle 是**刻意极简**的（项目里反复强调「没有 coreutils、没有 pkill/ps」）。
 * 为了同步功能引入 okhttp / sardine 之类，会让 bundle 体积和依赖树重新膨胀，
 * 而实际只需要 PUT / GET / MKCOL / PROPFIND 四个动词 —— 自己写不到 200 行，
 * 且没有升级冲突风险。
 *
 * ## 目标端
 *
 * 飞牛 NAS（fnOS）的 WebDAV 服务，默认端口 HTTP 5005 / HTTPS 5006。
 * 开启方式：飞牛后台 → 系统设置 → 文件服务 → WebDAV → 启用。
 * ⚠️ 团队文件夹还要单独勾「允许通过文件共享协议挂载到其他设备」，否则连上��看不到目录
 * （这是 fnOS 特有的安全机制，不是本类的bug）。
 *
 * ## 安全
 *
 * - 只支持 {@code http://}（局域网）+ {@code https://}；明文仅限局域网使用。
 * - Basic 认证走 HTTPS 时是加密的；局域网 HTTP 下等同明文密码，
 *   所以**不建议把 WebDAV 端口暴露到公网**。
 * - 凭证存在SharedPreferences（见 {@link DshSync}），不写日志。
 */
final class DshWebDavClient {

    private static final String TAG = "DshWebDavClient";

    /** 单次请求超时。局域网正常应< 1s；留足余量但不允许无限等（NAS 掉线时不能卡住同步线程）。 */
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 15000;

    private final String baseUrl;      // 形如 http://192.168.1.10:5005
    private final String user;
    private final String pass;

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

    private HttpURLConnection open(String method, String urlPath) throws IOException {
        URL u = new URL(baseUrl + urlPath);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setInstanceFollowRedirects(false);   // WebDAV 服务端重定向会丢认证头
        c.setRequestProperty("Authorization", basicAuth());
        c.setRequestProperty("User-Agent", "dsh-phone/1");
        return c;
    }

    private String basicAuth() {
        // android.util.Base64：标准 JRE 的 java.util.Base64 要 API 26+，本项目 minSdk=21
        String raw = user + ":" + pass;
        return "Basic " + android.util.Base64.encodeToString(
                raw.getBytes(java.nio.charset.Charset.forName("UTF-8")),
                android.util.Base64.NO_WRAP);
    }

    /** 创建目录（已存在时服务端返回 405，视为成功）。 */
    boolean mkdirs(String relPath) {
        String acc = "";
        String[] parts = relPath.split("/");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].length() == 0) continue;
            acc += "/" + parts[i];
            try {
                HttpURLConnection c = open("MKCOL", encodePath(acc));
                int code = c.getResponseCode();
                c.disconnect();
                // 201 Created / 405 Method Not Allowed（已存在）都算成功
                if (code != 201 && code != 405 && code != 301 && code != 409) {
                    Log.w(TAG, "MKCOL " + acc + " -> " + code);
                }
            } catch (IOException e) {
                Log.w(TAG, "MKCOL 失败 " + acc + ": " + e.getMessage());
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
    boolean put(String relPath, File local) {
        if (!local.isFile()) return false;
        HttpURLConnection c = null;
        InputStream in = null;
        OutputStream os = null;
        try {
            c = open("PUT", encodePath(relPath));
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/octet-stream");
            c.setFixedLengthStreamingMode((int) local.length());
            os = c.getOutputStream();
            in = new FileInputStream(local);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
            int code = c.getResponseCode();
            return code == 201 || code == 204 || code == 200;
        } catch (IOException e) {
            Log.w(TAG, "PUT 失败 " + relPath + ": " + e.getMessage());
            return false;
        } finally {
            closeQuietly(in);
            closeQuietly(os);
            if (c != null) c.disconnect();
        }
    }

    /** 下载远端文件到本地；本地已存在则覆盖。 */
    boolean get(String relPath, File dest) {
        HttpURLConnection c = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            c = open("GET", encodePath(relPath));
            int code = c.getResponseCode();
            if (code != 200) return false;
            in = c.getInputStream();
            File parent = dest.getParentFile();
            if (parent != null) parent.mkdirs();
            out = new java.io.FileOutputStream(dest);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            return true;
        } catch (IOException e) {
            Log.w(TAG, "GET 失败 " + relPath + ": " + e.getMessage());
            return false;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
            if (c != null) c.disconnect();
        }
    }

    /**
     * 用 PROPFIND 列出远端某个目录下的条目名（只取文件名，不含路径）。
     *
     * <p>实现取舍：服务端返回 XML，但我们**不引XML 解析库去正确处理命名空间**
     * （WebDAV 的 `D:href` 在各家服务端前缀不一）。改为宽松的文本提取：
     * 够用、零依赖。拿不到就返回空集合（调用方按"远端为空"处理，不会误删本地）。
     *
     * @return 文件名集合；失败返回空集合
     */
    java.util.Set<String> list(String relPath) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            c = open("PROPFIND", encodePath(relPath));
            c.setDoOutput(true);
            c.setRequestProperty("Depth", "1");
            c.setRequestProperty("Content-Type", "application/xml; charset=utf-8");
            // 最小 PROPFIND body：只要 href
            String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<D:propfind xmlns:D=\"DAV:\"><D:prop><D:href/></D:prop></D:propfind>";
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.flush();
            os.close();

            int code = c.getResponseCode();
            if (code != 207 && code != 200) {
                Log.w(TAG, "PROPFIND " + relPath + " -> " + code);
                return names;
            }
            in = c.getInputStream();
            String xml = readAll(in);
            String prefix = baseUrl.replace("http://", "").replace("https://", "");
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
        } finally {
            closeQuietly(in);
            if (c != null) c.disconnect();
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
    long lastModified(String relPath) {
        HttpURLConnection c = null;
        try {
            c = open("HEAD", encodePath(relPath));
            int code = c.getResponseCode();
            if (code != 200 && code != 204) return 0L;
            // 有些服务端（尤其自建/精简实现）不回Last-Modified，改用 Date 兜底意义不大，
            // 直接返回 0让调用方跳过 —— 宁可不同步，也不冒险用错误时间覆盖用户数据。
            String lm = c.getHeaderField("Last-Modified");
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
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 连通性探测（PROPFIND Depth:0）。用于设置页「测试连接」。 */
    boolean ping() {
        HttpURLConnection c = null;
        try {
            c = open("PROPFIND", "/");
            c.setDoOutput(true);
            c.setRequestProperty("Depth", "0");
            OutputStream os = c.getOutputStream();
            os.write(("<?xml version=\"1.0\"?><D:propfind xmlns:D=\"DAV:\">"
                    + "<D:prop><D:resourcetype/></D:prop></D:propfind>").getBytes("UTF-8"));
            os.flush();
            os.close();
            int code = c.getResponseCode();
            // 207 Multi-Status 是 WebDAV 正常应答；401 说明能连但凭证不对
            return code == 207 || code == 200 || code == 401;
        } catch (Exception e) {
            Log.w(TAG, "ping 失败: " + e.getMessage());
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

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

    private static String readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toString("UTF-8");
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) try { c.close(); } catch (IOException ignored) { }
    }
}