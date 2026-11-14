package com.dsh.harness.webview;

import android.util.Log;
import android.webkit.WebView;

import com.dsh.harness.HostProfile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 官方外壳注入器（v34）。
 *
 * <p>取代 r152 之前的 {@code MobileTuning.apply()}（mobile.css / mobile.js /
 * market.css 四件套）。按《WebView 完整技术手册》§6 与用户要求「首页与官方
 * WebView 一模一样」：**不注入任何样式/布局**，只加载
 * {@code assets/dsh/official-bridge.js}（设置入口拦截 + 返回状态上报）。
 *
 * <p>r225 三引擎切换：额外注入独立的 {@code engine-switcher.js}。
 * ⚠️ **official-bridge.js 原文一个字节都不改** —— r217~r223 的布局修复全在
 * 那 113KB 里，历史上拿 r215 当基线重打包导致过一次整体降级，不再重演。
 */
public final class OfficialShell {

    private static final String TAG = "OfficialShell";

    /** 顺序有意义：官方桥先建立，切换 UI 后挂。 */
    private static final String[] ASSETS = {
        "dsh/official-bridge.js",
        "dsh/engine-switcher.js"
    };

    private static final Map<String, String> CACHE = new HashMap<>();

    private OfficialShell() { }

    /** 幂等：两个脚本各自带守卫（__dshOfficialBridge / __dshEngineSwitcher）。 */
    public static void inject(WebView view) {
        if (view == null) { return; }
        for (String asset : ASSETS) {
            try {
                String js = load(view, asset);
                if (js == null) { continue; }
                view.evaluateJavascript(js, null);
            } catch (Throwable t) {
                // 注入是体验增强，绝不影响 dsh 主流程
                Log.w(TAG, "[dsh] inject 失败 " + asset + ": " + t.getMessage());
            }
        }
    }

    /**
     * v2.3 批 B：**按当前档案决定是否注入** 的统一入口。
     *
     * <p>relay 档的页面是<b>远端 origin</b>（Relay 反代官方 UI）—— 桥对象不存在，
     * official-bridge.js 里对 {@code window.dshNative} 的守卫虽会让脚本空转，
     * 但「往远端页面里注我们自己的 JS」这件事本身就该杜绝：脚本可能随版本演进
     * 引入新的桥调用，不能指望 JS 侧守卫永远是 no-op。
     * 档案判定失败同样不注入（fail-safe：少一个功能，不多一个洞）。
     */
    public static void injectIfAllowed(WebView view) {
        if (view == null) { return; }
        try {
            HostProfile.Profile p = HostProfile.get(view.getContext()).current();
            if (!p.bridgeAllowed()) {
                Log.i(TAG, "[dsh] relay 档（远端 origin）不注入脚本");
                return;
            }
        } catch (Throwable t) {
            Log.w(TAG, "[dsh] 档案判定失败，保守不注入: " + t.getMessage());
            return;
        }
        inject(view);
    }

    private static synchronized String load(WebView view, String asset) {
        String s = CACHE.get(asset);
        if (s != null) { return s; }
        try (InputStream in = view.getContext().getAssets().open(asset)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) { bos.write(buf, 0, n); }
            s = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            CACHE.put(asset, s);
            return s;
        } catch (Throwable t) {
            Log.w(TAG, "[dsh] 读取 " + asset + " 失败: " + t.getMessage());
            return null;
        }
    }
}
