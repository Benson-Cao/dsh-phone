package com.dsh.harness;

import android.util.Log;
import android.webkit.CookieManager;

/**
 * 档案切换时**必须一起归零**的进程级状态。
 *
 * <p>为什么单独抽一个类：这段逻辑原来只写在 `MainActivity.switchProfile()` 里，
 * 而切档入口现在有两个（原生切换器页 / 离线页「进入电脑」），
 * 谁漏掉一步就是跨主机串会话（R-22）。集中一处，新增入口就不可能漏。
 *
 * <p>两件事各自的理由：
 * <ul>
 *   <li><b>清 cookie</b>：`CookieManager` 是进程级单例，上一台主机的 `dsh-auth` /
 *       Relay 反代会话 cookie 带进下一台，就是跨主机串会话；</li>
 *   <li><b>作废预热 WebView</b>：`DshPreloadWeb` 里常驻的是**上一个档案**的首页，
 *       不作废 epoch 就可能被领养，表现是「切了档却还看到上一条连接」。</li>
 * </ul>
 *
 * <p>WebView 实例本身的销毁由调用方做（它持有引用）；本类只处理全局状态。
 */
public final class ProfileSession {

    private static final String TAG = "ProfileSession";

    private ProfileSession() {}

    /** 清 cookie + 作废预热。可在主线程调用（必须）。 */
    public static void resetRuntime() {
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.removeAllCookies(null);
            cm.flush();
        } catch (Throwable t) {
            Log.w(TAG, "清 cookie 失败: " + t.getMessage());
        }
        try {
            DshPreloadWeb.invalidate();
        } catch (Throwable t) {
            Log.w(TAG, "作废预热失败: " + t.getMessage());
        }
    }
}
