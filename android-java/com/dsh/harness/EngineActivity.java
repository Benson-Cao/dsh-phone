package com.dsh.harness;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 本机引擎页（设计稿 12「本机引擎」）。
 *
 * <h3>这一页要回答什么</h3>
 * 「在这台手机上用」这条路，用户能看到的只有通知栏里那行「本地服务运行中」。
 * 引擎到底在哪个端口、拿的是什么基线、被系统放行了没有、有没有第三方插件在改官方页面 ——
 * 这些都在日志里，不在屏幕上。本页把它们摊开。
 *
 * <h3>三处<b>故意</b>与设计稿不一致</h3>
 * <ol>
 *   <li><b>不写「30min 保活」</b>：设计稿的三格指标里有一格是「30min 保活」，
 *       而实现里 {@link DshWakeLock} 是<b>按需续期 10 分钟</b>（30s 采样、30s 内动过 ≥4KB 才算有活）。
 *       照抄 30min 会让用户以为「放着不动也能撑半小时」，这正是排查「为什么被杀了」时最误导人的一句。
 *       本页画的是真实机制。</li>
 *   <li><b>不写「2.8s 冷启动」</b>：本机没有冷启动耗时埋点，这个数只能靠编。
 *       三格改成「基线版本 / 保活状态 / 第三方插件数」—— 都是查得到源的量。</li>
 *   <li><b>不做「重置本机环境」红按钮</b>：设计稿有，但清空会话必须先有「导出」这一半链路，
 *       现在只有半条（{@link DshExporter} 能导，重置动作本身没有完整校验与恢复路径）。
 *       画一个点下去就删会话的按钮，是把风险丢给用户。这一格换成「环境检查」，那是真入口。</li>
 * </ol>
 *
 * <h3>线程</h3>
 * 「Termux + Node」要起子进程问版本（最多 4s），所以 {@link EnvCheck#run} 在后台线程跑；
 * 骨架先画，卡片后填。端口探活也在同一个后台线程里做，避免主线程阻塞。
 */
public class EngineActivity extends Activity {

    /** 运行环境卡片（异步填三行）。 */
    private LinearLayout envCard;
    /** 保活卡片（异步填两行）。 */
    private LinearLayout keepCard;
    /** Hero 区的状态行与指标行。 */
    private TextView heroKicker;
    private TextView heroMetrics;
    /** 底部主按钮：按端口探活结果变成「启动引擎 / 停止引擎」。 */
    private TextView engineButton;
    /** 页面已销毁就别再回写 UI。 */
    private boolean gone;
    /** 最近一次探活结果，决定按钮语义。 */
    private boolean running;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从系统省电设置回来要重算：保活卡片的状态就是在那里改的；引擎也可能被系统回收了
        refresh();
    }

    @Override
    protected void onDestroy() {
        gone = true;
        super.onDestroy();
    }

    // ---------- UI 骨架 ----------

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(DshUi.pageBg(this));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = DshUi.dp(this, 18);
        root.setPadding(pad, DshUi.dp(this, 12), pad, DshUi.dp(this, 20));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View.OnClickListener back = new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        };
        View.OnClickListener about = new View.OnClickListener() {
            @Override public void onClick(View v) { showAbout(); }
        };
        root.addView(DshUi.appBar(this, "本机引擎", "‹", back, "ⓘ", about), DshUi.matchWrap());
        root.addView(hero(), DshUi.matchWrapTop(this, 12));

        root.addView(DshUi.section(this, "运行环境"), DshUi.matchWrapTop(this, 16));
        envCard = DshUi.cardBox(this);
        root.addView(envCard, DshUi.matchWrapTop(this, 6));

        root.addView(DshUi.section(this, "保活"), DshUi.matchWrapTop(this, 16));
        keepCard = DshUi.cardBox(this);
        root.addView(keepCard, DshUi.matchWrapTop(this, 6));

        root.addView(new View(this), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, DshUi.dp(this, 22)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        engineButton = DshUi.button(this, "启动引擎", DshUi.BTN_SECONDARY);
        engineButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onEngineClick(); }
        });
        row.addView(engineButton, DshUi.half(this, true));

        TextView power = DshUi.button(this, "后台保活设置", DshUi.BTN_SECONDARY);
        power.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openPower(); }
        });
        row.addView(power, DshUi.half(this, false));
        root.addView(row, DshUi.matchWrap());

        TextView done = DshUi.button(this, "返回应用", DshUi.BTN_GHOST);
        done.setOnClickListener(back);
        root.addView(done, DshUi.matchWrapTop(this, 8));

        setContentView(scroll);
        try {
            getWindow().setStatusBarColor(DshPalette.systemBar(this));
            getWindow().getDecorView().setSystemUiVisibility(
                    DshPalette.systemUiFlags(this, getWindow().getDecorView().getSystemUiVisibility()));
        } catch (Throwable ignored) {
        }
    }

    /**
     * Hero：左侧 3dp 近黑竖条 + 状态 / 设备名 / 端口 / 三格指标。
     *
     * <p>单色稿用「左边一条竖条」代替左边的彩色边（{@code border-left:3px}），
     * 明度差就是强调；深浅色都取 ink，跟着主题反转。
     */
    private View hero() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(DshUi.card(this));
        bg.setCornerRadius(DshUi.dp(this, DshUi.R_PANEL));
        bg.setStroke(Math.max(1, DshUi.dp(this, 1)), DshUi.line(this));
        card.setBackground(bg);

        View accent = new View(this);
        GradientDrawable ad = new GradientDrawable();
        ad.setColor(DshUi.ink(this));
        int r = DshUi.dp(this, DshUi.R_PANEL);
        // 只圆左边两个角，右边与卡片内容齐平
        ad.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
        accent.setBackground(ad);
        card.addView(accent, new LinearLayout.LayoutParams(
                DshUi.dp(this, 3), LinearLayout.LayoutParams.MATCH_PARENT));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = DshUi.dp(this, 14);
        col.setPadding(p, p, p, DshUi.dp(this, 13));

        heroKicker = DshUi.mono(this, "● 正在探活…", 10.5f, DshUi.label(this), false);
        col.addView(heroKicker, DshUi.matchWrap());

        col.addView(DshUi.label(this, "这台手机", 17, DshUi.ink(this), true),
                DshUi.matchWrapTop(this, 7));

        col.addView(DshUi.mono(this, loopback(), 10.5f, DshUi.label(this), false),
                DshUi.matchWrapTop(this, 4));

        heroMetrics = DshUi.mono(this, "—", 10.5f, DshUi.label(this), false);
        heroMetrics.setLineSpacing(DshUi.dp(this, 2), 1f);
        col.addView(heroMetrics, DshUi.matchWrapTop(this, 9));

        card.addView(col, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return card;
    }

    /** 「127.0.0.1:3080 · 只绑回环」——端口取运行期常量，不写死数字。 */
    private String loopback() {
        return "127.0.0.1:" + DshBootstrap.DSH_PORT + " · 只绑回环";
    }

    // ---------- 数据 ----------

    private void refresh() {
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean up = DshProcessManager.isPortOpen(DshBootstrap.DSH_PORT);
                final List<EnvCheck.Item> items;
                try {
                    items = EnvCheck.run(app);
                } catch (Throwable t) {
                    return;   // 检查炸了骨架还在，不该把整页清空
                }
                final boolean exempt = DshPowerGuard.isExempt(app);
                final boolean wlOn = DshWakeLock.enabled(app);
                final boolean wlHeld = DshWakeLock.isHeld();
                final int plugins = EnvCheck.thirdPartyClientPlugins(app).size();
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        render(up, items, exempt, wlOn, wlHeld, plugins);
                    }
                });
            }
        }, "enginestate").start();
    }

    private void render(boolean up, List<EnvCheck.Item> items,
                        boolean exempt, boolean wlOn, boolean wlHeld, int plugins) {
        if (gone) return;
        running = up;

        heroKicker.setText(up ? "● 运行中 · on-device" : "○ 未运行 · 下方可启动");
        heroKicker.setTextColor(up ? DshUi.ink(this) : DshUi.mute(this));

        String baseline = DshBootstrap.engineVersion();
        heroMetrics.setText("基线 " + (baseline == null ? "未知" : baseline)
                + "   ·   保活 " + (exempt ? "已放行" : "未放行")
                + "   ·   插件 " + plugins);

        // 运行环境：Termux+Node / DSH 基线 / 窄屏适配 —— 三项来自 EnvCheck，不另起口径
        if (envCard != null && items.size() >= 4) {
            envCard.removeAllViews();
            envCard.addView(itemRow(items.get(0), 0, true));
            envCard.addView(itemRow(items.get(1), 1, true));
            envCard.addView(itemRow(items.get(3), 2, false));
        }

        // 保活：省电白名单（点得动，去系统设置）+ 唤醒锁（只读，展示真实机制）
        if (keepCard != null) {
            keepCard.removeAllViews();
            keepCard.addView(keepRow("⚡", "省电白名单",
                    exempt ? "已放行 · 厂商策略仍可能结束后台"
                            : "未放行 · 长任务会被系统结束",
                    exempt ? DshUi.tagOn(this, "已放行") : DshUi.tagOff(this, "未放行"),
                    0, true, new View.OnClickListener() {
                        @Override public void onClick(View v) { openPower(); }
                    }));
            keepCard.addView(keepRow("◷", "后台唤醒锁",
                    wlOn ? ("已开启 · 30s 采样，10min 按需续期"
                            + (wlHeld ? " · 当前持有" : " · 当前未持有"))
                         : "已关闭 · 后台任务可能在锁屏后中断",
                    wlOn ? DshUi.tagOn(this, wlHeld ? "持有" : "待命")
                         : DshUi.tagOff(this, "已关闭"),
                    1, false, new View.OnClickListener() {
                        @Override public void onClick(View v) { toggleWakeLock(); }
                    }));
        }

        syncButton();
    }

    // ---------- 行 ----------

    /** EnvCheck 的一项 → 一行（四档标签的分派与 DshUi 的形状编码一致：实心点 / 虚线卡 / 淡空心）。 */
    private View itemRow(EnvCheck.Item it, int tone, boolean divider) {
        LinearLayout tag;
        switch (it.state) {
            case EnvCheck.STATE_READY:
            case EnvCheck.STATE_PASS:
                tag = DshUi.tagOn(this, it.tagText());
                break;
            case EnvCheck.STATE_WAIT:
                tag = DshUi.tag(this, it.tagText(), DshUi.card(this), DshUi.ink2(this),
                        2, DshUi.mute(this), true);
                break;
            default:
                tag = DshUi.tagOff(this, it.tagText());
                break;
        }
        return itemRow(it.glyph, it.title, it.meta, tag, tone, divider, null);
    }

    private View keepRow(String glyph, String title, String meta, View right,
                         int tone, boolean divider, View.OnClickListener click) {
        return itemRow(glyph, title, meta, right, tone, divider, click);
    }

    /** 一行：头像 + 两行文字 + 右侧标签（可选下分隔线 / 可选点击）。 */
    private View itemRow(String glyph, String title, String meta, View right,
                         int tone, boolean divider, View.OnClickListener click) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row = DshUi.rowBox(this);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                DshUi.dp(this, 34), DshUi.dp(this, 34));
        alp.rightMargin = DshUi.dp(this, 10);
        row.addView(DshUi.avatar(this, glyph, tone), alp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(DshUi.label(this, title, 12.5f, DshUi.ink(this), true));
        TextView mt = DshUi.label(this, meta, 10.5f, DshUi.label(this), false);
        mt.setLineSpacing(DshUi.dp(this, 2), 1f);
        col.addView(mt, DshUi.matchWrapTop(this, 3));
        row.addView(col, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.leftMargin = DshUi.dp(this, 8);
        row.addView(right, rlp);

        if (click != null) {
            row.setOnClickListener(click);
            DshUi.pressable(row);
        }
        wrap.addView(row, DshUi.matchWrap());
        if (divider) wrap.addView(DshUi.divider(this));
        return wrap;
    }

    // ---------- 交互 ----------

    private void syncButton() {
        if (engineButton == null) return;
        engineButton.setText(running ? "停止引擎" : "启动引擎");
        // 设计稿 09 把「启动/停止引擎」与「后台保活」并成一行**两个次要按钮**：
        // 这一页没有主操作 —— 真正的主操作在上一屏（进入电脑 / 改用本机），
        // 这里再放一个近黑实底按钮会把「看一眼状态」读成「该做点什么」。
        engineButton.setBackground(DshUi.ghostDrawable(this));
        engineButton.setTextColor(DshUi.ink(this));
    }

    private void onEngineClick() {
        if (running) {
            // r148：与切换器/JS 弹窗共用同一套圆角卡片。
            DshDialog.with(this)
                    .title("停止本机引擎？")
                    .message("引擎停掉后，走本机档案的会话就读不到了；电脑端档案不受影响。"
                            + "下次进入应用会自动重新拉起。")
                    .negative("取消", null)
                    .positive("停止", new DshDialog.Action() {
                        @Override public void run() { stopEngine(); }
                    })
                    .show();
            return;
        }
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                try { DshProcessManager.start(app); } catch (Throwable ignored) { }
                runOnUiThread(new Runnable() {
                    @Override public void run() { refresh(); }
                });
            }
        }, "enginestart").start();
    }

    private void stopEngine() {
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                try { DshProcessManager.stop(app); } catch (Throwable ignored) { }
                runOnUiThread(new Runnable() {
                    @Override public void run() { refresh(); }
                });
            }
        }, "enginestop").start();
    }

    private void openPower() {
        try {
            boolean exempt = DshPowerGuard.isExempt(this);
            startActivity(exempt ? DshPowerGuard.appDetails(this) : DshPowerGuard.requestIntent(this));
        } catch (Throwable t) {
            try { startActivity(DshPowerGuard.appDetails(this)); } catch (Throwable ignored) { }
        }
    }

    /** 唤醒锁开关：写偏好 + 立即生效，随后重画两行（不在本地改文案，等真实状态回来）。 */
    private void toggleWakeLock() {
        DshWakeLock.setEnabled(this, !DshWakeLock.enabled(this));
        refresh();
    }

    /** ⓘ：把「保活到底怎么工作」讲清楚 —— 这是被系统杀进程后最常见的误解源。 */
    private void showAbout() {
        String msg = "本页只讲本机引擎（on-device 档案）。\n"
                + "\n端口：" + DshBootstrap.DSH_PORT + " 是本机引擎的监听口，只绑回环，"
                + "同一局域网的电脑连不上它 —— 要走电脑端请到「主机切换器」配 Relay。\n"
                + "\n保活不是「一直占着锁」：\n"
                + "· 每 30 秒采样一次本应用收发字节数\n"
                + "· 30 秒内动过 ≥4KB 才算有活在干，此时续 10 分钟\n"
                + "· 没流量就不续，锁自然释放 —— 省电与长任务之间的折中\n"
                + "\n被系统杀掉的第一处置不是这里的开关，而是系统省电白名单；"
                + "厂商策略仍可能无视它，所以长任务建议切电脑端档案。";
        DshDialog.with(this)
                .title("本机引擎在做什么")
                .message(msg)
                .positive("知道了", null)
                .show();
    }
}
