package com.dsh.harness;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import java.util.List;

/**
 * 省电策略放行 —— 让 dsh 引擎在后台活下来。
 *
 * <h3>为什么必须由用户手动放行</h3>
 * 国产 ROM（MIUI / EMUI / ColorOS / OriginOS …）会杀掉后台进程，但我们**不能**
 * 用 `SYSTEM_ALERT_WINDOW` 或自启动壳去对抗 —— 那是恶意软件手法，也会被商店下架。
 * 正确做法是走系统标准通道：把「是否已放行」摆到界面上，并提供一次跳转。
 *
 * <h3>三层降级</h3>
 * <ol>
 *   <li>{@code ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS}（API 23+，带包名）：
 *       直接弹系统对话框，一步放行 —— 前提是清单里有
 *       {@code REQUEST_IGNORE_BATTERY_OPTIMIZATIONS}（已有）；</li>
 *   <li>该 action 被 ROM 摘掉（部分 ROM 真这么干）→ 退到
 *       {@code ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS}（列表页，用户自己找）；</li>
 *   <li>再不行 → 退到本应用详情页，至少能到「电池」入口附近。</li>
 * </ol>
 *
 * <p>注意：`isIgnoringBatteryOptimizations` 只反映**系统 Doze 白名单**，
 * 国产 ROM 的「后台限制 / 自启动」是另一套开关，这里不谎报「已放行」，
 * 只在文案里点明「系统省电白名单」，避免用户以为万事大吉。
 */
public final class DshPowerGuard {

    private DshPowerGuard() {}

    /** 是否已在系统省电白名单里（API < 23 一律视为未能判定 → 返回 false）。 */
    public static boolean isExempt(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 构造放行跳转（不直接 startActivity，便于调用方统一做异常兜底）。
     * 逐层降级，永远返回一个可用的 Intent。
     */
    public static Intent requestIntent(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent direct = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            direct.setData(Uri.parse("package:" + ctx.getPackageName()));
            if (resolve(ctx, direct)) return direct;

            Intent list = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            if (resolve(ctx, list)) return list;
        }
        return appDetails(ctx);
    }

    /** 应用详情页（最后的兜底）。 */
    public static Intent appDetails(Context ctx) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        i.setData(Uri.parse("package:" + ctx.getPackageName()));
        return i;
    }

    /** 给界面用的一句话状态。 */
    public static String summary(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return "当前系统版本无法查询省电白名单（Android 5.x）";
        }
        return isExempt(ctx)
                ? "已加入系统省电白名单"
                : "未加入省电白名单：息屏后引擎可能被杀，回到前台要重新冷启";
    }

    private static boolean resolve(Context ctx, Intent intent) {
        try {
            PackageManager pm = ctx.getPackageManager();
            List<ResolveInfo> hits = pm.queryIntentActivities(intent, 0);
            return hits != null && !hits.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}
