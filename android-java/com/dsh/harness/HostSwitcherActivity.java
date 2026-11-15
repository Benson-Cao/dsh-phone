package com.dsh.harness;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
 *
 * <h3>v2 设计稿（06 主机切换器）与本页的四处偏离</h3>
 * <ol>
 *   <li><b>「正在使用」不用 {@code bound} 冒充</b>：{@code bound} 是 Relay 侧的
 *       <i>归属</i>（这台手机指向哪台电脑），档案停在本机时它照样为真（r124 修过文案）。
 *       这里只在「档案确实是 relay 且 hostId 相同」时打「正在使用」，
 *       其余 bound 条目打「绑定目标」。</li>
 *   <li><b>心跳时间点不显示</b>：接口只给 online 布尔值，没有「3 秒前」这种时间戳。
 *       编一个出来比不显示更糟 —— 那会让人以为链路比实际更健康。</li>
 *   <li><b>「连接诊断」不是第三方页面</b>：设计稿 05 屏还没有原生实现，
 *       所以诊断内容以对话框形式给出**本机真实值**（执行位置 / 入口 / 令牌有无 /
 *       保活放行 / 设备指纹），不伪造心跳与丢包数。</li>
 *   <li><b>保留「连 SSH 主机」</b>：设计稿把 SSH 收进「添加电脑」的三段 Tab，
 *       而三段 Tab 的落地页是 {@link PairActivity}。若把 SSH 入口藏到配对流程里，
 *       只想连一台能 ssh 的机器的人得先假装配一次对 —— r123 正是为修这个才加的入口。</li>
 * </ol>
 */
public class HostSwitcherActivity extends Activity {

    private TextView statusView;
    private TextView hostsSection;
    private LinearLayout listBox;
    private TextView refreshButton;
    private LinearLayout localCard;
    private TextView diagBar;

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

    // ---- UI ----

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(DshUi.pageBg(this));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = DshUi.dp(this, 18);
        root.setPadding(pad, DshUi.dp(this, 12), pad, DshUi.dp(this, 20));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 应用栏：返回 + 标题 + 刷新 ----
        refreshButton = DshUi.roundButton(this, "↻", new View.OnClickListener() {
            @Override public void onClick(View v) { reload(); }
        });
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = DshUi.roundButton(this, "‹", new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(
                DshUi.dp(this, 34), DshUi.dp(this, 34));
        backLp.rightMargin = DshUi.dp(this, 10);
        bar.addView(back, backLp);
        bar.addView(DshUi.label(this, "切换电脑", 15, DshUi.ink(this), true));
        View barSpacer = new View(this);
        bar.addView(barSpacer, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(refreshButton);
        root.addView(bar, DshUi.matchWrap());

        // ---- 标题与导语 ----
        TextView h1 = DshUi.label(this, "在哪台设备上继续？", 23, DshUi.ink(this), true);
        root.addView(h1, DshUi.matchWrapTop(this, 10));
        root.addView(DshUi.label(this,
                "会话、文件与设置留在各自设备，切换不会带走。",
                12.5f, DshUi.ink2(this), false), DshUi.matchWrapTop(this, 7));

        // ---- 状态行：只在有话说时占位（加载中 / 出错 / 提示）----
        statusView = DshUi.label(this, "", 11.5f, DshUi.ink2(this), false);
        statusView.setVisibility(View.GONE);
        root.addView(statusView, DshUi.matchWrapTop(this, 10));

        // ---- 已绑定：远端电脑清单 ----
        hostsSection = DshUi.section(this, "已绑定");
        root.addView(hostsSection, DshUi.matchWrapTop(this, 14));
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(listBox, DshUi.matchWrapTop(this, 2));

        // ---- 本机 ----
        root.addView(DshUi.section(this, "本机"), DshUi.matchWrapTop(this, 16));
        localCard = DshUi.cardBox(this);
        localCard.addView(localRow());
        root.addView(localCard, DshUi.matchWrapTop(this, 2));

        // ---- 诊断条（点按展开真实诊断）----
        diagBar = DshUi.diagBar(this, diagText());
        diagBar.setClickable(true);
        diagBar.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showDiagnostics(); }
        });
        DshUi.pressable(diagBar);
        root.addView(diagBar, DshUi.matchWrapTop(this, 18));

        // ---- 主按钮 + 两个次按钮 + 返回 ----
        TextView add = DshUi.button(this, "添加电脑", DshUi.BTN_PRIMARY);
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(HostSwitcherActivity.this, PairActivity.class));
            }
        });
        root.addView(add, DshUi.matchWrapTop(this, 12));

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(btnRow, DshUi.matchWrapTop(this, 9));

        TextView localBtn = DshUi.button(this, "改用本机引擎", DshUi.BTN_SECONDARY);
        localBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { useLocalEngine(); }
        });
        btnRow.addView(localBtn, DshUi.half(this, true));

        TextView sshBtn = DshUi.button(this, "连 SSH 主机", DshUi.BTN_SECONDARY);
        sshBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(SshConfigActivity.intent(HostSwitcherActivity.this));
            }
        });
        btnRow.addView(sshBtn, DshUi.half(this, false));

        TextView backBtn = DshUi.button(this, "返回应用", DshUi.BTN_GHOST);
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        root.addView(backBtn, DshUi.matchWrapTop(this, 2));

        setContentView(scroll);
        try {
            getWindow().setStatusBarColor(DshPalette.systemBar(this));
            getWindow().getDecorView().setSystemUiVisibility(
                    DshPalette.systemUiFlags(this, getWindow().getDecorView().getSystemUiVisibility()));
        } catch (Throwable ignored) {
        }
    }

    /** 本机那一行：薄荷渐变头像 + 说明 + 状态标签。整行可点。 */
    private View localRow() {
        HostProfile hp = HostProfile.get(this);
        boolean isLocal = HostProfile.KIND_LOCAL.equals(hp.currentId());
        LinearLayout row = DshUi.rowBox(this);
        row.addView(DshUi.avatar(this, "本", DshUi.MINT, DshUi.MINT_2), avatarLp());
        row.addView(deviceColumn("这台手机",
                "Termux · 原版 dsh web · 独立会话",
                "本机档不连任何电脑，会话只留在这台手机上。"));
        if (isLocal) {
            row.addView(DshUi.tag(this, "正在使用", DshUi.soft(this, DshUi.SOFT_MINT, 0xFF12403A),
                    DshUi.MINT, DshUi.MINT));
        } else {
            row.addView(DshUi.tag(this, "可切换", DshUi.soft(this, DshUi.SOFT_BLUE, 0xFF1E2547),
                    DshUi.BRAND, 0));
        }
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { useLocalEngine(); }
        });
        DshUi.pressable(row);
        return row;
    }

    /** 拉清单（后台线程）。令牌缺失时不去试空 Bearer。 */
    private void reload() {
        diagBar.setText(diagText());
        if (relayOrigin == null) {
            showStatus("还没配对过：点「添加电脑」填地址和配对码。", true);
            setRefreshEnabled(false);
            return;
        }
        if (token == null) {
            showStatus("本机令牌不可用（可能被清除或来自别的设备）。重新配对一次。", true);
            setRefreshEnabled(false);
            return;
        }
        setRefreshEnabled(false);
        showStatus("正在读取电脑列表…", false);
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

    /** @param enabled false 时同时压暗，避免「点了没反应」的错觉（TextView 无原生禁用态） */
    private void setRefreshEnabled(boolean enabled) {
        refreshButton.setEnabled(enabled);
        refreshButton.setAlpha(enabled ? 1f : 0.45f);
    }

    private void render(DshHostsClient.Result r) {
        setRefreshEnabled(true);
        listBox.removeAllViews();
        if (!r.ok()) {
            HostsError.Finding f = r.finding();
            hostsSection.setText("已绑定");
            showStatus(f.title + "：" + f.action, true);
            return;
        }
        List<DshHostsClient.Host> rows = r.rows;
        if (rows == null || rows.isEmpty()) {
            hostsSection.setText("已绑定");
            showStatus("这台手机还看不到任何电脑。确认电脑端在运行并已连上同一个 Relay。", true);
            return;
        }
        hostsSection.setText("已绑定 · " + rows.size());
        showStatus("", false);
        for (int i = 0; i < rows.size(); i++) {
            DshHostsClient.Host h = rows.get(i);
            listBox.addView(hostRow(h), DshUi.matchWrapTop(this, i == 0 ? 2 : 0));
            if (i < rows.size() - 1) listBox.addView(DshUi.divider(this));
        }
    }

    /** 远端电脑一行：渐变头像 + 名字/摘要 + 状态标签；整行可点。 */
    private View hostRow(final DshHostsClient.Host h) {
        LinearLayout row = DshUi.rowBox(this);
        row.addView(DshUi.avatar(this, avatarChar(h), DshUi.BRAND, DshUi.BRAND_2), avatarLp());
        row.addView(deviceColumn(displayName(h), metaOf(h), h.hostId));
        row.addView(stateTag(h));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (h.bound) {
                    // 已是绑定目标：不弹确认、不发请求（同主机 select 是 no-op）
                    showStatus(displayName(h) + " 已经是这台手机的绑定目标"
                            + "（归属，不代表此刻正在使用）。", false);
                    return;
                }
                confirmSwitch(h);
            }
        });
        DshUi.pressable(row);
        return row;
    }

    /**
     * 中间两行：名字 13.5 加粗 + 摘要 10.5 muted。
     * 名字过长时折行而不是截断 —— 两台电脑名字前缀相同的情况很常见。
     */
    private LinearLayout deviceColumn(String name, String meta, String idLine) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        col.setLayoutParams(lp);
        col.addView(DshUi.label(this, name, 13.5f, DshUi.ink(this), true));
        TextView m = DshUi.label(this, meta, 10.5f, DshUi.ink2(this), false);
        col.addView(m, DshUi.matchWrapTop(this, 3));
        if (idLine != null && idLine.length() > 0 && !idLine.equals(name)) {
            TextView id = DshUi.label(this, idLine, 10, DshUi.ink3(this), false);
            id.setTypeface(Typeface.MONOSPACE);
            col.addView(id, DshUi.matchWrapTop(this, 2));
        }
        return col;
    }

    /**
     * 状态标签。「正在使用」只在档案确实是 relay 且 hostId 相同时成立；
     * {@code bound} 单独给「绑定目标」——两者混用会把归属读成实时状态（r124）。
     */
    private View stateTag(DshHostsClient.Host h) {
        HostProfile.Profile cur = HostProfile.get(this).current();
        boolean inUse = HostProfile.KIND_RELAY.equals(cur.kind)
                && cur.hostId != null && cur.hostId.equals(h.hostId);
        if (inUse) {
            return DshUi.tag(this, "正在使用", DshUi.soft(this, DshUi.SOFT_MINT, 0xFF12403A),
                    DshUi.MINT, DshUi.MINT);
        }
        if (h.bound) {
            return DshUi.tag(this, "绑定目标", DshUi.soft(this, DshUi.SOFT_BLUE, 0xFF1E2547),
                    DshUi.BRAND, 0);
        }
        if (!h.online) {
            return DshUi.tag(this, "离线", DshUi.soft(this, DshUi.SOFT_GRAY, 0xFF2A323F),
                    DshUi.dark(this) ? DshUi.INK_3 : DshUi.ON_GRAY, 0);
        }
        return DshUi.tag(this, "在线", DshUi.soft(this, DshUi.SOFT_MINT, 0xFF12403A),
                DshUi.MINT, DshUi.MINT);
    }

    /** 摘要行：在线态 + 会话数 + 引擎态（不含时间戳 —— 接口没有）。 */
    private String metaOf(DshHostsClient.Host h) {
        StringBuilder sb = new StringBuilder();
        sb.append(h.online ? "在线" : "离线");
        if (h.sessionCount > 0) sb.append(" · ").append(h.sessionCount).append(" 个会话");
        if ("safe-mode".equals(h.engineState)) sb.append(" · 引擎安全模式");
        if (h.platform != null && !h.platform.isEmpty()) sb.append(" · ").append(h.platform);
        return sb.toString();
    }

    private String displayName(DshHostsClient.Host h) {
        return h.name == null || h.name.isEmpty() ? h.hostId : h.name;
    }

    /** 头像里的字：名字首个字符；没有名字时退回 hostId。 */
    private String avatarChar(DshHostsClient.Host h) {
        String n = displayName(h);
        return n == null || n.isEmpty() ? "?" : n.substring(0, 1);
    }

    private LinearLayout.LayoutParams avatarLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                DshUi.dp(this, 38), DshUi.dp(this, 38));
        lp.rightMargin = DshUi.dp(this, 11);
        return lp;
    }

    // ---- 状态条 ----

    /**
     * 状态行：出错用琥珀底，提示用透明底。
     *
     * <p>之所以不并进诊断条：诊断条是「链路体检」的常驻信息，
     * 而这里说的是「刚才那次操作怎么了」，两者混在一起会互相淹没。
     */
    private void showStatus(String msg, boolean error) {
        if (msg == null || msg.isEmpty()) {
            statusView.setVisibility(View.GONE);
            return;
        }
        statusView.setVisibility(View.VISIBLE);
        statusView.setText(msg);
        statusView.setTextColor(error
                ? (DshUi.dark(this) ? 0xFFE8C07A : DshUi.ON_AMBER)
                : DshUi.ink2(this));
        statusView.setBackground(error
                ? DshUi.pillDrawable(this, DshUi.dark(this) ? 0xFF3A3020 : DshUi.SOFT_AMBER)
                : null);
        int h = DshUi.dp(this, error ? 10 : 4);
        int v = DshUi.dp(this, error ? 9 : 2);
        statusView.setPadding(h, v, h, v);
    }

    // ---- 诊断 ----

    /** 诊断条文本：全部取本机真实值，不含任何令牌。 */
    private String diagText() {
        HostProfile hp = HostProfile.get(this);
        String path = hp.currentId();
        String keep = DshPowerGuard.isExempt(this) ? "保活已放行" : "保活未放行";
        String origin = relayOrigin == null ? "Relay 未记录" : "Relay " + relayOrigin;
        String tok = token == null ? "令牌缺失" : "令牌可用";
        return "activePath " + path + " · " + keep + "\n"
                + origin + " · " + tok + " · 20s 无心跳判定离线 · 点按看诊断";
    }

    /** 连接诊断对话框：只列能给出证据的项，缺数据的项如实写「未记录/不可用」。 */
    private void showDiagnostics() {
        HostProfile hp = HostProfile.get(this);
        HostProfile.Profile p = hp.current();

        StringBuilder sb = new StringBuilder();
        sb.append("执行位置  ").append(p.kind).append('\n');
        sb.append("入口      ").append(p.describe()).append('\n');
        sb.append("来源基座  ").append(p.baseOrigin()).append('\n');
        sb.append("保活      ").append(DshPowerGuard.summary(this)).append('\n');
        String fp = DshDeviceKey.fingerprintOrNull();
        sb.append("设备指纹  ").append(fp == null ? "不可用（密钥未建立）" : prefix(fp)).append('\n');
        sb.append("已配对    ").append(DshDeviceIdentity.lastPairedAt(this) > 0 ? "是" : "否");
        if (relayOrigin != null) sb.append("  ").append(relayOrigin);
        sb.append('\n');
        sb.append("令牌      ").append(token == null ? "缺失" : "可用（不显示内容）").append('\n');
        sb.append("绑定目标  ")
                .append(DshDeviceIdentity.lastHostDeviceId(this) == null ? "未记录"
                        : DshDeviceIdentity.lastHostDeviceId(this));
        sb.append("\n\n心跳口径：Relay 侧 20 秒收不到电脑上报即判离线；"
                + "本页不显示「几秒前」这类时间戳，因为接口只给在线布尔值。");

        new AlertDialog.Builder(this)
                .setTitle("连接诊断")
                .setMessage(sb.toString())
                .setNegativeButton("复制", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        copyToClipboard(diagText());
                    }
                })
                .setPositiveButton("关闭", null)
                .show();
    }

    private void copyToClipboard(String text) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(
                    android.content.ClipData.newPlainText("dsh-diagnostics", text));
            showStatus("诊断摘要已复制。", false);
        } catch (Throwable t) {
            showStatus("复制失败：" + t.getMessage(), true);
        }
    }

    private static String prefix(String fp) {
        return fp.length() <= 16 ? fp : fp.substring(0, 16) + "…";
    }

    // ---- 动作 ----

    /** 切回本机引擎：闭环回家路径，不用绕回选择页。 */
    private void useLocalEngine() {
        HostProfile hp = HostProfile.get(this);
        if (HostProfile.KIND_LOCAL.equals(hp.currentId())) {
            // 已在本机档：直接回应用，不做无意义的重启（会丢当前会话滚动位置）
            finish();
            return;
        }
        if (!hp.setCurrent(HostProfile.KIND_LOCAL)) {
            showStatus("切回本机失败：档案状态异常，重启应用后再试。", true);
            return;
        }
        ProfileSession.resetRuntime();
        ProfileSession.restartInto(this, new Intent(this, MainActivity.class));
    }

    /**
     * 确认后切换。确认框文案与服务端 `notice` 同一句：切换不会带走任何东西。
     */
    private void confirmSwitch(final DshHostsClient.Host h) {
        String label = displayName(h);
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
        showStatus("正在切换到 " + displayName(h) + "…", false);
        setRefreshEnabled(false);
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
        setRefreshEnabled(true);
        if (!r.ok()) {
            HostsError.Finding f = r.finding();
            showStatus(f.title + "：" + f.action, true);
            return;
        }
        // 服务端已重绑（令牌不变、绑定变）。本地档案要跟上，否则重启又回到旧主机。
        String bound = r.hostId != null ? r.hostId : h.hostId;
        HostProfile hp = HostProfile.get(this);
        hp.setRelay(relayOrigin, bound, token);
        if (!hp.setCurrent(HostProfile.KIND_RELAY)) {
            showStatus("切换已生效，但本地档案没保存成功；重启后可能需要再切一次。", true);
            return;
        }
        ProfileSession.resetRuntime();
        // 同 SshConfigActivity：必须重启到新实例，否则主界面复用旧档案、仍是上一台主机
        ProfileSession.restartInto(this, new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_ENTER_REMOTE, true));
    }
}
