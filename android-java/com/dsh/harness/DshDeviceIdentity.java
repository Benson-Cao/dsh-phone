package com.dsh.harness;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.security.SecureRandom;

/**
 * 本机身份：`phoneId` + 展示名 + 上一次配对结果。
 *
 * <p><b>核心不变量：`phoneId` 与设备密钥同生命周期。</b>
 * 两者都随「清除数据 / 卸载重装」一起消失，服务端因此看到的是一致的「一台新手机」。
 * 若只有密钥丢失而 `phoneId` 还在，r24 会回 {@code DEVICE_KEY_MISMATCH} 阻断 ——
 * 这是设计使然（防身份被顶替），但对用户表现为「配不上了」，所以
 * {@link #rotate(Context)} 必须把两者**一起**换掉。
 *
 * <p>`phoneId` 格式对齐服务端 `^[A-Za-z0-9_-]{8,64}$`，取值风格与 Relay 自生成的
 * `phone-<8 hex>` 一致，便于日志里一眼看出这是手机。
 */
public final class DshDeviceIdentity {

    private static final String PREFS = "dsh_pair";
    private static final String K_PHONE_ID = "phoneId";
    private static final String K_LAST_HOST = "lastHostDeviceId";
    private static final String K_LAST_RELAY = "lastRelayUrl";
    private static final String K_LAST_AT = "lastPairedAt";

    private DshDeviceIdentity() {}

    /** 取本机 phoneId；没有就生成并落盘（同步、极小）。 */
    public static String phoneId(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String v = p.getString(K_PHONE_ID, null);
        if (PairRequest.sanitizePhoneId(v) != null) return v;
        String fresh = newPhoneId();
        p.edit().putString(K_PHONE_ID, fresh).apply();
        return fresh;
    }

    /** 展示名：机型 + Android 版本，服务端只用于列表展示。 */
    public static String clientName() {
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        if (model.isEmpty()) model = "Android";
        String name = model + " · Android " + Build.VERSION.RELEASE;
        String clean = PairRequest.sanitizeClientName(name);
        return clean != null ? clean : "Android";
    }

    /** 配对成功后记下「配到哪台电脑」，供切换器与离线页使用。 */
    public static void rememberBinding(Context ctx, String hostDeviceId, String relayUrl) {
        SharedPreferences.Editor e = prefs(ctx).edit()
                .putString(K_LAST_HOST, hostDeviceId)
                .putLong(K_LAST_AT, System.currentTimeMillis());
        // 回环地址不落盘：Relay 按请求 Host 头回填 relayUrl，电脑侧自测/端口转发时会是
        // 127.0.0.1 —— 存下来只会让配对页预填一个手机上必然连不上的地址（PairUrl.isLoopback）。
        if (relayUrl != null && !PairUrl.isLoopback(relayUrl)) {
            e.putString(K_LAST_RELAY, relayUrl);
        }
        e.apply();
    }

    public static String lastHostDeviceId(Context ctx) {
        return prefs(ctx).getString(K_LAST_HOST, null);
    }

    public static String lastRelayUrl(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String v = p.getString(K_LAST_RELAY, null);
        // 历史脏数据就地清理：早期版本会把回环地址存进来，读到就删，
        // 否则配对页永远预填「http://127.0.0.1:8899」，用户点几次都以为是自己填错。
        if (v != null && PairUrl.isLoopback(v)) {
            p.edit().remove(K_LAST_RELAY).apply();
            return null;
        }
        return v;
    }

    public static long lastPairedAt(Context ctx) {
        return prefs(ctx).getLong(K_LAST_AT, 0L);
    }

    /**
     * 「重新开始」：换掉设备密钥 + 换掉 phoneId，下一次配对会被服务端当作新手机。
     *
     * <p>只应在用户明确选择后调用（{@code DEVICE_KEY_MISMATCH} 的处置分支），
     * 且必须提示后果：旧设备行仍在电脑端占一个名额，需要在管理台收回。
     */
    public static String rotate(Context ctx) {
        DshDeviceKey.clear();
        // 令牌必须跟着一起清：留着旧令牌配新身份，等于「新设备拿旧凭据」，
        // 服务端会按 HOST_MISMATCH / UNAUTHORIZED 拒绝，且用户无法自愈
        DshTokenStore.clear(ctx);
        String fresh = newPhoneId();
        prefs(ctx).edit()
                .putString(K_PHONE_ID, fresh)
                .remove(K_LAST_HOST)
                .remove(K_LAST_RELAY)
                .remove(K_LAST_AT)
                .apply();
        return fresh;
    }

    /** 生成本机 phoneId（`phone-` + 8 位小写 hex）。 */
    static String newPhoneId() {
        SecureRandom r = new SecureRandom();
        byte[] b = new byte[4];
        r.nextBytes(b);
        StringBuilder sb = new StringBuilder("phone-");
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
