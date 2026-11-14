package com.dsh.harness;

/**
 * SSH 配置页的**纯校验逻辑**（无 Android 依赖，可被桌面 JDK 直接跑单测）。
 *
 * <p>为什么单独抽出来：这段校验决定「用户填的这一坨能不能变成一条 ssh 命令」。
 * 放在 Activity 里就只能靠手点验，而它是纯函数 —— 校验规则一旦写错（例如允许
 * `user@host` 混进主机栏，拼出的命令变成 `ssh -l bob bob@host`），症状是
 * 「连不上但说不清哪错了」。抽出来才有确定性断言。
 *
 * <p>口径：
 * <ul>
 *   <li>主机栏只放**主机名或 IP**，不带 `ssh://`、不带 `user@`、不带端口
 *       （端口有独立栏位，混在一起会拼出 `host:2222:2222`）。</li>
 *   <li>端口 1–65535；本机端口 1024–65535（低端口需要系统权限，普通应用绑不上）。</li>
 *   <li>用户名不能为空、不能含空白：ssh 会把它当参数分隔。</li>
 * </ul>
 */
public final class SshForm {

    /** 本机端口下限：低于 1024 需要 root/系统权限，普通应用 bind 会被拒。 */
    public static final int MIN_LOCAL_PORT = 1024;
    public static final int MAX_PORT = 65535;
    /** 隧道远端（电脑侧）dsh 端口默认值。 */
    public static final int DEFAULT_TARGET_PORT = 3080;

    private SshForm() {}

    /**
     * 解析端口：空白用 {@code fallback}，非数字返回 {@code -1}（交给 {@link #validate} 报错）。
     *
     * <p>为什么不悄悄回落到默认值：用户把 2222 打成 22a2 时，静默回落 22 会连到别的
     * 端口上，还可能真连上一台机器 —— 那比报错危险。
     */
    public static int parsePort(String s, int fallback) {
        String t = s == null ? "" : s.trim();
        if (t.isEmpty()) return fallback;
        try {
            return Integer.parseInt(t);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 校验四个字段（远端端口按默认 3080）。
     *
     * @return {@code null} 表示通过；否则是给用户看的一句话（直接显示，不要再包一层）
     */
    public static String validate(String host, int port, String user, int localPort) {
        return validate(host, port, user, localPort, DEFAULT_TARGET_PORT);
    }

    /**
     * 校验五个字段。
     *
     * <p>{@code targetPort} 是**电脑侧** dsh 的端口 —— 隧道另一头打到哪里。默认 3080 只是
     * 手机端引擎的习惯值；真实电脑上 dsh 往往监听别的端口（实测这台 Windows 上的 dsh 在
     * 61167，3080 上什么都没有），所以必须可配，否则隧道打通也只是打到空端口。
     */
    public static String validate(String host, int port, String user, int localPort, int targetPort) {
        String h = host == null ? "" : host.trim();
        String u = user == null ? "" : user.trim();

        if (h.isEmpty()) return "主机地址不能为空。";
        if (hasSpace(h)) return "主机地址里不能有空格。";
        if (h.startsWith("ssh://") || h.startsWith("SSH://")) {
            return "主机栏只填主机名或 IP，不要带 ssh://。";
        }
        if (h.indexOf('@') >= 0) {
            return "主机栏不要写 user@host，用户名填下面那一栏。";
        }
        if (h.indexOf(':') >= 0) {
            return "主机栏不要带端口，端口填下面那一栏。";
        }
        if (h.startsWith("-")) return "主机地址不能以 - 开头。";

        if (port < 1 || port > MAX_PORT) return "SSH 端口要在 1–65535 之间。";
        if (u.isEmpty()) return "用户名不能为空。";
        if (hasSpace(u)) return "用户名里不能有空格。";
        if (u.startsWith("-")) return "用户名不能以 - 开头。";
        if (localPort < MIN_LOCAL_PORT || localPort > MAX_PORT) {
            return "本机端口要在 1024–65535 之间（更低的端口需要系统权限）。";
        }
        if (localPort == port) return "本机端口不能和 SSH 端口相同。";
        if (targetPort < 1 || targetPort > MAX_PORT) return "远端 dsh 端口要在 1–65535 之间。";
        return null;
    }

    /** 令牌里不能有空白（要拼进 URL 查询串）。空令牌合法（多数本机 dsh 不需要）。 */
    public static String validateToken(String token) {
        if (token == null) return null;
        String t = token.trim();
        if (t.isEmpty()) return null;
        if (hasSpace(t)) return "访问令牌里不能有空格或换行（复制时多带了空白）。";
        return null;
    }

    /**
     * 隧道打通后要加载的地址。
     *
     * <p>为什么带令牌：电脑上的 dsh web **对回环请求也要鉴权**（实测
     * `GET http://127.0.0.1:61167/` 返回 401）。不带令牌打开隧道只会看到 401，
     * 而不是界面 —— 那不是"连上了"，是"连上了但进不去"。
     *
     * @param token 可为空；非空时拼成 `?token=…`（URL 编码）
     */
    public static String sshUrl(int localPort, String token) {
        String base = "http://127.0.0.1:" + localPort + "/";
        String t = token == null ? "" : token.trim();
        if (t.isEmpty()) return base;
        try {
            return base + "?token=" + java.net.URLEncoder.encode(t, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return base;   // UTF-8 一定存在；兜底不拼令牌，好过拼个坏 URL
        }
    }

    private static boolean hasSpace(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') return true;
        }
        return false;
    }
}
