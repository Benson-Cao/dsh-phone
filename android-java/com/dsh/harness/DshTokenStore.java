package com.dsh.harness;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 设备令牌落盘（**密文**）—— 配对成功后 `deviceToken` 的唯一归宿。
 *
 * <h3>为什么必须由壳持有，而不是交给页面</h3>
 * 令牌是这台手机在 Relay 上的凭据：拿到它就能读写这台电脑的会话。页面里跑着
 * 官方 dsh web 与**第三方插件**（市场可装），把令牌塞进 `window` 等于让任意插件
 * 都能取走它。所以壳只对外暴露**能力**（查询、切换、取 uiUrl），不暴露令牌本体。
 *
 * <h3>为什么落盘要加密</h3>
 * 明文令牌在应用私有目录里，对 root / 备份还原 / 取证镜像都是可读的。
 * 这里用 AndroidKeyStore 里的 AES-256-GCM 密钥包裹：密钥不可导出，
 * 密文与密钥分离（备份走密文，到了新机器没有那把密钥就解不开）——
 * 语义上等价于「换机即失效」，与 {@link DshDeviceKey} 的 TOFU 一致。
 *
 * <h3>失败语义</h3>
 * 解不开（密钥被清、密文来自别的设备、GCM 校验失败）一律返回 {@code null} 并**顺手清掉
 * 密文**：留着只会让每次启动都失败一次，而正确处置本来就是「重新配对」。
 */
public final class DshTokenStore {

    private static final String PREFS = "dsh_pair";
    private static final String K_TOKEN = "deviceTokenSealed";
    private static final String KEY_ALIAS = "dsh.device.token.v1";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;

    private DshTokenStore() {}

    /** 是否已存有令牌（不判断能否解密，只判断有没有密文）。 */
    public static boolean has(Context ctx) {
        String sealed = prefs(ctx).getString(K_TOKEN, null);
        return sealed != null && sealed.indexOf(':') > 0;
    }

    /** 保存令牌（覆盖旧值）。 */
    public static boolean save(Context ctx, String token) {
        if (token == null || token.trim().isEmpty()) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
        try {
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.ENCRYPT_MODE, secretKey());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(token.trim().getBytes("UTF-8"));
            String sealed = Base64.encodeToString(iv, Base64.NO_WRAP) + ":"
                    + Base64.encodeToString(ct, Base64.NO_WRAP);
            prefs(ctx).edit().putString(K_TOKEN, sealed).apply();
            return true;
        } catch (Exception e) {
            // 存不下就别装作成功：调用方据此提示「本次配对需要重新来过」
            return false;
        }
    }

    /** 读取令牌；解不开返回 {@code null} 并清掉报废密文。 */
    public static String load(Context ctx) {
        String sealed = prefs(ctx).getString(K_TOKEN, null);
        if (sealed == null) return null;
        int sep = sealed.indexOf(':');
        if (sep <= 0) {
            prefs(ctx).edit().remove(K_TOKEN).apply();
            return null;
        }
        try {
            byte[] iv = Base64.decode(sealed.substring(0, sep), Base64.NO_WRAP);
            byte[] ct = Base64.decode(sealed.substring(sep + 1), Base64.NO_WRAP);
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(c.doFinal(ct), "UTF-8");
        } catch (Exception e) {
            prefs(ctx).edit().remove(K_TOKEN).apply();
            return null;
        }
    }

    /** 清除令牌（吊销 / 换身份 / 重新配对时调用）。 */
    public static void clear(Context ctx) {
        prefs(ctx).edit().remove(K_TOKEN).apply();
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS);
        } catch (Exception ignored) {
            // 删不掉密钥不影响正确性：密文已清，令牌不可再得
        }
    }

    private static SecretKey secretKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        java.security.Key existing = ks.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;

        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
