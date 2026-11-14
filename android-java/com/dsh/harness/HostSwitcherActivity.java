package com.dsh.harness;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 主机切换器（原生页面 3/3）—— 列出这台手机能切的电脑，并执行切换。
 *
 * <h3>为什么要有这一页</h3>
 * 手机连过的电脑通常不止一台（家里 / 公司）。在此之前切换只能靠改配置，
 * 而协议侧早就有了 `GET /api/hosts`（可切清单 + `bound` 标记）与
 * `POST /api/hosts/select`（r19）。这里只是把数据面接到界面。
 *
 * <h3>三条不能省的语义</h3>
 * <ol>
 *   <li><b>确认</b>：切换不带走会话、文件与插件。界面上确认一次，请求里还必须带
 *       {@code confirmNoMigration:true} —— 服务端要的是参数，不是态度。</li>
 *   <li><b>切换后必须归零</b>：清 cookie + 作废预热（{@link ProfileSession}），
 *       否则上一台主机的反代会话 cookie 会带进下一台（R-22 串会话）。</li>
 *   <li><b>令牌不进界面</b>：本页只把令牌交给 {@link DshHostsClient} 拼请求头，
 *       不显示、不打日志、不写进列表项。</li>
 * </ol>
 *
 * <h3>离线也允许切</h3>
 * 目标电脑离线不是拒绝理由（协议就是这么定的）：切过去看到的是离线页，
 * 用户至少知道「那台机器没开」。只有「不存在 / 已收回」才拒绝。
 */
public class HostSwitcherActivity extends Activity {

    private TextView statusView;
    private LinearLayout listBox;
    private Button refreshButton;
    private String relayOrigin;
    private String token;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        relayOrigin = DshDeviceIdentity.lastRelayUrl(this);
        token = DshTokenStore.load(this);
        buildUi();
        reload();
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
        title.setText("切换电脑");
        title.setTextColor(fg);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(title, matchWrap());

        TextView lead = new TextView(this);
        lead.setText("切换主机不会带走会话、文件与插件；切过去之后看到的是那台电脑自己的内容。");
        lead.setTextColor(muted);
        lead.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        lead.setLineSpacing(dp(2), 1f);
        root.addView(lead, marginTop(matchWrap(), 8));

        statusView = new TextView(this);
        statusView.setTextColor(fg);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        statusView.setLineSpacing(dp(2), 1f);
        root.addView(statusView, marginTop(matchWrap(), 16));

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(listBox, marginTop(matchWrap(), 12));

        refreshButton = new Button(this);
        refreshButton.setText("刷新");
        refreshButton.setAllCaps(false);
        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                reload();
            }
        });
        root.addView(refreshButton, marginTop(matchWrap(), 18));

        Button pairButton = new Button(this);
        pairButton.setText("重新配对");
        pairButton.setAllCaps(false);
        pairButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(HostSwitcherActivity.this, PairActivity.class));
            }
        });
        root.addView(pairButton, marginTop(matchWrap(), 8));

        // SSH 档不走 Relay，也就不需要配对码 —— 它的入口不能只挂在配对流程里，
        // 否则「只想连一台能 ssh 的机器」的人得先假装配一次对。
        Button sshButton = new Button(this);
        sshButton.setText("连 SSH 主机");
        sshButton.setAllCaps(false);
        sshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(SshConfigActivity.intent(HostSwitcherActivity.this));
            }
        });
        root.addView(sshButton, marginTop(matchWrap(), 8));

        Button localButton = new Button(this);
        localButton.setText("改用本机引擎");
        localButton.setAllCaps(false);
        localButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 闭环：从本页进来的人也能从这里回到本机，不用绕回选择页
                HostProfile hp = HostProfile.get(HostSwitcherActivity.this);
                if (!hp.setCurrent(HostProfile.KIND_LOCAL)) {
                    statusView.setText("切回本机失败：档案状态异常，重启应用后再试。");
                    return;
                }
                ProfileSession.resetRuntime();
                startActivity(new Intent(HostSwitcherActivity.this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
                finish();
            }
        });
        root.addView(localButton, marginTop(matchWrap(), 8));

        Button backButton = new Button(this);
        backButton.setText("返回");
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

    /** 拉清单（后台线程）。令牌缺失时不去试空 Bearer。 */
    private void reload() {
        if (relayOrigin == null) {
            statusView.setText("还没配对过：先点下面的「重新配对」。");
            refreshButton.setEnabled(false);
            return;
        }
        if (token == null) {
            statusView.setText("本机令牌不可用（可能被清除或来自别的设备）。重新配对一次。");
            refreshButton.setEnabled(false);
            return;
        }
        refreshButton.setEnabled(false);
        statusView.setText("正在读取电脑列表…");
        listBox.removeAllViews();

        new Thread(new Runnable() {
            @Override
            public void run() {
                final DshHostsClient.Result r = DshHostsClient.list(relayOrigin, token);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        render(r);
                    }
                });
            }
        }, "dsh-hosts").start();
    }

    private void render(DshHostsClient.Result r) {
        refreshButton.setEnabled(true);
        listBox.removeAllViews();
        if (!r.ok()) {
            HostsError.Finding f = r.finding();
            statusView.setText(f.title + "：" + f.action);
            return;
        }
        List<DshHostsClient.Host> rows = r.rows;
        if (rows == null || rows.isEmpty()) {
            statusView.setText("这台手机还看不到任何电脑。确认电脑端在运行并已连上同一个 Relay。");
            return;
        }
        statusView.setText("共 " + rows.size() + " 台电脑");
        for (final DshHostsClient.Host h : rows) {
            listBox.addView(rowView(h), marginTop(matchWrap(), 8));
        }
    }

    /** 单行：名字 + 摘要 + 当前标记；整行可点。 */
    private View rowView(final DshHostsClient.Host h) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(12), dp(12), dp(12));
        box.setBackground(roundRect(DshPalette.surface(this), dp(10)));

        TextView name = new TextView(this);
        name.setText(h.name == null || h.name.isEmpty() ? h.hostId : h.name);
        name.setTextColor(DshPalette.text(this));
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        box.addView(name, matchWrap());

        TextView detail = new TextView(this);
        detail.setText(summaryOf(h));
        detail.setTextColor(DshPalette.muted(this));
        detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        detail.setLineSpacing(dp(2), 1f);
        box.addView(detail, marginTop(matchWrap(), 4));

        box.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (h.bound) {
                    // 已是当前主机：不弹确认、不发请求（同主机 select 是 no-op）
                    statusView.setText(h.hostId + " 已经是当前电脑");
                    return;
                }
                confirmSwitch(h);
            }
        });
        return box;
    }

    private String summaryOf(DshHostsClient.Host h) {
        StringBuilder sb = new StringBuilder();
        sb.append(h.online ? "在线" : "离线");
        if (h.platform != null && !h.platform.isEmpty()) sb.append(" · ").append(h.platform);
        if (h.sessionCount > 0) sb.append(" · ").append(h.sessionCount).append(" 个会话");
        if ("safe-mode".equals(h.engineState)) sb.append(" · 引擎安全模式");
        if (h.bound) sb.append(" · 当前使用中");
        sb.append('\n').append(h.hostId);
        return sb.toString();
    }

    /**
     * 确认后切换。确认框文案与服务端 `notice` 同一句：切换不会带走任何东西。
     */
    private void confirmSwitch(final DshHostsClient.Host h) {
        String label = h.name == null || h.name.isEmpty() ? h.hostId : h.name;
        new AlertDialog.Builder(this)
                .setTitle("切换到「" + label + "」？")
                .setMessage("切换主机不会带走会话、文件与插件。\n"
                        + "切过去之后，界面会重建并显示这台电脑自己的内容。")
                .setNegativeButton("取消", null)
                .setPositiveButton("切换", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        doSwitch(h);
                    }
                })
                .show();
    }

    private void doSwitch(final DshHostsClient.Host h) {
        statusView.setText("正在切换到 " + h.hostId + "…");
        refreshButton.setEnabled(false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final DshHostsClient.Result r = DshHostsClient.select(relayOrigin, token, h.hostId);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        onSwitchResult(r, h);
                    }
                });
            }
        }, "dsh-host-switch").start();
    }

    private void onSwitchResult(DshHostsClient.Result r, DshHostsClient.Host h) {
        refreshButton.setEnabled(true);
        if (!r.ok()) {
            HostsError.Finding f = r.finding();
            statusView.setText(f.title + "：" + f.action);
            return;
        }
        // 服务端已重绑（令牌不变、绑定变）。本地档案要跟上，否则重启又回到旧主机。
        String bound = r.hostId != null ? r.hostId : h.hostId;
        HostProfile hp = HostProfile.get(this);
        hp.setRelay(relayOrigin, bound, token);
        if (!hp.setCurrent(HostProfile.KIND_RELAY)) {
            statusView.setText("切换已生效，但本地档案没保存成功；重启后可能需要再切一次。");
            return;
        }
        ProfileSession.resetRuntime();
        Intent i = new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_ENTER_REMOTE, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(i);
        finish();
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
