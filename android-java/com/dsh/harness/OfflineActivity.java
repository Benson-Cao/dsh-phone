package com.dsh.harness;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * PC 离线页（原生页面 2/3）—— 电脑不可达时的**有信息量的**停留页。
 *
 * <h3>为什么需要这一页</h3>
 * 手机连不上电脑时，官方 Web UI 只会停在白屏或一个转圈。用户无法判断是
 * 「地址写错了」「电脑休眠了」「电脑上的 Relay 没起」还是「自己被收回了」。
 * 这四种情况的下一步完全不同，所以必须给状态 + 可执行的下一步，而不是一个错误码。
 *
 * <h3>判定口径</h3>
 * <ul>
 *   <li>TCP 连不上 → 地址/网络问题；</li>
 *   <li>连上且 200，`online == 0` → 地址对、Relay 活着，但**电脑没连上来**；</li>
 *   <li>连上但非 200 → Relay 活着但状态异常（把状态码照实显示）；</li>
 *   <li>没有绑定记录 → 还没配过，直接引导去配对页。</li>
 * </ul>
 *
 * <p>探活走免鉴权的 `/health`：本页正是在「令牌可能无效」时使用，
 * 依赖鉴权端点会让本页在真正需要它的时候失灵。
 */
public class OfflineActivity extends Activity {

    private TextView stateView;
    private TextView detailView;
    private TextView bindingView;
    private Button recheckButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        check();
    }

    @Override
    protected void onResume() {
        super.onResume();
        check();
    }

    private void buildUi() {
        int bg = DshPalette.bg(this);
        int fg = DshPalette.text(this);
        int muted = DshPalette.muted(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(bg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(28), pad, dp(28));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("电脑未连接");
        title.setTextColor(fg);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(title, matchWrap());

        stateView = new TextView(this);
        stateView.setText("正在检查…");
        stateView.setTextColor(fg);
        stateView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        root.addView(stateView, marginTop(matchWrap(), 14));

        detailView = new TextView(this);
        detailView.setTextColor(muted);
        detailView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        detailView.setLineSpacing(dp(2), 1f);
        root.addView(detailView, marginTop(matchWrap(), 6));

        bindingView = new TextView(this);
        bindingView.setTextColor(muted);
        bindingView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        bindingView.setLineSpacing(dp(3), 1f);
        bindingView.setPadding(dp(12), dp(12), dp(12), dp(12));
        bindingView.setBackground(roundRect(DshPalette.surface(this), dp(10)));
        root.addView(bindingView, marginTop(matchWrap(), 20));

        recheckButton = new Button(this);
        recheckButton.setText("重新检查");
        recheckButton.setAllCaps(false);
        recheckButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                check();
            }
        });
        root.addView(recheckButton, marginTop(matchWrap(), 18));

        Button pairButton = new Button(this);
        pairButton.setText("重新配对");
        pairButton.setAllCaps(false);
        pairButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(OfflineActivity.this, PairActivity.class));
            }
        });
        root.addView(pairButton, marginTop(matchWrap(), 8));

        Button powerButton = new Button(this);
        powerButton.setText("后台保活设置");
        powerButton.setAllCaps(false);
        powerButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(DshPowerGuard.requestIntent(OfflineActivity.this));
                } catch (Exception ignored) {
                    // 页面本身已说明「去系统设置里手动放行」，这里不重复报错
                }
            }
        });
        root.addView(powerButton, marginTop(matchWrap(), 8));

        Button backButton = new Button(this);
        backButton.setText("返回应用");
        backButton.setAllCaps(false);
        backButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        root.addView(backButton, marginTop(matchWrap(), 8));

        setContentView(scroll);
        try {
            getWindow().setStatusBarColor(DshPalette.systemBar(this));
            getWindow().getDecorView().setSystemUiVisibility(
                    DshPalette.systemUiFlags(this, getWindow().getDecorView().getSystemUiVisibility()));
        } catch (Throwable ignored) {
        }
    }

    /** 后台探活 + 刷新文案。 */
    private void check() {
        final String relay = DshDeviceIdentity.lastRelayUrl(this);
        final String host = DshDeviceIdentity.lastHostDeviceId(this);
        final long at = DshDeviceIdentity.lastPairedAt(this);

        bindingView.setText(bindingText(relay, host, at));

        if (relay == null) {
            stateView.setText("还没有配对过");
            detailView.setText("先在电脑上生成配对码，再点下面的「重新配对」输入。");
            recheckButton.setEnabled(false);
            return;
        }
        recheckButton.setEnabled(false);
        stateView.setText("正在检查…");
        detailView.setText("");

        new Thread(new Runnable() {
            @Override
            public void run() {
                final DshPairClient.Health h = DshPairClient.health(relay);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        render(h, relay);
                    }
                });
            }
        }, "dsh-health").start();
    }

    private void render(DshPairClient.Health h, String relay) {
        recheckButton.setEnabled(true);
        if (!h.reachable) {
            stateView.setText("连不上 " + relay);
            detailView.setText("地址不通。依次确认：\n"
                    + "1) 手机与电脑在同一网络（同一 Wi-Fi，或电脑开了热点）；\n"
                    + "2) 电脑上的 Relay 正在运行；\n"
                    + "3) 地址与端口没写错（可在电脑端配对码页面看到）。");
            return;
        }
        if (h.status != 200) {
            stateView.setText("Relay 返回 " + h.status);
            detailView.setText("地址是通的，但服务没有正常响应。看电脑端 Relay 日志。");
            return;
        }
        if (h.online == 0) {
            stateView.setText("地址通，但没有电脑在线");
            detailView.setText("Relay 正在运行，是电脑本体没连上来。\n"
                    + "确认电脑上的 dsh 客户端在运行（托盘图标），再刷新。");
            return;
        }
        stateView.setText("电脑在线（" + h.online + " 台）");
        detailView.setText("连接已恢复：返回应用即可继续。若仍显示离线，"
                + "说明是这台手机与电脑的绑定关系需要重新配对。");
    }

    private String bindingText(String relay, String host, long at) {
        if (relay == null && host == null) {
            return "未绑定任何电脑";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("上次配对\n");
        sb.append("电脑　").append(host == null ? "—" : host).append('\n');
        sb.append("地址　").append(relay == null ? "—" : relay);
        if (at > 0) {
            sb.append('\n').append("时间　").append(DateFormat.format("yyyy-MM-dd HH:mm", at));
        }
        sb.append('\n').append("省电　").append(DshPowerGuard.isExempt(this) ? "已放行" : "未放行");
        return sb.toString();
    }

    // ---- 小工具 ----

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams marginTop(LinearLayout.LayoutParams lp, int dp) {
        lp.topMargin = dp(dp);
        return lp;
    }

    private GradientDrawable roundRect(int fill, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius);
        d.setStroke(1, DshPalette.isDark(this) ? 0xFF2C3846 : 0xFFE2E8F0);
        return d;
    }
}
