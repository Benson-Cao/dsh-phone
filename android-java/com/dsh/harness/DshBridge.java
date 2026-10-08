package com.dsh.harness;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

/**
 * 注入页面的 {@code window.dshNative} —— 原生能力桥。
 *
 * 目前只暴露 dshmarket（DSH 插件市场）安装：
 * 插件必须落到 {@code $HOME/.dsh/profiles/web/node_modules/} 并注册进 profile
 * manifest 的 {@code dsh.profile.bundles}，WebView 里的 JS 没有文件系统权限，
 * 所以只能由原生做。
 *
 * ⚠️ 安全：{@code @JavascriptInterface} 只在**已加载我们信任的页面**时可达
 * （WebView 只 load 127.0.0.1:3080 的 dsh 实例），所以这里不额外做 origin 校验。
 * 若将来要加载远程页面，必须补上域名白名单。
 */
public final class DshBridge {

    private static final String TAG = "DshBridge";
    private final Activity act;

    public DshBridge(Activity act) {
        this.act = act;
    }

    /** 已安装则返回版本号，否则返回空串。同步、极快。 */
    @JavascriptInterface
    public String marketInstalled() {
        try {
            String v = DshMarketInstaller.installedVersion(act);
            return v == null ? "" : v;
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 拉起安装（后台线程执行，完成后回调 JS）。
     * JS 侧传入的 callbackId 会被 {@code window.__dshMarketDone(id, ok, msg)} 接收。
     */
    @JavascriptInterface
    public void installMarket(final String callbackId) {
        new Thread(new Runnable() {
            @Override public void run() {
                String err;
                try {
                    err = DshMarketInstaller.install(act);
                } catch (Throwable e) {
                    Log.e(TAG, "安装 dshmarket 异常", e);
                    err = "安装异常: " + e.getMessage();
                }
                final String result = err;
                final boolean ok = (result == null);
                final String msg = ok ? "安装完成，重启应用后生效" : result;
                final String ver = ok ? DshMarketInstaller.installedVersion(act) : "";
                if (ok) Log.i(TAG, "dshmarket 安装成功 " + ver);
                else Log.e(TAG, "dshmarket 安装失败: " + err);
                act.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try {
                            JSONObject o = new JSONObject();
                            o.put("ok", ok);
                            o.put("message", msg);
                            o.put("version", ver == null ? "" : ver);
                            final String js = "window.__dshMarketDone&&window.__dshMarketDone("
                                + JSONObject.quote(callbackId) + "," + o.toString() + ");";
                            android.webkit.WebView w = ((MainActivity) act).webViewRef();
                            if (w != null) w.evaluateJavascript(js, null);
                        } catch (Exception e) {
                            Log.e(TAG, "回调 JS 失败", e);
                        }
                    }
                });
            }
        }, "dsh-market-install").start();
    }
}