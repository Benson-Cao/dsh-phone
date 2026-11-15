package com.dsh.harness;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
 *
 * <h3>v2 设计稿（11 连接失败）的三条对齐原则</h3>
 * <ol>
 *   <li><b>标题行就是诊断结论</b>：设计稿写「目标电脑没有响应 · 20 秒无心跳」，
 *       而我们的探针只知道「连不上 / 状态码不对 / 在线 0 台」。所以标题用
 *       探针的真实结论，不套一句固定的「20 秒无心跳」—— 那会在真因是地址写错时
 *       把人引向心跳与保活。</li>
 *   <li><b>步骤区允许为空</b>：能给出处置步骤的状态才列编号，列不出来就不画，
 *       而不是硬凑三条。</li>
 *   <li><b>「上次配对」显示的是绑定记录</b>，不是「上次成功连接」—— 我们没有
 *       连接成功的时间戳，只有配对时间。名字写错会让人以为链路刚刚还在。</li>
 * </ol>
 */
public class OfflineActivity extends Activity {

    /**
     * 以「选择连接方式」的身份打开（r113 按需连接）。
     *
     * <p>同一个页面承担两种语义：远端档案启动时不自动进入 → 停在这里让用户选
     * 「进入电脑 / 改用本机」；或电脑连不上时 → 停在这里看诊断。
     * 两者共用同一套探测与绑定信息，只有标题与引导文案不同。
     */
    public static final String EXTRA_CHOICE = "choice";

    private TextView stateView;
    private View stateDot;
    private LinearLayout stepsBox;
    private TextView noStepsView;
    private TextView bindingTitle;
    private TextView bindingSub;
    private TextView bindingKind;
    private TextView snapshotStageView;
    private TextView snapshotKindView;
    private TextView recheckButton;
    private TextView enterButton;
    private boolean choiceMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        choiceMode = getIntent() != null && getIntent().getBooleanExtra(EXTRA_CHOICE, false);
        buildUi();
        check();
    }

    @Override
    protected void onResume() {
        super.onResume();
        check();
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

        // 应用栏：返回 + 标题 + 重新检查（刷新探针，与主按钮在两个模式下各司其职）
        recheckButton = DshUi.roundButton(this, "↻", new View.OnClickListener() {
            @Override public void onClick(View v) { check(); }
        });
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView backTop = DshUi.roundButton(this, "‹", new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(
                DshUi.dp(this, 34), DshUi.dp(this, 34));
        backLp.rightMargin = DshUi.dp(this, 10);
        bar.addView(backTop, backLp);
        bar.addView(DshUi.label(this, "连接电脑", 15, DshUi.ink(this), true));
        bar.addView(new View(this), new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(recheckButton);
        root.addView(bar, DshUi.matchWrap());

        root.addView(DshUi.label(this, choiceMode ? "连接到电脑" : "暂时连不上",
                23, DshUi.ink(this), true), DshUi.matchWrapTop(this, 10));
        root.addView(DshUi.label(this, choiceMode
                        ? "这台手机上也有 dsh 引擎，可以直接在本机用；要用电脑上的会话与文件再连过去。"
                        : "会话和文件都还在那台电脑上，恢复后接着用。",
                12.5f, DshUi.ink2(this), false), DshUi.matchWrapTop(this, 7));

        // ---- 结论卡：标题（探针结论）+ 处置步骤 ----
        LinearLayout errCard = new LinearLayout(this);
        errCard.setOrientation(LinearLayout.VERTICAL);
        errCard.setBackground(warnCard());
        int cpad = DshUi.dp(this, 13);
        errCard.setPadding(cpad, cpad, cpad, cpad);
        root.addView(errCard, DshUi.matchWrapTop(this, 14));

        LinearLayout headRow = new LinearLayout(this);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        headRow.setGravity(Gravity.CENTER_VERTICAL);
        stateDot = dot(DshUi.AMBER);
        headRow.addView(stateDot, dotLp());
        stateView = DshUi.label(this, "正在检查…", 13.5f, warnInk(), true);
        headRow.addView(stateView);
        errCard.addView(headRow, DshUi.matchWrap());

        stepsBox = new LinearLayout(this);
        stepsBox.setOrientation(LinearLayout.VERTICAL);
        stepsBox.setBackground(DshUi.inputDrawable(this));
        stepsBox.setPadding(DshUi.dp(this, 11), DshUi.dp(this, 3),
                DshUi.dp(this, 11), DshUi.dp(this, 3));
        errCard.addView(stepsBox, DshUi.matchWrapTop(this, 10));

        noStepsView = DshUi.label(this, "", 11.5f, warnInk(), false);
        noStepsView.setVisibility(View.GONE);
        errCard.addView(noStepsView, DshUi.matchWrapTop(this, 8));

        // ---- 上次配对（不是「上次成功连接」——时间戳来自配对，不来自链路）----
        root.addView(DshUi.section(this, "上次配对"), DshUi.matchWrapTop(this, 16));
        LinearLayout binding = DshUi.cardBox(this);
        LinearLayout bindRow = DshUi.rowBox(this);
        bindRow.addView(dot(DshUi.INK_3), dotLp());
        LinearLayout bindCol = new LinearLayout(this);
        bindCol.setOrientation(LinearLayout.VERTICAL);
        bindingTitle = DshUi.label(this, "未绑定任何电脑", 12.5f, DshUi.ink(this), true);
        bindCol.addView(bindingTitle);
        bindingSub = DshUi.label(this, "", 10.5f, DshUi.ink2(this), false);
        bindCol.addView(bindingSub, DshUi.matchWrapTop(this, 3));
        bindRow.addView(bindCol, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bindingKind = DshUi.label(this, "", 10.5f, DshUi.ink3(this), false);
        bindingKind.setTypeface(Typeface.MONOSPACE);
        bindRow.addView(bindingKind);
        binding.addView(bindRow);
        root.addView(binding, DshUi.matchWrapTop(this, 2));

        // 「还能做什么」分节 + capabilityText() 诊断条按设计稿修订版 §4.8（图62）删除：
        // 它把「可用降级」又复述一遍，而下面「诊断快照」三行已经说了同一件事。
        // 删除后中下部留白由底部的 spacer 吸收，不补替补卡。

        // ---- 诊断快照（单色稿新增）：把「哪一步失败 / 走的那条路 / 还能降级到哪」摊平 ----
        root.addView(DshUi.section(this, "诊断快照"), DshUi.matchWrapTop(this, 12));
        LinearLayout snap = DshUi.cardBox(this);
        snap.addView(snapshotRow("失败阶段", null, 0));
        snap.addView(DshUi.divider(this));
        snap.addView(snapshotRow("activePath", null, 1));
        snap.addView(DshUi.divider(this));
        snap.addView(snapshotRow("可用降级", "本机引擎", 2));
        root.addView(snap, DshUi.matchWrapTop(this, 2));

        // ---- 动作 ----
        enterButton = DshUi.button(this,
                choiceMode ? "进入这台电脑" : "重新检查", DshUi.BTN_PRIMARY);
        enterButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (choiceMode) enterRemote(); else check();
            }
        });
        root.addView(enterButton, DshUi.matchWrapTop(this, 14));

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row1, DshUi.matchWrapTop(this, 9));
        TextView sw = DshUi.button(this, "换一台电脑", DshUi.BTN_SECONDARY);
        sw.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(OfflineActivity.this, HostSwitcherActivity.class));
            }
        });
        row1.addView(sw, DshUi.half(this, true));
        TextView local = DshUi.button(this, "改用本机引擎", DshUi.BTN_SECONDARY);
        local.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { useLocal(); }
        });
        row1.addView(local, DshUi.half(this, false));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row2, DshUi.matchWrapTop(this, 9));
        TextView pair = DshUi.button(this, "重新配对", DshUi.BTN_SECONDARY);
        pair.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(OfflineActivity.this, PairActivity.class));
            }
        });
        row2.addView(pair, DshUi.half(this, true));
        // 第三台「电脑」不一定走 Relay：能 ssh 的机器用 SSH 档，不需要配对码。
        // 这台电脑连不上时，用户最可能就在这一页找别的路。
        TextView ssh = DshUi.button(this, "连 SSH 主机", DshUi.BTN_SECONDARY);
        ssh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(SshConfigActivity.intent(OfflineActivity.this));
            }
        });
        row2.addView(ssh, DshUi.half(this, false));

        // 「后台保活设置」按钮按设计稿修订版 §4.3 第 3 条改为**只读状态行**：
        // 它原本指向已删除的「我的 / 设置」页，而真正要改的是系统省电白名单。
        // 这里不再做成可点跳转 —— 一个跳系统设置的按钮放在失败页，会把
        // 「连不上电脑」和「后台会不会被杀」两件事混在一起。
        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row3, DshUi.matchWrapTop(this, 8));
        boolean exempt = DshPowerGuard.isExempt(this);
        TextView keep = DshUi.label(this,
                exempt ? "前台服务保活中 · 已放行省电白名单" : "前台服务未放行 · 长任务可能被系统结束",
                10.5f, exempt ? DshUi.label(this) : DshUi.ink2(this), false);
        row3.addView(keep, DshUi.matchWrap());

        // 本机引擎页（设计稿 09）：走「在这台手机上用」这条路时，引擎在不在跑、
        // 拿什么基线、保活怎么工作，此前只出现在通知栏与日志里，屏幕上看不到。
        // 本页是它**唯一**的入口（连接电脑页按 §4.7 已不再提供本机选项）。
        TextView engine = DshUi.button(this, "本机引擎状态", DshUi.BTN_GHOST);
        engine.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(OfflineActivity.this, EngineActivity.class));
            }
        });
        root.addView(engine, DshUi.matchWrapTop(this, 2));

        TextView back = DshUi.button(this, "返回应用", DshUi.BTN_GHOST);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        root.addView(back, DshUi.matchWrapTop(this, 2));

        setContentView(scroll);
        try {
            getWindow().setStatusBarColor(DshPalette.systemBar(this));
            getWindow().getDecorView().setSystemUiVisibility(
                    DshPalette.systemUiFlags(this, getWindow().getDecorView().getSystemUiVisibility()));
        } catch (Throwable ignored) {
        }
    }

    // ---- 探活 ----

    /** 后台探活 + 刷新文案。 */
    private void check() {
        final String relay = DshDeviceIdentity.lastRelayUrl(this);
        final String host = DshDeviceIdentity.lastHostDeviceId(this);
        final long at = DshDeviceIdentity.lastPairedAt(this);

        renderBinding(relay, host, at);

        if (relay == null) {
            setHead("还没有配对过", DshUi.AMBER);
            setSteps("先在电脑上生成配对码，再点下面的「重新配对」输入。");
            setEnabled(recheckButton, false);
            setEnabled(enterButton, false);
            return;
        }
        setEnabled(enterButton, true);
        setEnabled(recheckButton, false);
        setHead("正在检查…", DshUi.INK_3);
        setSteps("");

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
        setEnabled(recheckButton, true);
        if (!h.reachable) {
            setHead("连不上 " + relay, DshUi.DANGER);
            setSteps("手机与电脑在同一网络（同一 Wi-Fi，或电脑开了热点）\n"
                    + "电脑上的 Relay 正在运行\n"
                    + "地址与端口没写错（可在电脑端配对码页面看到）");
            return;
        }
        if (h.status != 200) {
            setHead("Relay 返回 " + h.status, DshUi.AMBER);
            setSteps("地址是通的，但服务没有正常响应。看电脑端 Relay 日志。");
            return;
        }
        if (h.online == 0) {
            setHead("地址通，但没有电脑在线", DshUi.AMBER);
            setSteps("Relay 正在运行，是电脑本体没连上来\n"
                    + "确认电脑上的 dsh 客户端在运行（托盘图标），再刷新");
            return;
        }
        setHead("电脑在线（" + h.online + " 台）", DshUi.MINT);
        setSteps("连接已恢复：返回应用即可继续。若仍显示离线，"
                + "说明是这台手机与电脑的绑定关系需要重新配对。");
    }

    // capabilityText()（「还能做什么」那条诊断条）随该分节一并删除（设计稿修订版 §4.8 图62）。
    // 同样的 activePath / 令牌状态在下面「诊断快照」里已经有了，两处各写一遍只会漂移。

    // ---- 渲染小工具 ----

    /**
     * 结论卡的标题行 + 状态环。
     *
     * <p>单色稿把「状态」编码进**形状**而不是颜色，所以这里只有两档：
     * 实心 = 目标已达成（电脑在线），空心 = 其余全部（未配对 / 检查中 / 各类失败）。
     * 失败与否靠标题文案 + 下方虚线卡区分，不再靠红黄绿。
     *
     * <p>判定沿用本文件既有的「看标题前缀」写法（与 {@link #stageOf} 同源），
     * 避免为一个布尔值再加一个参数、把 8 个调用点全改一遍。
     */
    private void setHead(String text, int dotColor) {
        stateView.setText(text);
        if (snapshotStageView != null) snapshotStageView.setText(stageOf(text));
        if (snapshotKindView != null) snapshotKindView.setText(HostProfile.get(this).currentId());
        boolean solid = text != null && text.startsWith("电脑在线");
        try {
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            if (solid) {
                d.setColor(dotColor);
            } else {
                d.setColor(0x00000000);
                d.setStroke(Math.max(2, DshUi.dp(this, 2)), dotColor);
            }
            stateDot.setBackground(d);
        } catch (Throwable ignored) {
        }
    }

    /** 把结论行压成两个字的「失败阶段」，给诊断快照用（长句塞不进标签）。 */
    private String stageOf(String head) {
        if (head == null) return "未知";
        if (head.startsWith("连不上")) return "地址不通";
        if (head.startsWith("Relay 返回")) return "Relay 异常";
        if (head.startsWith("地址通")) return "无心跳";
        if (head.startsWith("电脑在线")) return "已恢复";
        if (head.startsWith("还没有配对过")) return "未配对";
        if (head.startsWith("正在检查")) return "检查中";
        return "档案异常";
    }

    /** 诊断快照的一行：左词 + 右标签（虚线=失败阶段，反白=执行位置，实心=可降级项）。 */
    private View snapshotRow(String name, String value, int style) {
        LinearLayout row = DshUi.rowBox(this);
        row.addView(DshUi.label(this, name, 12, DshUi.ink(this), true),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout tag;
        if (style == 0) {
            tag = DshUi.tagWait(this, value == null ? "检查中" : value);
        } else if (style == 1) {
            tag = DshUi.tagInvert(this, value == null
                    ? HostProfile.get(this).currentId() : value);
        } else {
            tag = DshUi.tagOn(this, value);
        }
        TextView txt = (TextView) tag.getChildAt(tag.getChildCount() - 1);
        if (style == 0) snapshotStageView = txt;
        if (style == 1) snapshotKindView = txt;
        row.addView(tag);
        return row;
    }

    /**
     * 处置步骤：逐行编号。
     *
     * <p>编号是「按顺序做」的暗示，所以只在真有多步时出现；一句话的说明不加编号，
     * 否则读起来像一份没写完的清单。
     */
    private void setSteps(String detail) {
        stepsBox.removeAllViews();
        noStepsView.setVisibility(View.GONE);
        if (detail == null || detail.trim().isEmpty()) {
            stepsBox.setVisibility(View.GONE);
            return;
        }
        String[] lines = detail.split("\n");
        if (lines.length == 1) {
            stepsBox.setVisibility(View.GONE);
            noStepsView.setVisibility(View.VISIBLE);
            noStepsView.setText(lines[0]);
            return;
        }
        stepsBox.setVisibility(View.VISIBLE);
        int shown = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = stripNumber(lines[i]);
            if (line.isEmpty()) continue;
            if (shown > 0) stepsBox.addView(DshUi.divider(this));
            shown++;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, DshUi.dp(this, 8), 0, DshUi.dp(this, 8));
            TextView n = DshUi.label(this, String.valueOf(shown), 11.5f, DshUi.BRAND, true);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    DshUi.dp(this, 14), LinearLayout.LayoutParams.WRAP_CONTENT);
            nlp.rightMargin = DshUi.dp(this, 9);
            row.addView(n, nlp);
            TextView t = DshUi.label(this, line, 11.5f, DshUi.ink(this), false);
            row.addView(t, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            stepsBox.addView(row);
        }
    }

    /** 去掉文案里已有的「1)」「1、」编号，避免出现「1 1)」这种双编号。 */
    private static String stripNumber(String line) {
        return line.trim().replaceFirst("^\\d+\\s*[)）.、]\\s*", "");
    }

    private void renderBinding(String relay, String host, long at) {
        HostProfile.Profile p = HostProfile.get(this).current();
        bindingKind.setText(p.kind);
        if (relay == null && host == null) {
            bindingTitle.setText("未绑定任何电脑");
            bindingSub.setText("配对之后这里会显示电脑名与配对时间");
            return;
        }
        String when = at > 0 ? DateFormat.format("yyyy-MM-dd HH:mm", at).toString() : null;
        bindingTitle.setText((host == null ? "—" : host) + (when == null ? "" : " · " + when));
        StringBuilder sb = new StringBuilder();
        sb.append(relay == null ? "地址未记录" : relay);
        sb.append(" · 省电").append(DshPowerGuard.isExempt(this) ? "已放行" : "未放行");
        bindingSub.setText(sb.toString());
    }

    private void setEnabled(TextView v, boolean enabled) {
        if (v == null) return;
        v.setEnabled(enabled);
        v.setAlpha(enabled ? 1f : 0.5f);
    }

    /** 结论卡：单色稿是**1.5px 虚线近黑边**，不填告警底色（mono/README §3「线型通道」）。 */
    private GradientDrawable warnCard() {
        return DshUi.dashedCardDrawable(this, DshUi.ink2(this));
    }

    private int warnInk() {
        return DshUi.ink(this);
    }

    /** 空心环：单色稿里「待处理/离线」都用空心圈，只有「在线/完成」才是实心点。 */
    private View dot(int color) {
        View v = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(0x00000000);
        d.setStroke(Math.max(2, DshUi.dp(this, 2)), color);
        v.setBackground(d);
        return v;
    }

    private LinearLayout.LayoutParams dotLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                DshUi.dp(this, 7), DshUi.dp(this, 7));
        lp.rightMargin = DshUi.dp(this, 8);
        return lp;
    }

    // ---- 按需连接的三条出口 ----

    /**
     * 「进入这台电脑」：只跳过按需连接守卫，**不改变档案类型**。
     *
     * <p>r128：SSH 档必须原样保留（主机、隧道端口、令牌都是用户填的）。早年版在这里
     * 无条件 `setRelay(...) + setCurrent(relay)`，把 SSH 档悄悄换成一个没配置的
     * Relay（`relayUrl` 形如 `http://<电脑局域网IP>:8899`），用户看到的是
     * 「连不上这台电脑」加一串 Relay 原因 —— 而他填的明明是 SSH。
     * （注意 8899 是**电脑端**监听地址，不是手机本地端口；这里早年写成
     * `127.0.0.1:8899` 是错的，会让人去手机上排查一个不存在的端口。）
     * Relay 档仍走三要素补齐：地址、主机 ID、令牌缺一不可，缺令牌不能降级成
     * 「无令牌也进」—— 反代页会因为拿不到会话票而白屏。
     *
     * <p>两个出口都用 `restartInto()`：`CLEAR_TOP` 命中已有 MainActivity 时只走
     * `onNewIntent`，`onCreate` 不执行，主界面会继续用切档前的档案快照（r125）。
     */
    private void enterRemote() {
        HostProfile hp = HostProfile.get(this);
        if (RemoteEntryRules.keepCurrentProfile(hp.current().kind)) {
            ProfileSession.restartInto(this, new Intent(this, MainActivity.class)
                    .putExtra(MainActivity.EXTRA_ENTER_REMOTE, true));
            return;
        }
        String relay = DshDeviceIdentity.lastRelayUrl(this);
        String host = DshDeviceIdentity.lastHostDeviceId(this);
        String token = DshTokenStore.load(this);
        if (relay == null || host == null || token == null) {
            setHead("还缺一步：这台手机没有可用的配对凭据", DshUi.AMBER);
            setSteps("点下面的「重新配对」，把手机重新连上电脑（配对码在电脑上生成）。");
            return;
        }
        hp.setRelay(relay, host, token);
        if (!hp.setCurrent(HostProfile.KIND_RELAY)) {
            setHead("进入失败：relay 档案没有配置完整", DshUi.DANGER);
            setSteps("地址或主机 ID 为空。重新配对一次。");
            return;
        }
        ProfileSession.resetRuntime();
        ProfileSession.restartInto(this, new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_ENTER_REMOTE, true));
    }

    /** 改用本机引擎：把档案切回 local，然后让 MainActivity 重新走本机流程。 */
    private void useLocal() {
        HostProfile hp = HostProfile.get(this);
        if (!hp.setCurrent(HostProfile.KIND_LOCAL)) {
            setHead("切回本机失败", DshUi.DANGER);
            setSteps("档案状态异常，重启应用后再试。");
            return;
        }
        ProfileSession.resetRuntime();
        ProfileSession.restartInto(this, new Intent(this, MainActivity.class));
    }
}
