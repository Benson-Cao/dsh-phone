package com.dsh.harness;

import android.util.Log;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Vector;

/**
 * SFTP 传输实现（基于 JSch）。对应设计稿 S17「连接类型 · SFTP」。
 *
 * 在 S52 选 SFTP 后，「连接并测试」会用 {@link #ping()} 真实建立 SSH 会话并完成鉴权；
 * 保存后 {@link DshSync#syncOnce} 通过本类把 {@code .dsh/storages} 双向同步到 NAS。
 *
 * <p>同步范围、冲突策略与 WebDAV 完全一致（见 {@link DshSync}），只是换了传输层。
 * 局域网自管 NAS：跳过主机密钥校验、不写 known_hosts（符合「明文仅限局域网」约定）。
 */
final class DshSftpClient implements DshSync.NasTransport {

    private static final String TAG = "DshSftpClient";
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int CHANNEL_TIMEOUT_MS = 15000;

    private final String host;
    private final int port;
    private final String user;
    private final String pass;
    private final String base;   // 远端基础目录：<share>/dsh-phone/storages

    private Session session;
    private ChannelSftp sftp;

    /** 最近一次失败的人类可读原因（v30.6：透出到「连接并测试」）。 */
    private String lastError = "";

    @Override
    public String lastError() { return lastError; }

    DshSftpClient(String host, String port, String share, String user, String pass) {
        this.host = host == null ? "" : host.trim();
        int p;
        try {
            p = (port == null || port.trim().isEmpty()) ? 22 : Integer.parseInt(port.trim());
        } catch (NumberFormatException e) {
            p = 22;
        }
        this.port = p;
        this.user = user == null ? "" : user.trim();
        this.pass = pass == null ? "" : pass;
        String sh = share == null ? "" : share.trim();
        this.base = (sh.isEmpty() ? "" : (sh.endsWith("/") ? sh : sh + "/")) + DshSync.REMOTE_ROOT;
    }

    private synchronized void ensureConnected() throws JSchException {
        if (session != null && session.isConnected() && sftp != null && sftp.isConnected()) return;
        JSch jsch = new JSch();
        session = jsch.getSession(user, host, port);
        session.setPassword(pass);
        session.setConfig("StrictHostKeyChecking", "no");
        session.setConfig("UserKnownHostsFile", "/dev/null");
        session.connect(CONNECT_TIMEOUT_MS);
        sftp = (ChannelSftp) session.openChannel("sftp");
        sftp.connect(CHANNEL_TIMEOUT_MS);
    }

    private synchronized void disconnect() {
        if (sftp != null) {
            try { sftp.disconnect(); } catch (Throwable ignored) { }
        }
        if (session != null) {
            try { session.disconnect(); } catch (Throwable ignored) { }
        }
        sftp = null;
        session = null;
    }

    private String remotePath(String rel) {
        return base.isEmpty() ? rel : (base + "/" + rel);
    }

    private static String dirOf(String path) {
        int i = path.lastIndexOf('/');
        return i <= 0 ? "/" : path.substring(0, i);
    }

    @Override
    public boolean ping() {
        lastError = "";
        try {
            ensureConnected();
            // 验证可达 + 鉴权：探测基础目录，不存在则列其父目录
            try {
                sftp.lstat(base.isEmpty() ? "." : base);
            } catch (SftpException e) {
                sftp.ls(base.isEmpty() ? "." : dirOf(base));
            }
            return true;
        } catch (JSchException e) {
            String m = e.getMessage() == null ? "" : e.getMessage();
            if (m.contains("Auth fail") || m.contains("auth")) {
                lastError = "SSH 可达，但账号或密码不对（" + m + "）";
            } else if (m.contains("timeout") || m.contains("Timeout")) {
                lastError = "连接超时——确认手机与 NAS 在同一网络，且已开启 SSH/SFTP";
            } else if (m.contains("refused") || m.contains("UnknownHost") || m.contains("unreachable")) {
                lastError = "无法建立 SSH 连接——确认 IP/端口(22)正确、NAS 已开启 SSH";
            } else {
                lastError = "SSH 连接失败：" + m;
            }
            Log.w(TAG, "ping 失败: " + m);
            disconnect();
            return false;
        } catch (Throwable t) {
            lastError = "连接失败：" + t.getClass().getSimpleName();
            Log.w(TAG, "ping 失败: " + t.getMessage());
            disconnect();
            return false;
        }
    }

    @Override
    public boolean mkdirs(String relPath) {
        try {
            ensureConnected();
            String[] parts = remotePath(relPath).split("/");
            StringBuilder acc = new StringBuilder();
            for (String part : parts) {
                if (part.isEmpty()) continue;
                acc.append('/').append(part);
                try {
                    sftp.stat(acc.toString());
                } catch (SftpException e) {
                    try {
                        sftp.mkdir(acc.toString());
                    } catch (SftpException ignore) {
                        return false;
                    }
                }
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "mkdirs 失败: " + t.getMessage());
            disconnect();
            return false;
        }
    }

    @Override
    public boolean put(String relPath, File local) {
        if (!local.isFile()) return false;
        try {
            ensureConnected();
            mkdirs(dirOf(remotePath(relPath)));
            try (InputStream in = new FileInputStream(local);
                 OutputStream os = sftp.put(remotePath(relPath))) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "put 失败 " + relPath + ": " + t.getMessage());
            disconnect();
            return false;
        }
    }

    @Override
    public boolean get(String relPath, File dest) {
        try {
            ensureConnected();
            File parent = dest.getParentFile();
            if (parent != null) parent.mkdirs();
            try (InputStream in = sftp.get(remotePath(relPath));
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "get 失败 " + relPath + ": " + t.getMessage());
            disconnect();
            return false;
        }
    }

    @Override
    public Set<String> list(String relPath) {
        Set<String> names = new LinkedHashSet<>();
        try {
            ensureConnected();
            Vector<?> v = sftp.ls(remotePath(relPath));
            for (Object o : v) {
                if (!(o instanceof ChannelSftp.LsEntry)) continue;
                String name = ((ChannelSftp.LsEntry) o).getFilename();
                if (name.equals(".") || name.equals("..")) continue;
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
            ensureConnected();
            SftpATTRS a = sftp.stat(remotePath(relPath));
            return ((long) a.getMTime()) * 1000L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    @Override
    public void close() {
        disconnect();
    }
}
