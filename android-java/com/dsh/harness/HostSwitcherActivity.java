package com.dsh.harness;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
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
 * <h3>单色稿（06）相对彩色版的差异</h3>
 * <ol>
 *   <li><b>状态不再用颜色</b>：在线 = 实心近黑点，离线 = 浅灰空心圈 + 灰虚线标签，
 *       当前设备/关键标识 = 反白块（{@link DshUi#tagInvert}）。</li>
 *   <li><b>「绑定目标 / 在线判定 / 切换语义」抽成实体卡片</b>：上一版是一段三行散文，
 *       读起来像免责声明；这三条恰恰是最容易被读错的三个词，做成表格更好查。</li>
 *   <li><b>心跳时间点仍然不显示</b>：接口只给 online 布尔值，编一个「3 秒前」比不显示更糟。</li>
 * </ol>
 */
public class HostSwitcherActivity extends Activity {

    private TextView statusView;
    private TextView hostsSection;
    private LinearLayout listBox;
    private TextView refreshButton;
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
        int pad = DshUi.dp(this, 16);
        root.setPadding(pad, DshUi.dp(this, 10), pad, DshUi.dp(this, 18));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 应用栏：返回 + 标题 + 刷新（含底部发丝线）----
        // 手写这一行而不是用 DshUi.appBar：刷新按钮要能被 setEnabled 压暗，
        // 必须自己握着引用（appBar 里建的那个拿不到）。
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int vpad = DshUi.dp(this, 4);
        bar.setPadding(0, vpad, 0, vpad);
        TextView back = DshUi.roundButton(this, "‹", new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        bar.addView(back, DshUi.fixedEnd(this, 30, 9));
        bar.addView(DshUi.label(this, "连接电脑", 14, DshUi.ink(this), true));
        bar.addView(new View(this), new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        refreshButton = DshUi.roundButton(this, "↻", new View.OnClickListener() {
            @Override public void onClick(View v) { reload(); }
        });
        bar.addView(refreshButton);
        root.addView(bar, DshUi.matchWrap());
        root.addView(DshUi.divider(this));

        // 标题与导语（原「在哪台设备上继续？」+「会话、文件与设置留在各自设备…」）已按
        // 设计稿修订版 §4.8（图59）删除：应用栏已经写明「连接电脑」，再写一遍大标题
        // 属于解释性重复。删除后中部留白由下面的内容与底部按钮吸收，不补替补卡。

        // ---- 状态行：只在有话说时占位（加载中 / 出错 / 提示）----
        statusView = DshUi.label(this, "", 11.5f, DshUi.label(this), false);
        statusView.setVisibility(View.GONE);
        root.addView(statusView, DshUi.matchWrapTop(this, 10));

        // ---- 已绑定：远端电脑清单 ----
        hostsSection = DshUi.section(this, "已绑定");
        root.addView(hostsSection, DshUi.matchWrapTop(this, 12));
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(listBox, DshUi.matchWrapTop(this, 2));

        // 「本机」设备组与「归属与在线口径」卡按设计稿修订版 §4.7/§4.8 删除：
        // 连接电脑列表里混入本机引擎，会让「选一台电脑」和「切执行位置」两件事混淆；
        // 口径表则是技术解释，属于「界面上堆叠产品口径」被红框点掉的那一类。
        // 本机引擎的入口仍在 08 失败页（兜底路径），本页不再提供。

        // ---- 诊断条（点按展开真实诊断）----
        diagBar = DshUi.diagBar(this, diagText());
        diagBar.setClickable(true);
        diagBar.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showDiagnostics(); }
        });
        DshUi.pressable(diagBar);
        root.addView(diagBar, DshUi.matchWrapTop(this, 16));

        // ---- 主按钮 + 两个次按钮 + 两个链接 ----
        TextView add = DshUi.button(this, "＋ 添加电脑", DshUi.BTN_PRIMARY);
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(HostSwitcherActivity.this, PairActivity.class));
            }
        });
        root.addView(add, DshUi.matchWrapTop(this, 12));

        // 「改用本机引擎」按钮按 §4.7 删除：本机档案不再从「连接电脑」页进入。
        // 「连接诊断」按 §4.3 第 2 条收敛为「刷新状态」——诊断对话框本身保留，
        // 入口仍在上面那条诊断条上（点条即展开），按钮位让给最常用的动作。
        TextView refreshBtn = DshUi.button(this, "刷新状态", DshUi.BTN_SECONDARY);
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { reload(); }
        });
        root.addView(refreshBtn, DshUi.matchWrapTop(this, 8));

        // 「连 SSH 主机」是 r123 特意加的第三条路：SSH 不走 Relay 也就不需要配对码，
        // 入口不能只挂在配对流程里。单色稿这一屏没有它，用下划线链接补，不占按钮位。
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row2, DshUi.matchWrapTop(this, 4));
        TextView sshBtn = DshUi.button(this, "连 SSH 主机 ›", DshUi.BTN_GHOST);
        sshBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(SshConfigActivity.intent(HostSwitcherActivity.this));
            }
        });
        row2.addView(sshBtn, DshUi.half(this, true));
        TextView backBtn = DshUi.button(this, "返回应用", DshUi.BTN_GHOST);
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        row2.addView(backBtn, DshUi.half(this, false));

        setContentView(scroll);
        try {
            getWindow().setStatusBarColor(DshPalette.systemBar(this));
            getWindow().getDecorView().setSystemUiVisibility(
                    DshPalette.systemUiFlags(this, getWindow().getDecorView().getSystemUiVisibility()));
        } catch (Throwable ignored) {
        }
    }

    // 这里原本有 factRow()（「归属与在线口径」三行）与 localRow()（「本机」设备行）。
    // 两张卡都按设计稿修订版 §4.7/§4.8 删除，行构造函数一并移除 —— 留着不用等于给
    // 下一个人「这里还有一块 UI」的错觉，而它在页面上根本不存在。

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
            // 空列表时**不画**「已绑定」小标题：一个没有任何卡的标题读起来像渲染失败，
            // 改成用一张空态卡说话，标题只在真有内容时出现。
            hostsSection.setVisibility(View.GONE);
            showStatus(f.title + "：" + f.action, true);
            listBox.addView(emptyCard("还没有可切换的电脑", "点下面的「添加电脑」，用配对码或 SSH 连一台。"));
            return;
        }
        List<DshHostsClient.Host> rows = r.rows;
        if (rows == null || rows.isEmpty()) {
            hostsSection.setVisibility(View.GONE);
            showStatus("这台手机还看不到任何电脑。确认电脑端在运行并已连上同一个 Relay。", true);
            listBox.addView(emptyCard("还没有可切换的电脑", "点下面的「添加电脑」，用配对码或 SSH 连一台。"));
            return;
        }
        hostsSection.setVisibility(View.VISIBLE);
        hostsSection.setText("已绑定 · " + rows.size());
        showStatus("", false);
        LinearLayout card = DshUi.cardBox(this);
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) card.addView(DshUi.divider(this));
            card.addView(hostRow(rows.get(i)));
        }
        listBox.addView(card, DshUi.matchWrapTop(this, 2));
    }

    /** 空态卡：与远端电脑卡同构（卡片 + 两行文字），避免空标题悬在页面上。 */
    private View emptyCard(String title, String hint) {
        LinearLayout card = DshUi.cardBox(this);
        LinearLayout row = DshUi.rowBox(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(DshUi.label(this, title, 12.5f, DshUi.ink(this), true));
        col.addView(DshUi.label(this, hint, 10.5f, DshUi.label(this), false),
                DshUi.matchWrapTop(this, 3));
        row.addView(col, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(row);
        card.setLayoutParams(DshUi.matchWrapTop(this, 2));
        return card;
    }

    /** 远端电脑一行：方块头像 + 名字/摘要 + 状态标签；整行可点。 */
    private View hostRow(final DshHostsClient.Host h) {
        LinearLayout row = DshUi.rowBox(this);
        row.addView(DshUi.avatar(this, avatarChar(h), h.online ? 0 : 2), avatarLp());
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
     * 中间两行：名字 12.5 加粗 + 摘要 10.5（hostId 用等宽）。
     * 名字过长时折行而不是截断 —— 两台电脑名字前缀相同的情况很常见。
     */
    private LinearLayout deviceColumn(String name, String meta, String idLine) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(DshUi.label(this, name, 12.5f, DshUi.ink(this), true));
        if (meta != null && meta.length() > 0) {
            col.addView(DshUi.label(this, meta, 10.5f, DshUi.label(this), false),
                    DshUi.matchWrapTop(this, 3));
        }
        if (idLine != null && idLine.length() > 0 && !idLine.equals(name)) {
            col.addView(DshUi.mono(this, idLine, 10, DshUi.mute(this), false),
                    DshUi.matchWrapTop(this, 2));
        }
        return col;
    }

    /**
     * 状态标签（单色稿 §3）。
     * <ul>
     *   <li>档案确实指向这台主机 → 实心点「正在使用」；</li>
     *   <li>{@code bound} 但没在用 → 虚线「绑定目标」（归属≠实时状态，r124）；</li>
     *   <li>离线 → 灰虚线 + 空心圈；在线 → 浅底实心点。</li>
     * </ul>
     */
    private View stateTag(DshHostsClient.Host h) {
        HostProfile.Profile cur = HostProfile.get(this).current();
        boolean inUse = HostProfile.KIND_RELAY.equals(cur.kind)
                && cur.hostId != null && cur.hostId.equals(h.hostId);
        if (inUse) return DshUi.tagOn(this, "正在使用");
        if (h.bound) return DshUi.tagWait(this, "绑定目标");
        if (!h.online) return DshUi.tagOff(this, "离线");
        return DshUi.tagOn(this, "在线");
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
                DshUi.dp(this, 34), DshUi.dp(this, 34));
        lp.rightMargin = DshUi.dp(this, 10);
        return lp;
    }

    // ---- 状态条 ----

    /**
     * 状态行：出错用**虚线近黑边**（单色稿没有琥珀底），提示用透明底。
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
        statusView.setTextColor(error ? DshUi.ink(this) : DshUi.label(this));
        statusView.setBackground(error ? DshUi.dashedCardDrawable(this, DshUi.ink2(this)) : null);
        int h = DshUi.dp(this, error ? 10 : 2);
        int v = DshUi.dp(this, error ? 9 : 2);
        statusView.setPadding(h, v, h, v);
    }

    // ---- 诊断 ----

    /** 诊断条文本：全部取本机真实值，不含任何令牌。 */
    private String diagText() {
        HostProfile hp = HostProfile.get(this);
        String path = hp.currentId();
        String keep = DshPowerGuard.isExempt(this) ? "保活已放行" : "保活未放行";
        String origin = relayOrigin == null ? "Relay 未记录" : relayOrigin;
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

    // 这里原本有 useLocalEngine()（切回本机档案的闭环路径）。设计稿修订版 §4.7 把
    // 「本机」从连接电脑页整体移除，本页不再承担「切回本机」的职责：
    // 走本机档案的入口只剩 08 连接失败页的兜底按钮。
    // 保留这个方法而不给它调用方，等于留一条「看起来能用其实点不到」的路 —— 所以删掉。

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
