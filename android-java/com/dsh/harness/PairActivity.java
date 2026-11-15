package com.dsh.harness;

import android.app.Activity;
import android.content.Intent;
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
    private TextView powerView;
    private TextView fingerprintView;

    /** 上一次失败是否需要「重新开始」。 */
    private boolean rotateOffered = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        prefill();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从系统设置页返回后刷新放行状态（用户可能刚点完）
        if (powerView != null) powerView.setText(DshPowerGuard.summary(this));
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

        // ---- 应用栏 ----
        root.addView(DshUi.appBar(this, "添加电脑", "‹", new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        }, null, null), DshUi.matchWrap());

        // ---- 三段 Tab ----
        root.addView(tabBar(), DshUi.matchWrapTop(this, 14));

        // ---- 标题与导语 ----
        root.addView(DshUi.label(this, "用配对码连接", 22, DshUi.ink(this), true),
                DshUi.matchWrapTop(this, 4));
        root.addView(DshUi.label(this,
                "在电脑端生成配对码，把显示的地址和 6 位数字填到这里。",
                12.5f, DshUi.ink2(this), false), DshUi.matchWrapTop(this, 7));

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

        // ---- 首次连接会做什么 ----
        root.addView(DshUi.section(this, "首次连接会做什么"), DshUi.matchWrapTop(this, 16));
        root.addView(infoCard("盾", DshUi.BRAND,
                "核对电脑指纹并长期钉住；之后指纹变化会直接阻断。", null, null),
                DshUi.matchWrapTop(this, 2));

        // 保活这一行是真实状态：未放行时点进去就跳系统设置，不在页面上假装已放行。
        powerView = DshUi.label(this, DshPowerGuard.summary(this), 11.5f, DshUi.ink2(this), false);
        LinearLayout powerCard = infoCard("电", DshUi.MINT,
                "后台保活", powerView, new View.OnClickListener() {
                    @Override public void onClick(View v) { requestPowerExempt(); }
                });
        root.addView(powerCard, DshUi.matchWrapTop(this, 8));

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

    /** 分段控件（设计稿 .seg）：选中项白底 + 品牌色，未选中项透明。 */
    private LinearLayout tabBar() {
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackground(DshUi.pillDrawable(this, DshUi.dark(this) ? 0xFF232C38 : 0xFFE8ECF5));
        seg.setPadding(DshUi.dp(this, 3), DshUi.dp(this, 3),
                DshUi.dp(this, 3), DshUi.dp(this, 3));

        String[] names = { "配对码", "SSH", "本机引擎" };
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            boolean on = i == 0;
            TextView tab = DshUi.label(this, names[i], 12,
                    on ? DshUi.BRAND : DshUi.ink2(this), on);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(0, DshUi.dp(this, 8), 0, DshUi.dp(this, 8));
            if (on) {
                tab.setBackground(DshUi.pillDrawable(this, DshUi.card(this)));
                tab.setElevation(DshUi.dp(this, 2));
            }
            tab.setClickable(true);
            tab.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (idx == 1) {
                        startActivity(SshConfigActivity.intent(PairActivity.this));
                    } else if (idx == 2) {
                        useLocalEngine();
                    }
                }
            });
            seg.addView(tab, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        return seg;
    }

    /** 表单分组里的字段标题（设计稿 .lab）：11.5sp、加粗、muted，右侧可带 ? 气泡。 */
    private LinearLayout fieldLabel(String text, String badge) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        int h = DshUi.dp(this, 13);
        box.setPadding(h, DshUi.dp(this, 11), h, 0);
        box.addView(DshUi.label(this, text, 11.5f, DshUi.ink2(this), true));
        if (badge != null) {
            TextView q = DshUi.label(this, badge, 9.5f, DshUi.ink3(this), true);
            q.setGravity(Gravity.CENTER);
            q.setBackground(DshUi.pillDrawable(this,
                    DshUi.dark(this) ? 0xFF2C3542 : 0xFFEEF1F7));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    DshUi.dp(this, 14), DshUi.dp(this, 14));
            lp.leftMargin = DshUi.dp(this, 6);
            box.addView(q, lp);
        }
        return box;
    }

    /** 输入框：39dp 高、11dp 圆角、浅灰底（设计稿 .inp）。 */
    private EditText inputBox(String hint, int inputType) {
        EditText in = new EditText(this);
        in.setHint(hint);
        in.setHintTextColor(DshUi.ink3(this));
        in.setTextColor(DshUi.ink(this));
        in.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        in.setSingleLine(true);
        in.setInputType(inputType);
        in.setBackground(DshUi.inputDrawable(this));
        int h = DshUi.dp(this, 12);
        in.setPadding(h, DshUi.dp(this, 9), h, DshUi.dp(this, 9));
        // 输入框左右各留 13dp，与设计稿 .field 的内边距一致
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, DshUi.dp(this, 39));
        lp.leftMargin = DshUi.dp(this, 13);
        lp.rightMargin = DshUi.dp(this, 13);
        in.setLayoutParams(lp);
        return in;
    }

    /** 字段下方的说明：muted 10.5sp，品牌色用于强调关键数字。 */
    private TextView helpText(String... parts) {
        TextView t = DshUi.label(this, "", 10.5f, DshUi.ink3(this), false);
        StringBuilder sb = new StringBuilder();
        for (String p : parts) sb.append(p);
        t.setText(sb.toString());
        int h = DshUi.dp(this, 13);
        t.setPadding(h, DshUi.dp(this, 6), h, DshUi.dp(this, 11));
        return t;
    }

    /**
     * 说明小卡（设计稿 .info）：图标块 + 一句说明 + 右侧可选值。
     *
     * @param glyph 图标块里的字（无图标资源，用字形代替，不引入外链图片）
     */
    private LinearLayout infoCard(String glyph, int tint, String title,
                                  TextView valueOverride, View.OnClickListener onClick) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(DshUi.cardDrawable(this));
        int h = DshUi.dp(this, 13);
        box.setPadding(h, DshUi.dp(this, 11), h, DshUi.dp(this, 11));

        TextView ic = DshUi.label(this, glyph, 12, tint, true);
        ic.setGravity(Gravity.CENTER);
        ic.setBackground(DshUi.pillDrawable(this,
                DshUi.dark(this) ? 0xFF232C38 : 0xFFF2F4FC));
        LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(
                DshUi.dp(this, 26), DshUi.dp(this, 26));
        icLp.rightMargin = DshUi.dp(this, 10);
        box.addView(ic, icLp);

        if (valueOverride == null) {
            TextView body = DshUi.label(this, title, 11.5f, DshUi.ink2(this), false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            box.addView(body, lp);
        } else {
            TextView head = DshUi.label(this, title, 11.5f, DshUi.ink(this), true);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(head);
            col.addView(valueOverride, DshUi.matchWrapTop(this, 3));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            box.addView(col, lp);
        }

        if (onClick != null) {
            box.setOnClickListener(onClick);
            DshUi.pressable(box);
            TextView chev = DshUi.label(this, "›", 18, DshUi.ink3(this), false);
            box.addView(chev);
        }
        return box;
    }

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

    private void requestPowerExempt() {
        try {
            startActivity(DshPowerGuard.requestIntent(PairActivity.this));
        } catch (Exception e) {
            // 部分 ROM 三个 action 全摘掉 —— 如实说，不假装成功
            setStatus("这台手机的系统没有可用的省电设置入口，"
                    + "请到「设置 → 电池 → 应用耗电管理」手动允许后台运行。", true);
        }
    }

    /** 切到本机档并重启（与切换器、SSH 配置页同一条归零路径）。 */
    private void useLocalEngine() {
        HostProfile hp = HostProfile.get(this);
        if (!hp.setCurrent(HostProfile.KIND_LOCAL)) {
            setStatus("切换到本机引擎失败：档案状态异常，重启应用后再试。", true);
            return;
        }
        ProfileSession.resetRuntime();
        ProfileSession.restartInto(this, new Intent(this, MainActivity.class));
    }

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
        statusView.setTextColor(error
                ? (DshUi.dark(this) ? 0xFFE8C07A : DshUi.ON_AMBER)
                : DshUi.ink2(this));
        statusView.setBackground(error
                ? DshUi.pillDrawable(this, DshUi.dark(this) ? 0xFF3A3020 : DshUi.SOFT_AMBER)
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
