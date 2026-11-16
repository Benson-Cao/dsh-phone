package com.dsh.harness;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 运行时链路状态（r145，r147 修口径）—— 与 {@link HostProfile} 的「档案配置」严格分开。
 *
 * <h3>为什么单独一层</h3>
 * SSH 档「连不上」是**本次启动的结果**，不是用户填的配置；把它写进档案会让
 * 「配置还在」和「这次连上了吗」两件事混在一起。单独存一个布尔标记：
 * <ul>
 *   <li>{@link MainActivity} 在 SSH 隧道确认失败时置位，成功进 WebView 时清除；</li>
 *   <li>{@link HostSwitcherActivity} 读到「当前档是 ssh 且这次失败」就自动回落到
 *       本机引擎；只要当前档不是 ssh，就把那台机器标成<strong>离线</strong>
 *       —— 此刻没有隧道，给「可切换」是假希望；</li>
 *   <li>切换器会主动 TCP 探测 sshd 端口，结果只写进副标题（「22 端口可达 / 主机不可达」），
 *       <strong>不决定标签</strong>；不通时顺手置位，端口通不清除已有标记
 *       （22 端口可达但公钥被拒是常见失败形态，只有隧道真通才算恢复）；</li>
 *   <li>用户主动切回 SSH 重试时先清标记，否则尝试连接期间再打开切换器会被判成又失败。</li>
 * </ul>
 *
 * <p><b>r147 口径修正</b>：回落到本机引擎<strong>不再清标记</strong>。以前自动回落时
 * 顺手清掉，下一屏（current=local）立刻把同一台连不上的机器显示成「可切换」，
 * 与失败页写的「隧道没有建起来」自相矛盾。失败是这台主机的事实，不是当前档的属性。
 *
 * <p>只存布尔，不存地址、令牌或失败原因（原因在失败页文案里，不落盘）。
 * 用 {@code apply()} 异步写盘：同进程内内存值立即可见，切换器紧接着读得到。
 */
public final class LinkState {

    private static final String PREF = "dsh_link_state";
    private static final String KEY_SSH_FAILED = "sshFailed";

    private LinkState() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 本次 SSH 连接已确认失败（隧道没起来 / 指纹拒绝 / 页面没加载出来 / 探测不可达）。 */
    public static void markSshFailed(Context c) {
        prefs(c).edit().putBoolean(KEY_SSH_FAILED, true).apply();
    }

    /** SSH 成功进页面 / 主动重连 / 探测确认主机恢复：清掉标记。回落本机不清。 */
    public static void clearSshFailed(Context c) {
        prefs(c).edit().remove(KEY_SSH_FAILED).apply();
    }

    public static boolean sshFailed(Context c) {
        return prefs(c).getBoolean(KEY_SSH_FAILED, false);
    }
}
