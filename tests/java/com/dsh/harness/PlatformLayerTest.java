package com.dsh.harness;

/**
 * 平台层纯逻辑测试 —— 在**桌面 JDK** 上跑，不需要真机、不需要 Gradle。
 *
 * <p>为什么值得单独搭这一层：安卓侧单元测试要 AGP + Robolectric 才能跑，
 * 而本仓库的构建链是「Termux 源码树 + sed 补丁 + gradle」，没有现成的 test
 * sourceset。把可纯化的逻辑（指纹、HMAC、请求体、错误映射、地址规整）抽成
 * 零 Android 依赖的类之后，一次 `scripts/test-java.sh` 就能在秒级回归它们。
 *
 * <p>**跨语言对照**：指纹与 HMAC 的期望值不写死常量，而是由 `node -e`
 * 现场算出来、经系统属性传进来。这样单元测试同时钉住了「Java 与服务端
 * Node 口径一致」——写死常量只能证明 Java 自己跟自己对得上。
 */
public final class PlatformLayerTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        String nodeSig = System.getProperty("nodeSig", "");
        String nodeFp = System.getProperty("nodeFp", "");
        String nodeSshFp = System.getProperty("nodeSshFp", "");

        fingerprint(nodeFp);
        proof(nodeSig);
        sshHostKey(nodeSshFp);
        request();
        errors();
        hosts();
        urls();

        System.out.println();
        System.out.println("# " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ---- 指纹 ----

    private static void fingerprint(String nodeFp) {
        byte[] spki = "dsh-device-key-test-vector".getBytes();
        String fp = DeviceKeyFingerprint.ofSpki(spki);

        check("指纹是 64 位小写 hex", fp.matches("^[0-9a-f]{64}$"));
        if (!nodeFp.isEmpty()) {
            check("指纹与 node crypto 一致", nodeFp.equals(fp));
        } else {
            System.out.println("ok - 跳过 node 对照（未提供 nodeFp）");
        }

        check("大写归一为小写", DeviceKeyFingerprint.normalize(fp.toUpperCase()).equals(fp));
        check("冒号分隔被去掉",
                DeviceKeyFingerprint.normalize(fp.substring(0, 32) + ":" + fp.substring(32)).equals(fp));
        check("带空白被去掉",
                DeviceKeyFingerprint.normalize("  " + fp + "\n").equals(fp));
        check("63 位被判非法", DeviceKeyFingerprint.normalize(fp.substring(1)) == null);
        check("65 位被判非法", DeviceKeyFingerprint.normalize(fp + "0") == null);
        check("非 hex 被判非法",
                DeviceKeyFingerprint.normalize("zz" + fp.substring(2)) == null);
        check("null 被判非法", !DeviceKeyFingerprint.isValid(null));
        check("空串被判非法", !DeviceKeyFingerprint.isValid(""));
        check("空公钥抛异常", throwsOn(new Runnable() {
            @Override
            public void run() {
                DeviceKeyFingerprint.ofSpki(new byte[0]);
            }
        }));
    }

    // ---- HMAC ----

    private static void proof(String nodeSig) {
        String secret = "9f2c4a17d0b35e68ac0127be945df013";
        String challenge = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718";

        String sig = PairProof.sign(secret, challenge);
        check("签名是 64 位小写 hex", sig.matches("^[0-9a-f]{64}$"));
        if (!nodeSig.isEmpty()) {
            check("签名与 Node createHmac 一致（密钥按 hex 文本处理）", nodeSig.equals(sig));
        } else {
            System.out.println("ok - 跳过 node 对照（未提供 nodeSig）");
        }

        // 对照：把密钥错误地 hex 解码成字节，结果必须不同 ——
        // 这条就是把「搬服务端代码时最容易写错的那一步」钉死
        String wrong = PairProof.sign("wrong-secret", challenge);
        check("换密钥必得不同签名", !wrong.equals(sig));
        check("常量比较自反", PairProof.equal(sig, sig));
        check("常量比较拒绝不同值", !PairProof.equal(sig, wrong));
        check("常量比较拒绝 null", !PairProof.equal(sig, null));
        check("空 challenge 抛异常", throwsOn(new Runnable() {
            @Override
            public void run() {
                PairProof.sign("9f2c4a17d0b35e68ac0127be945df013", "");
            }
        }));
    }

    // ---- SSH 主机指纹（TOFU）----

    private static void sshHostKey(String nodeSshFp) {
        byte[] blob = "dsh-ssh-hostkey-vector".getBytes();
        String fp = SshHostKey.fingerprintOfBlob(blob);

        check("SSH 指纹带 SHA256: 前缀", fp.startsWith("SHA256:"));
        check("SSH 指纹 base64 无填充", !fp.contains("="));
        if (!nodeSshFp.isEmpty()) {
            check("SSH 指纹与 node crypto 一致", nodeSshFp.equals(fp));
        } else {
            System.out.println("ok - 跳过 node 对照（未提供 nodeSshFp）");
        }

        check("归一：裸串补前缀", ("SHA256:" + fp.substring(7)).equals(SshHostKey.normalize(fp.substring(7))));
        check("归一：去掉注释", fp.equals(SshHostKey.normalize(fp + " user@host")));
        check("归一：空串为 null", SshHostKey.normalize("  ") == null);
        check("归一：非法字符为 null", SshHostKey.normalize("SHA256:abc$def") == null);

        check("首次连接 → FIRST_SEEN",
                SshHostKey.decide(null, fp) == SshHostKey.Decision.FIRST_SEEN);
        check("指纹相同 → MATCH",
                SshHostKey.decide(fp, fp) == SshHostKey.Decision.MATCH);
        check("指纹不同 → MISMATCH",
                SshHostKey.decide(fp, "SHA256:" + repeat('A', 43)) == SshHostKey.Decision.MISMATCH);
        check("取不到本次指纹 → MISMATCH（不能当首次放行）",
                SshHostKey.decide(fp, null) == SshHostKey.Decision.MISMATCH);

        String line = "192.0.2.10 ssh-ed25519 " + base64Of(blob);
        check("解析 keyscan 行得到同一指纹",
                fp.equals(SshHostKey.fingerprintOfKeyLine(line)));
        check("注释行返回 null", SshHostKey.blobOfKeyLine("# comment") == null);
        check("字段不足返回 null", SshHostKey.blobOfKeyLine("only-two ssh-ed25519") == null);
        check("base64 解码 hello",
                "hello".equals(new String(SshHostKey.decodeBase64("aGVsbG8="))));
    }

    /** 测试用：把字节编成标准 base64（带填充），只为拼 keyscan 行。 */
    private static String base64Of(byte[] d) {
        final String T = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i + 2 < d.length) {
            int n = ((d[i] & 0xff) << 16) | ((d[i + 1] & 0xff) << 8) | (d[i + 2] & 0xff);
            sb.append(T.charAt((n >> 18) & 63)).append(T.charAt((n >> 12) & 63))
              .append(T.charAt((n >> 6) & 63)).append(T.charAt(n & 63));
            i += 3;
        }
        int rem = d.length - i;
        if (rem == 1) {
            int n = (d[i] & 0xff) << 16;
            sb.append(T.charAt((n >> 18) & 63)).append(T.charAt((n >> 12) & 63)).append("==");
        } else if (rem == 2) {
            int n = ((d[i] & 0xff) << 16) | ((d[i + 1] & 0xff) << 8);
            sb.append(T.charAt((n >> 18) & 63)).append(T.charAt((n >> 12) & 63))
              .append(T.charAt((n >> 6) & 63)).append('=');
        }
        return sb.toString();
    }

    // ---- 请求体 ----

    private static void request() {
        String key = "0123456789ABCDEF0123456789abcdef0123456789abcdef0123456789ABCDEF";
        String body = PairRequest.body("ch-1", "sig-1", "phone-1234abcd", "Pixel 8 · Android 14", key);
        check("请求体带归一化指纹", body.contains("\"devicePubKey\":\"" + key.toLowerCase() + "\""));
        check("请求体带 phoneId", body.contains("\"phoneId\":\"phone-1234abcd\""));
        check("请求体带 clientName", body.contains("\"clientName\":\"Pixel 8 · Android 14\""));

        String noKey = PairRequest.body("ch-1", "sig-1", null, null, "not-a-fingerprint");
        check("非法指纹整个字段省略", !noKey.contains("devicePubKey"));
        check("空 phoneId 不写字段", !noKey.contains("phoneId"));
        check("空 clientName 不写字段", !noKey.contains("clientName"));
        check("省略后仍是合法 JSON 形状", noKey.startsWith("{") && noKey.endsWith("}"));

        String shortId = PairRequest.body("ch", "sig", "abc", null, null);
        check("过短 phoneId 被判非法并省略", !shortId.contains("phoneId"));

        String quoted = PairRequest.body("ch", "sig", null, "a\"b\\c", null);
        check("引号与反斜杠被转义", quoted.contains("\"clientName\":\"a\\\"b\\\\c\""));

        String ctrl = PairRequest.body("ch", "sig", null, "a\u0001b\u007fc", null);
        check("控制字符被剔除", ctrl.contains("\"clientName\":\"abc\""));

        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 60; i++) longName.append('x');
        String trunc = PairRequest.body("ch", "sig", null, longName.toString(), null);
        check("设备名截断到 40", trunc.contains("\"clientName\":\"" + repeat('x', 40) + "\"")
                && !trunc.contains(repeat('x', 41)));

        check("缺 challenge 抛异常", throwsOn(new Runnable() {
            @Override
            public void run() {
                PairRequest.body(null, "sig", null, null, null);
            }
        }));
        check("缺 sig 抛异常", throwsOn(new Runnable() {
            @Override
            public void run() {
                PairRequest.body("ch", "  ", null, null, null);
            }
        }));
    }

    // ---- 错误处置 ----

    private static void errors() {
        PairError.Finding mismatch = PairError.of(409, PairError.DEVICE_KEY_MISMATCH);
        check("指纹变化 → 不可原样重试", !mismatch.retryable);
        check("指纹变化 → 提供换身份", mismatch.needsRotate);
        check("指纹变化 → 文案提到管理台收回", mismatch.action.contains("管理台"));

        PairError.Finding limit = PairError.of(409, PairError.CLIENT_LIMIT);
        check("名额上限 → 不可原样重试", !limit.retryable);
        check("名额上限 → 不换身份", !limit.needsRotate);

        PairError.Finding code = PairError.of(400, PairError.CODE_INVALID);
        check("配对码错 → 不可原样重试", !code.retryable);

        PairError.Finding expired = PairError.of(400, PairError.CHALLENGE_EXPIRED);
        check("挑战过期 → 不可原样重试", !expired.retryable);

        PairError.Finding net = PairError.of(0, null);
        check("无响应 → 网络故障且可重试",
                PairError.NETWORK.equals(net.code) && net.retryable);

        PairError.Finding offline = PairError.of(409, PairError.HOST_OFFLINE);
        check("电脑离线 → 可重试", offline.retryable);

        PairError.Finding conflict = PairError.of(409, "SOMETHING_NEW");
        check("未知 409 不冒充离线且不可盲重试",
                !PairError.HOST_OFFLINE.equals(conflict.code) && !conflict.retryable);

        PairError.Finding server = PairError.of(503, null);
        check("5xx → 可重试", server.retryable && server.title.contains("出错"));

        PairError.Finding unauth = PairError.of(401, null);
        check("401 无码 → 按未授权处置", PairError.UNAUTHORIZED.equals(unauth.code));
    }

    // ---- 主机切换处置 ----

    private static void hosts() {
        HostsError.Finding unauth = HostsError.of(401, HostsError.UNAUTHORIZED);
        check("切换·未授权 → 不可原样重试", !unauth.retryable);
        check("切换·未授权 → 提示重新配对", unauth.action.contains("重新配对"));

        HostsError.Finding confirm = HostsError.of(400, HostsError.CONFIRM_REQUIRED);
        check("切换·缺确认 → 不可原样重试", !confirm.retryable);
        check("切换·缺确认 → 指向确认框", confirm.action.contains("确认"));

        HostsError.Finding offline = HostsError.of(404, HostsError.HOST_OFFLINE);
        check("切换·目标不在 → 可重试", offline.retryable);

        HostsError.Finding limit = HostsError.of(409, HostsError.CLIENT_LIMIT);
        check("切换·名额满 → 不可原样重试", !limit.retryable);

        HostsError.Finding net = HostsError.of(0, null);
        check("切换·无响应 → 网络故障可重试",
                HostsError.NETWORK.equals(net.code) && net.retryable);

        HostsError.Finding server = HostsError.of(502, "BAD_GATEWAY");
        check("切换·5xx → 可重试", server.retryable);

        HostsError.Finding unknown401 = HostsError.of(401, "SOMETHING");
        check("切换·未知码 + 401 → 按未授权", HostsError.UNAUTHORIZED.equals(unknown401.code));

        HostsError.Finding unknown = HostsError.of(418, null);
        check("切换·未知状态 → 通用码且可重试",
                "HTTP_418".equals(unknown.code) && unknown.retryable);
    }

    // ---- 地址规整 ----

    private static void urls() {
        check("补默认 scheme",
                "http://192.168.0.5:8787".equals(PairUrl.normalizeBase("192.168.0.5:8787")));
        check("保留 https",
                "https://relay.example.com".equals(PairUrl.normalizeBase(" https://relay.example.com/ ")));
        check("去掉尾斜杠",
                "http://192.168.0.5:8787".equals(PairUrl.normalizeBase("http://192.168.0.5:8787///")));
        check("保留路径前缀",
                "http://gwire.example.com/relay".equals(PairUrl.normalizeBase("gwire.example.com/relay/")));
        check("拒绝空串", PairUrl.normalizeBase("   ") == null);
        check("拒绝只有 scheme", PairUrl.normalizeBase("http://") == null);
        check("拒绝带凭据的地址", PairUrl.normalizeBase("http://u:p@host:1") == null);
        check("拒绝内嵌空白", PairUrl.normalizeBase("http://ho st:1") == null);

        check("端点拼接无重复斜杠",
                "http://h:1/pair/verify".equals(PairUrl.endpoint("h:1/", "pair/verify")));
        check("非法 base 拼出 null", PairUrl.endpoint("   ", "/pair") == null);
    }

    // ---- 小工具 ----

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("ok - " + name);
        } else {
            failed++;
            System.out.println("not ok - " + name);
        }
    }

    private static boolean throwsOn(Runnable r) {
        try {
            r.run();
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }
}
