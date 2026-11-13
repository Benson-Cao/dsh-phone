package com.dsh.harness;

import android.util.Log;

import jcifs.CIFSContext;
import jcifs.context.SingletonContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * SMB/CIFS 传输实现（基于 jcifs-ng）。对应设计稿 S17「连接类型 · SMB」。
 *
 * 与 WebDAV/SFTP 共用 {@link DshSync} 的同步与冲突策略，只是传输层换成 SMB。
 * 连接串形如 {@code smb://host:port/share}，鉴权用 用户名/密码（域留空）。
 */
final class DshSmbClient implements DshSync.NasTransport {

    private static final String TAG = "DshSmbClient";

    private final String host;
    private final int port;
    private final String share;
    private final CIFSContext ctx;

    /** 最近一次失败的人类可读原因（v30.6：透出到「连接并测试」）。 */
    private String lastError = "";

    @Override
    public String lastError() { return lastError; }

    DshSmbClient(String host, String port, String share, String user, String pass) {
        this.host = host == null ? "" : host.trim();
        int p;
        try {
            p = (port == null || port.trim().isEmpty()) ? 445 : Integer.parseInt(port.trim());
        } catch (NumberFormatException e) {
            p = 445;
        }
        this.port = p;
        this.share = share == null ? "" : share.trim();
        String u = user == null ? "" : user.trim();
        String pw = pass == null ? "" : pass;
        // 域留空；SMB 默认用 用户名/密码 直连。SingletonContext 读取默认 jcifs 配置。
        this.ctx = SingletonContext.getInstance()
                .withCredentials(new NtlmPasswordAuthenticator("", u, pw));
    }

    private String base() {
        String h = "smb://" + host + (port == 445 ? "" : ":" + port);
        if (share.isEmpty()) return h;
        return h + "/" + (share.startsWith("/") ? share.substring(1) : share);
    }

    private String remotePath(String rel) {
        String b = base();
        return (rel == null || rel.isEmpty()) ? b : (b + "/" + rel);
    }

    @Override
    public boolean ping() {
        lastError = "";
        try {
            SmbFile root = new SmbFile(base(), ctx);
            root.listFiles();   // 触发连接 + 鉴权
            return true;
        } catch (jcifs.smb.SmbException e) {
            String m = e.getMessage() == null ? "" : e.getMessage();
            if (m.contains("Access is denied") || m.contains("Access Denied")) {
                lastError = "SMB 可达，但该账号无此共享目录的访问权限";
            } else if (m.contains("auth") || m.contains("Auth") || m.contains("logon")) {
                lastError = "SMB 可达，但账号或密码不对（" + m + "）";
            } else {
                lastError = "SMB 响应异常：" + m;
            }
            Log.w(TAG, "ping 失败: " + m);
            return false;
        } catch (java.io.IOException e) {
            // jcifs 只声明抛 SmbException/MalformedURLException；底层超时/拒连会包在
            // IOException 链里，运行时按具体类型细分（编译期不能单独 catch，会报"永不抛出"）
            if (e instanceof java.net.SocketTimeoutException) {
                lastError = "连接超时——确认手机与 NAS 在同一网络，且已开启 SMB 共享";
            } else if (e instanceof java.net.ConnectException) {
                lastError = "无法建立 SMB 连接——确认 IP/端口(445)正确、NAS 已开启 SMB";
            } else {
                lastError = "连接失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            Log.w(TAG, "ping 失败: " + e.getMessage());
            return false;
        } catch (Throwable t) {
            String m = t.getMessage() == null ? "" : t.getMessage();
            if (m.contains("Connection refused") || m.contains("unreachable")
                    || m.contains("UnknownHost") || m.contains("Connect")) {
                lastError = "无法建立 SMB 连接——确认 IP/端口(445)正确、NAS 已开启 SMB";
            } else {
                lastError = "连接失败：" + (m.isEmpty() ? t.getClass().getSimpleName() : m);
            }
            Log.w(TAG, "ping 失败: " + m);
            return false;
        }
    }

    @Override
    public boolean mkdirs(String relPath) {
        try {
            SmbFile f = new SmbFile(remotePath(relPath), ctx);
            if (f.exists()) return true;
            f.mkdirs();   // jcifs-ng 的 mkdirs 会一并创建父目录
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "mkdirs 失败: " + t.getMessage());
            return false;
        }
    }

    @Override
    public boolean put(String relPath, File local) {
        if (!local.isFile()) return false;
        try (InputStream in = new FileInputStream(local);
             OutputStream os = new SmbFile(remotePath(relPath), ctx).getOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "put 失败 " + relPath + ": " + t.getMessage());
            return false;
        }
    }

    @Override
    public boolean get(String relPath, File dest) {
        try {
            File parent = dest.getParentFile();
            if (parent != null) parent.mkdirs();
            try (InputStream in = new SmbFile(remotePath(relPath), ctx).getInputStream();
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "get 失败 " + relPath + ": " + t.getMessage());
            return false;
        }
    }

    @Override
    public Set<String> list(String relPath) {
        Set<String> names = new LinkedHashSet<>();
        try {
            SmbFile dir = new SmbFile(remotePath(relPath), ctx);
            if (!dir.exists()) return names;
            for (SmbFile f : dir.listFiles()) {
                String name = f.getName();
                if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
                if (name.isEmpty() || name.equals(".") || name.equals("..")) continue;
                names.add(name);
            }
        } catch (Throwable t) {
            Log.w(TAG, "list 失败 " + relPath + ": " + t.getMessage());
        }
        return names;
    }

    @Override
    public long lastModified(String relPath) {
        try {
            return new SmbFile(remotePath(relPath), ctx).lastModified();
        } catch (Throwable t) {
            return 0L;
        }
    }

    @Override
    public void close() {
        // jcifs-ng 无显式连接句柄，GC 回收即可
    }
}
