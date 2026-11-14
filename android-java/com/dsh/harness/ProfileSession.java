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

    /**
     * 切档后**重新进入主界面**：先清空本任务的返回栈，再以全新实例启动。
     *
     * <p>为什么不能只写 `startActivity(MainActivity …CLEAR_TOP)`：CLEAR_TOP 命中已有的
     * MainActivity 时只走到 `onNewIntent`，**`onCreate` 不执行** —— 主界面在 onCreate 里读
     * {@code HostProfile.current()} 决定连哪台，于是它继续用**切档前**的档案快照。
     *
     * <p>这不是理论问题：真机上「连 SSH 主机 → 填令牌 → 保存并连接」时，主界面早已在栈里，
     * 结果隧道用的是旧档案（host 对、**令牌空**），WebView 拿到 401，界面报「连不上这台电脑」。
     * 同一缺陷对「切换到其它电脑」「改用本机引擎」同样成立。
     *
     * <p>{@code finishAffinity()} + `NEW_TASK|CLEAR_TASK` 的效果是：旧实例与返回栈一起消失，
     * 新实例 onCreate 重新读档案 —— 与用户「重开应用」语义一致，且不会在栈里留下一个
     * 仍显示旧主机的僵尸页。
     */
    public static void restartInto(android.app.Activity from, android.content.Intent target) {
        try {
            from.finishAffinity();
        } catch (Throwable t) {
            Log.w(TAG, "finishAffinity 失败: " + t.getMessage());
        }
        try {
            target.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
            from.startActivity(target);
        } catch (Throwable t) {
            Log.w(TAG, "重启主界面失败: " + t.getMessage());
        }
    }
}
