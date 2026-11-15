package com.dsh.harness;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 配对页（原生页面 1/3）—— 协议 §5.1 的两步配对，全程不依赖 WebView 与 dsh 引擎。
 *
 * <h3>为什么是原生页而不是 Web 页</h3>
 * 首次安装时 dsh 引擎可能还在解包（几十秒到几分钟），此时 WebView 是空白的。
 * 配对是「用起来之前」必须完成的一步（依据 D-005：手机侧不写 UI 皮肤，
 * 但配对 / 切换器 / 离线页三块属原生面），所以不能寄生在 Web 页上。
 *
 * <h3>本页承担的三件事</h3>
 * <ol>
 *   <li>把 {@link DshDeviceKey} 的指纹随第一次配对交出去（r24 的 TOFU 钉住）；</li>
 *   <li>把 {@link PairError} 的处置建议如实展示 —— 三个 409 的下一步完全不同；</li>
 *   <li>在 {@code DEVICE_KEY_MISMATCH} 时提供显式的「重新开始」（换身份），
 *       而不是让用户反复重试一件不可能成功的事。</li>
 * </ol>
 *
 * <h3>不做什么</h3>
 * 不在这里存令牌。令牌由 WebView 侧 {@code client-core} 使用；本页只把
 * 配对结果写入 {@link DshDeviceIdentity} 的绑定记录，令牌的落盘归属
 * client-core 的存储层（避免两处各存一份、吊销后一边还在用）。
 *
 * <h3>v2 设计稿（07 添加电脑 · 配对）的三处对齐</h3>
 * <ol>
 *   <li>三段 Tab 是真跳转：配对码（本页）/ SSH（{@link SshConfigActivity}）/
 *       本机引擎（切档并重启）。不做「点了没反应」的装饰性分段控件。</li>
 *   <li>地址输入框下的说明按实测口径写死：填<b>电脑端配对页显示的地址</b>。
 *       历史上最常踩的坑是把手机的 127.0.0.1 填进来。</li>
 *   <li>保活一行显示 {@link DshPowerGuard#summary} 的真实结果，点一下就去系统设置；
 *       不在未放行时也画一个绿勾。</li>
 * </ol>
 */
public class PairActivity extends Activity {

    private EditText baseInput;
    private EditText codeInput;
    private TextView pairButton;
    private TextView rotateButton;
    private TextView statusView;
    private TextView fingerprintView;

    /** 上一次失败是否需要「重新开始」。 */
    private boolean rotateOffered = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        prefill();
    }

    // 原本这里在 onResume 里刷新「后台保活」行。该行按设计稿修订版 §4.8 删除，
    // 本页不再展示放行状态（它在 08 连接失败页以只读方式出现），所以钩子一并去掉。

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

        // ---- 应用栏 ----
        root.addView(DshUi.appBar(this, "添加电脑", "‹", new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        }, null, null), DshUi.matchWrap());

        // ---- 三段 Tab ----
        root.addView(tabBar(), DshUi.matchWrapTop(this, 14));

        // ---- 标题与导语 ----
        root.addView(DshUi.label(this, "用配对码连接", 21, DshUi.ink(this), true),
                DshUi.matchWrapTop(this, 10));
        root.addView(DshUi.label(this,
                "在电脑端生成配对码，把显示的地址和 6 位数字填到这里。",
                12.5f, DshUi.label(this), false), DshUi.matchWrapTop(this, 6));

        // ---- 卡片：地址 + 配对码 ----
        LinearLayout card = DshUi.cardBox(this);
        root.addView(card, DshUi.matchWrapTop(this, 12));

        card.addView(fieldLabel("电脑地址", "?"));
        baseInput = inputBox("192.168.0.5:8787",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        card.addView(baseInput);
        card.addView(helpText("填电脑端配对页显示的局域网地址，", "不是手机的 127.0.0.1。"));
        card.addView(DshUi.divider(this));

        card.addView(fieldLabel("6 位配对码", "?"));
        codeInput = inputBox("• • •   • • •", InputType.TYPE_CLASS_NUMBER);
        card.addView(codeInput);
        card.addView(helpText("一次性使用 · ", "10 分钟", " 内有效 · 设备/IP 限速"));

        // 这里原本是「首次连接会做什么」分节 + 两枚 info 卡（指纹钉住 / 后台保活）。
        // 按设计稿修订版 §4.8（图60）整体删除，不补替补卡：指纹核对已经在表单下方
        // 那条等宽诊断条里给出真实值，保活状态在 08 连接失败页以只读方式呈现。

        // ---- 状态 ----
        statusView = DshUi.label(this, "", 12.5f, DshUi.ink2(this), false);
        statusView.setVisibility(View.GONE);
        root.addView(statusView, DshUi.matchWrapTop(this, 12));

        // ---- 动作 ----
        pairButton = DshUi.button(this, "配对并连接", DshUi.BTN_PRIMARY);
        pairButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startPairing(); }
        });
        root.addView(pairButton, DshUi.matchWrapTop(this, 14));

        // 设备身份冲突时才会出现：换一套身份是唯一能自愈的动作
        rotateButton = DshUi.button(this, "重新开始（作为新设备配对）", DshUi.BTN_DANGER);
        rotateButton.setVisibility(View.GONE);
        rotateButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String fresh = DshDeviceIdentity.rotate(PairActivity.this);
                rotateOffered = false;
                rotateButton.setVisibility(View.GONE);
                setStatus("已换一套新的设备身份（" + fresh + "）。现在重新生成配对码再配一次。", false);
                refreshFingerprint();
            }
        });
        root.addView(rotateButton, DshUi.matchWrapTop(this, 9));

        // 配对是「连 Relay 上的电脑」这一条路；SSH 走另一条（公钥认证、不需要配对码）。
        TextView ssh = DshUi.button(this, "改用 SSH 连一台机器 ›", DshUi.BTN_GHOST);
        ssh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(SshConfigActivity.intent(PairActivity.this));
            }
        });
        root.addView(ssh, DshUi.matchWrapTop(this, 6));

        // ---- 设备身份（等宽诊断条）----
        fingerprintView = DshUi.diagBar(this, "正在读取设备身份…");
        root.addView(fingerprintView, DshUi.matchWrapTop(this, 14));

        setContentView(scroll);
        applySystemBar();
    }

    /** 分段控件（设计稿 .seg）：选中项**反白块**（近黑底白字），容器浅灰 + 发丝线。 */
    private LinearLayout tabBar() {
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable segBg = new GradientDrawable();
        segBg.setColor(DshUi.subtle(this));
        segBg.setCornerRadius(DshUi.dp(this, DshUi.R_CONTROL));
        segBg.setStroke(Math.max(1, DshUi.dp(this, 1)), DshUi.line(this));
        seg.setBackground(segBg);
        seg.setPadding(DshUi.dp(this, 2), DshUi.dp(this, 2),
                DshUi.dp(this, 2), DshUi.dp(this, 2));

        // 设计稿修订版 §4.7：分段控件只留「配对码 / SSH」两条真路径。原来的第三段
        // 「本机引擎」其实是**切换档案**，不是「添加一台电脑」，混在这里会让
        // 「连电脑」这件事看起来还有一个本地选项。
        String[] names = { "配对码", "SSH" };
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            boolean on = i == 0;
            TextView tab = DshUi.label(this, names[i], 11.5f,
                    on ? 0xFFFFFFFF : DshUi.label(this), on);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(0, DshUi.dp(this, 8), 0, DshUi.dp(this, 8));
            if (on) {
                tab.setBackground(DshUi.pillDrawable(this, DshUi.ink(this)));
            }
            tab.setClickable(true);
            tab.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (idx == 1) {
                        startActivity(SshConfigActivity.intent(PairActivity.this));
                    }
                }
            });
            seg.addView(tab, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        return seg;
    }

    /** 表单分组里的字段标题（设计稿 .lab）：11sp、加粗、label 色，右侧可带 ? 气泡。 */
    private LinearLayout fieldLabel(String text, String badge) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        int h = DshUi.dp(this, 12);
        box.setPadding(h, DshUi.dp(this, 9), h, 0);
        box.addView(DshUi.label(this, text, 11, DshUi.label(this), true));
        if (badge != null) {
            TextView q = DshUi.label(this, badge, 9, DshUi.mute(this), true);
            q.setGravity(Gravity.CENTER);
            q.setBackground(DshUi.pillDrawable(this, DshUi.subtle(this)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    DshUi.dp(this, 13), DshUi.dp(this, 13));
            lp.leftMargin = DshUi.dp(this, 6);
            box.addView(q, lp);
        }
        return box;
    }

    /** 输入框：37dp 高、8dp 圆角、极浅底、等宽字（设计稿 .inp 用等宽）。 */
    private EditText inputBox(String hint, int inputType) {
        EditText in = new EditText(this);
        in.setHint(hint);
        in.setHintTextColor(DshUi.mute(this));
        in.setTextColor(DshUi.ink(this));
        in.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        in.setTypeface(android.graphics.Typeface.MONOSPACE);
        in.setSingleLine(true);
        in.setInputType(inputType);
        in.setBackground(DshUi.inputDrawable(this));
        int h = DshUi.dp(this, 11);
        in.setPadding(h, DshUi.dp(this, 8), h, DshUi.dp(this, 8));
        // 输入框左右各留 12dp，与设计稿 .field 的内边距一致
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, DshUi.dp(this, 37));
        lp.leftMargin = DshUi.dp(this, 12);
        lp.rightMargin = DshUi.dp(this, 12);
        in.setLayoutParams(lp);
        return in;
    }

    /** 字段下方的说明：mute 10sp（设计稿 .help）。 */
    private TextView helpText(String... parts) {
        TextView t = DshUi.label(this, "", 10, DshUi.mute(this), false);
        StringBuilder sb = new StringBuilder();
        for (String p : parts) sb.append(p);
        t.setText(sb.toString());
        int h = DshUi.dp(this, 12);
        t.setPadding(h, DshUi.dp(this, 5), h, DshUi.dp(this, 10));
        return t;
    }

    // infoCard() 随「首次连接会做什么」两枚说明卡一并删除（设计稿修订版 §4.8 图60）。
    // 需要说明小卡时请从 DshUi 取，不要再在这里长回一份。

    private void applySystemBar() {
        try {
            getWindow().setStatusBarColor(DshPalette.systemBar(this));
            getWindow().getDecorView().setSystemUiVisibility(
                    DshPalette.systemUiFlags(this, getWindow().getDecorView().getSystemUiVisibility()));
        } catch (Throwable ignored) {
            // 系统栏着色失败不影响功能
        }
    }

    private void prefill() {
        String last = DshDeviceIdentity.lastRelayUrl(this);
        if (last != null && baseInput.getText().length() == 0) baseInput.setText(last);
        refreshFingerprint();
    }

    /** 设备身份一行（等宽）。只给前 16 位：够核对，不足以被拿去冒用。 */
    private void refreshFingerprint() {
        final TextView tv = fingerprintView;
        final String phoneId = DshDeviceIdentity.phoneId(this);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String fp = DshDeviceKey.fingerprintOrNull();
                final String text = fp == null
                        ? "device " + phoneId + "\n指纹不可用：本次配对不提交指纹（电脑端不会钉住这台设备）"
                        : "device " + phoneId + " · 指纹 " + fp.substring(0, 16) + "…"
                          + "\n首次配对时把这串钉到那台电脑上";
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tv.setText(text);
                    }
                });
            }
        }, "dsh-device-key").start();
    }

    // ---- 动作 ----

    // requestPowerExempt() 与 useLocalEngine() 已随「保活卡」「本机引擎 tab」一并移除：
    // 两者在当前页面都不再有调用方。省电放行仍在 08 连接失败页与 09 本机引擎页可达。

    // ---- 配对 ----

    private void startPairing() {
        final String base = DshPairClient.normalizeBase(baseInput.getText().toString());
        final String code = codeInput.getText().toString().trim();
        if (base == null) {
            setStatus("电脑地址格式不对。填 IP 或 IP:端口，例如 192.168.0.5:8787。", true);
            return;
        }
        if (!code.matches("\\d{6}")) {
            setStatus("配对码是 6 位数字。", true);
            return;
        }

        pairButton.setEnabled(false);
        pairButton.setAlpha(0.6f);
        rotateButton.setVisibility(View.GONE);
        setStatus("正在配对…", false);

        new Thread(new Runnable() {
            @Override
            public void run() {
                final String phoneId = DshDeviceIdentity.phoneId(PairActivity.this);
                final String name = DshDeviceIdentity.clientName();
                final String fp = DshDeviceKey.fingerprintOrNull();
                final DshPairClient.Verified r =
                        DshPairClient.pairOnce(base, code, phoneId, name, fp);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        onPairResult(r);
                    }
                });
            }
        }, "dsh-pair").start();
    }

    private void onPairResult(DshPairClient.Verified r) {
        pairButton.setEnabled(true);
        pairButton.setAlpha(1f);
        if (r.ok()) {
            DshDeviceIdentity.rememberBinding(this, r.hostDeviceId, r.relayUrl);
            // 令牌只落壳（Keystore 包裹），不进页面、不进日志。存失败要说出来：
            // 「配对成功但令牌没存下」等于下次还得重配，不能显示成一切正常。
            boolean stored = DshTokenStore.save(this, r.deviceToken);
            setStatus("配对成功：" + r.hostDeviceId + "（协议 " + r.protocol + "）。\n"
                    + (stored
                        ? "令牌已加密保存在本机。"
                        : "但令牌没能保存（设备密钥不可用），下次仍需重新配对。")
                    + "\n下一步：在「切换电脑」里选中这台电脑。", !stored);
            codeInput.setText("");
            return;
        }

        PairError.Finding f = r.finding();
        setStatus(f.title + "：" + f.action, true);
        rotateOffered = f.needsRotate;
        rotateButton.setVisibility(rotateOffered ? View.VISIBLE : View.GONE);
    }

    // ---- 小工具 ----

    /**
     * 状态区：成功用中性色、失败用琥珀底。
     *
     * <p>不用纯红字：配对失败大多是「码过期 / 已被使用 / 身份冲突」这类可处置的事，
     * 纯红读起来像崩了。
     */
    private void setStatus(String text, boolean error) {
        statusView.setVisibility(View.VISIBLE);
        statusView.setText(text);
        statusView.setTextColor(error ? DshUi.ink(this) : DshUi.label(this));
        // 单色稿：失败用**虚线近黑边**，不用琥珀底（见 mono/README §3「线型通道」）
        statusView.setBackground(error
                ? DshUi.dashedCardDrawable(this, DshUi.ink2(this))
                : null);
        int h = DshUi.dp(this, error ? 11 : 2);
        int v = DshUi.dp(this, error ? 9 : 2);
        statusView.setPadding(h, v, h, v);
    }

    /** 供其它页面/桥接启动本页。 */
    static Intent intent(Activity from) {
        return new Intent(from, PairActivity.class);
    }
}
