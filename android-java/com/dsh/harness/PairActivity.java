package com.dsh.harness;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
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
 */
public class PairActivity extends Activity {

    private EditText baseInput;
    private EditText codeInput;
    private Button pairButton;
    private Button rotateButton;
    private Button powerButton;
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
        title.setText("连接电脑");
        title.setTextColor(fg);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(title, matchWrap());

        TextView hint = new TextView(this);
        hint.setText("在电脑端「生成配对码」，然后在这里填入电脑地址与 6 位数字。\n"
                + "配对码一次性使用，10 分钟内有效。");
        hint.setTextColor(muted);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hint.setLineSpacing(dp(2), 1f);
        root.addView(hint, marginTop(matchWrap(), 8));

        baseInput = new EditText(this);
        baseInput.setHint("电脑地址，如 192.168.0.5:8787");
        baseInput.setHintTextColor(muted);
        baseInput.setTextColor(fg);
        baseInput.setSingleLine(true);
        baseInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        baseInput.setBackground(roundRect(DshPalette.surface(this), dp(10)));
        baseInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(baseInput, marginTop(matchWrap(), 18));

        codeInput = new EditText(this);
        codeInput.setHint("6 位配对码");
        codeInput.setHintTextColor(muted);
        codeInput.setTextColor(fg);
        codeInput.setSingleLine(true);
        codeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        codeInput.setBackground(roundRect(DshPalette.surface(this), dp(10)));
        codeInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(codeInput, marginTop(matchWrap(), 10));

        pairButton = new Button(this);
        pairButton.setText("配对");
        pairButton.setAllCaps(false);
        pairButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startPairing();
            }
        });
        root.addView(pairButton, marginTop(matchWrap(), 16));

        // 配对是「连 Relay 上的电脑」这一条路；SSH 走另一条（公钥认证、不需要配对码）。
        // 未配对的人本来就停在本页，所以另一条路必须从这里也能进去。
        Button sshButton = new Button(this);
        sshButton.setText("改用 SSH 连一台机器");
        sshButton.setAllCaps(false);
        sshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(SshConfigActivity.intent(PairActivity.this));
            }
        });
        root.addView(sshButton, marginTop(matchWrap(), 8));

        rotateButton = new Button(this);
        rotateButton.setText("重新开始（作为新设备配对）");
        rotateButton.setAllCaps(false);
        rotateButton.setVisibility(View.GONE);
        rotateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String fresh = DshDeviceIdentity.rotate(PairActivity.this);
                rotateOffered = false;
                rotateButton.setVisibility(View.GONE);
                setStatus("已换一套新的设备身份（" + fresh + "）。现在重新生成配对码再配一次。", false);
                refreshFingerprint();
            }
        });
        root.addView(rotateButton, marginTop(matchWrap(), 8));

        statusView = new TextView(this);
        statusView.setTextColor(fg);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        statusView.setLineSpacing(dp(2), 1f);
        statusView.setVisibility(View.GONE);
        root.addView(statusView, marginTop(matchWrap(), 14));

        // ---- 省电放行 ----
        TextView powerTitle = new TextView(this);
        powerTitle.setText("后台保活");
        powerTitle.setTextColor(fg);
        powerTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        powerTitle.setTypeface(powerTitle.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(powerTitle, marginTop(matchWrap(), 32));

        powerView = new TextView(this);
        powerView.setText(DshPowerGuard.summary(this));
        powerView.setTextColor(muted);
        powerView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        powerView.setLineSpacing(dp(2), 1f);
        root.addView(powerView, marginTop(matchWrap(), 6));

        powerButton = new Button(this);
        powerButton.setText("去放行");
        powerButton.setAllCaps(false);
        powerButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(DshPowerGuard.requestIntent(PairActivity.this));
                } catch (Exception e) {
                    // 部分 ROM 三个 action 全摘掉 —— 如实说，不假装成功
                    setStatus("这台手机的系统没有可用的省电设置入口，请到「设置 → 电池 → 应用耗电管理」手动允许后台运行。", true);
                }
            }
        });
        root.addView(powerButton, marginTop(matchWrap(), 10));

        fingerprintView = new TextView(this);
        fingerprintView.setTextColor(muted);
        fingerprintView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        fingerprintView.setLineSpacing(dp(2), 1f);
        root.addView(fingerprintView, marginTop(matchWrap(), 28));

        setContentView(scroll);
        applySystemBar();
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

    /** 显示本机指纹前 16 位（够核对，不足以被拿去冒用）。 */
    private void refreshFingerprint() {
        final TextView tv = fingerprintView;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String fp = DshDeviceKey.fingerprintOrNull();
                final String text = fp == null
                        ? "本机设备密钥不可用：本次配对不会提交设备指纹（电脑端不会钉住这台设备）"
                        : "本机设备指纹 " + fp.substring(0, 16) + "…（首次配对时钉到这台电脑上）";
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tv.setText(text);
                    }
                });
            }
        }, "dsh-device-key").start();
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
        if (r.ok()) {
            DshDeviceIdentity.rememberBinding(this, r.hostDeviceId, r.relayUrl);
            // 令牌只落壳（Keystore 包裹），不进页面、不进日志。存失败要说出来：
            // 「配对成功但令牌没存下」等于下次还得重配，不能显示成一切正常。
            boolean stored = DshTokenStore.save(this, r.deviceToken);
            setStatus("配对成功：" + r.hostDeviceId + "（协议 " + r.protocol + "）。\n"
                    + (stored
                        ? "令牌已加密保存在本机。"
                        : "但令牌没能保存（设备密钥不可用），下次仍需重新配对。")
                    + "\n下一步：在桌面端「主机切换器」里选中这台电脑。", !stored);
            codeInput.setText("");
            return;
        }

        PairError.Finding f = r.finding();
        setStatus(f.title + "：" + f.action, true);
        rotateOffered = f.needsRotate;
        rotateButton.setVisibility(rotateOffered ? View.VISIBLE : View.GONE);
    }

    // ---- 小工具 ----

    private void setStatus(String text, boolean error) {
        statusView.setVisibility(View.VISIBLE);
        statusView.setText(text);
        statusView.setTextColor(error ? 0xFFD14343 : DshPalette.text(this));
    }

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

    /** 供其它页面/桥接启动本页。 */
    static Intent intent(Activity from) {
        return new Intent(from, PairActivity.class);
    }
}
