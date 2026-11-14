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
     * 校验四个字段。
     *
     * @return {@code null} 表示通过；否则是给用户看的一句话（直接显示，不要再包一层）
     */
    public static String validate(String host, int port, String user, int localPort) {
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
        return null;
    }

    private static boolean hasSpace(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') return true;
        }
        return false;
    }
}
