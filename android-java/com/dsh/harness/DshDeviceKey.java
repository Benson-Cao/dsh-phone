package com.dsh.harness;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.spec.ECGenParameterSpec;

/**
 * 设备密钥（AndroidKeyStore，不可导出）—— r24 `devicePubKey` 的密钥载体。
 *
 * <h3>为什么密钥要放在 Keystore 而不是 SharedPreferences</h3>
 * 这把密钥代表「这台手机」的身份。放在文件里等于把身份交给任何能读应用私有目录的
 * 进程（root、备份还原、adb backup）；Keystore 的私钥即便应用进程被 dump 也导不出，
 * 而且换一台手机（清数据 / 重装）必然得到不同的密钥 —— 这正是 TOFU 想表达的语义。
 *
 * <h3>参数选择的理由</h3>
 * <ul>
 *   <li><b>EC P-256</b>：SPKI 只有 91 字节，指纹计算快；Android 7+ 全量支持。</li>
 *   <li><b>SIGN | VERIFY</b>：当前只用来算指纹，但保留签名能力是刻意的 ——
 *       协议若把「持有性证明」提上台面（签名 challenge），不用重建密钥。</li>
 *   <li><b>不设 setUserAuthenticationRequired</b>：设了就需要每次解锁屏幕才能用，
 *       后台同步（收通知、补增量）会在锁屏状态直接失败。身份密钥不该和锁屏耦合。</li>
 *   <li><b>不设 setInvalidatedByBiometricEnrollment</b>：同一把密钥要活过指纹增删，
 *       否则用户加个指纹就把自己踢下线。</li>
 * </ul>
 *
 * <h3>生命周期</h3>
 * 卸载重装 / 清除数据 → Keystore 条目随之消失 → 指纹必变。
 * 因此 {@code phoneId} 必须与密钥**同生命周期**（见 {@link DshDeviceIdentity}）：
 * 两者一起消失时，服务端看到的是「一台新手机」，不会撞上 {@code DEVICE_KEY_MISMATCH}。
 */
public final class DshDeviceKey {

    /** 别名带版本号：将来换算法/参数时用新别名，老条目自然废弃，不需要迁移逻辑。 */
    static final String ALIAS = "dsh.device.key.v1";
    private static final String PROVIDER = "AndroidKeyStore";

    /** 密钥不可用（Keystore 异常 / API 过低）—— 调用方必须当作「无法提供指纹」，不能伪造。 */
    public static final class KeyException extends Exception {
        KeyException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private DshDeviceKey() {}

    /** 是否已存在设备密钥（不创建）。 */
    public static boolean exists() {
        try {
            KeyStore ks = load();
            return ks != null && ks.containsAlias(ALIAS);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 取设备公钥；不存在则创建。幂等 —— 重复调用不会换密钥。
     *
     * <p>创建是**同步且可能略慢**（首次生成 EC 密钥约 10~80ms），
     * 调用方不要在 UI 线程直接调（{@link PairActivity} 放在后台线程）。
     */
    public static PublicKey ensure() throws KeyException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            throw new KeyException("设备密钥需要 Android 6.0+", null);
        }
        try {
            KeyStore ks = load();
            if (ks == null) throw new KeyException("AndroidKeyStore 不可用", null);

            if (!ks.containsAlias(ALIAS)) {
                KeyPairGenerator kpg = KeyPairGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_EC, PROVIDER);
                kpg.initialize(new KeyGenParameterSpec.Builder(
                        ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                        .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build());
                KeyPair kp = kpg.generateKeyPair();
                return kp.getPublic();
            }

            Certificate cert = ks.getCertificate(ALIAS);
            if (cert == null) throw new KeyException("设备密钥条目损坏（无证书）", null);
            return cert.getPublicKey();
        } catch (KeyException e) {
            throw e;
        } catch (Exception e) {
            throw new KeyException("取设备密钥失败: " + e.getMessage(), e);
        }
    }

    /**
     * 设备公钥指纹（64 位小写 hex）；密钥不可用时返回 {@code null}。
     *
     * <p>返回 {@code null} 的语义是「本次不携带 `devicePubKey`」——
     * 协议规定按未携带处理，既不钉住也不阻断。**不要**用假值兜底：
     * 那会把一台机器永久钉在一个假的身份上，且无法自愈。
     */
    public static String fingerprintOrNull() {
        try {
            return DeviceKeyFingerprint.ofSpki(ensure().getEncoded());
        } catch (Exception e) {
            return null;
        }
    }

    /** 删除设备密钥（用户显式「重新开始」时调用，配合 {@link DshDeviceIdentity#rotate}）。 */
    public static void clear() {
        try {
            KeyStore ks = load();
            if (ks != null && ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS);
        } catch (Exception ignored) {
            // 删不掉也不阻断：phoneId 轮换后旧指纹已无意义
        }
    }

    private static KeyStore load() throws Exception {
        KeyStore ks = KeyStore.getInstance(PROVIDER);
        ks.load(null);
        return ks;
    }
}
