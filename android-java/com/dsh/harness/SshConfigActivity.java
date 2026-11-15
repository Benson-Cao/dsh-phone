package com.dsh.harness;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * SSH 主机配置页（原生页面 4/4）—— 填主机/端口/用户名/本地端口，测一次，再进。
 *
 * <h3>为什么补这一页（r123）</h3>
 * {@link HostProfile#setSsh} 在 r115 就写好了，但**全仓库没有调用方**：隧道层能跑、
 * 主机指纹能比、界面却没有任何地方能填主机地址。SSH 档因此对真实用户不可达 ——
 * 这比「缺一台 sshd」更前置。本页只做一件事：把 {@code setSsh} 接上界面。
 *
 * <h3>「测试连接」为什么按真连接验，而不是只验端口通</h3>
 * SSH 档失败几乎都发生在后两步（公钥没被接受 / 转发没建立），而这两步只有真起一次
 * `ssh -L` 才能证伪。所以测试按 {@link DshSshTunnel} 的真实序列走完：
 * 取主机公钥 → 指纹比对 → 生成/读取密钥 → 写 known_hosts → 起隧道 → 等本地端口 accept
 * → 停掉。中途任何一步失败都给出**那一步**的原因，不合并成一句「连接失败」。
 *
 * <h3>本页不做的两件事</h3>
 * <ul>
 *   <li>不写密码、不存密码：包内没有 askpass/sshpass，密码认证无法非交互完成；
 *       本页把公钥摊出来让用户贴到对端 {@code ~/.ssh/authorized_keys}。</li>
 *   <li>测试阶段**不钉住**首次见到的主机指纹：钉住是「保存并连接」的动作，
 *       测试只报告「首次见到，连的时候会钉住」。否则一次手滑的测试会留下一个
 *       错误的钉子，之后只会报指纹不一致。</li>
 * </ul>
 */
public class SshConfigActivity extends Activity {

    private EditText hostInput;
    private EditText portInput;
    private EditText userInput;
    private EditText localPortInput;
    private EditText targetPortInput;
    private EditText tokenInput;
    private Button testButton;
    private Button saveButton;
    private TextView statusView;
    private TextView fingerprintView;
    private TextView pubkeyView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        prefill();
    }

    // ---- UI ----

    private void buildUi() {
        // v2 设计稿 09「添加电脑 SSH」：应用栏 + 三段 Tab + 分组卡片 +
        // 端口映射示意 + 令牌掩码/显示 + 渐变主按钮。
        // 业务逻辑（字段、校验、testConnection / saveAndConnect / probe）一律不动。
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(DshUi.pageBg(this));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, dp(14), pad, dp(20));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 应用栏：返回 + 标题 ----
        LinearLayout appBar = new LinearLayout(this);
        appBar.setOrientation(LinearLayout.HORIZONTAL);
        appBar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView back = DshUi.label(this, "‹", 26, DshUi.ink(this), false);
        back.setPadding(0, 0, dp(12), 0);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        appBar.addView(back);
        appBar.addView(DshUi.label(this, "添加电脑", 18, DshUi.ink(this), true));
        root.addView(appBar, matchWrap());

        // ---- 三段 Tab：都是真跳转，不做假切换；选中项**反白块**（单色稿 .seg）----
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable segBg = new GradientDrawable();
        segBg.setColor(DshUi.subtle(this));
        segBg.setCornerRadius(dp(DshUi.R_CONTROL));
        segBg.setStroke(1, DshUi.line(this));
        tabs.setBackground(segBg);
        tabs.setPadding(dp(2), dp(2), dp(2), dp(2));
        root.addView(tabs, marginTop(matchWrap(), 12));
        // 设计稿修订版 §4.7：分段只留「配对码 / SSH」。第三段「本机引擎」原本是
        // 切换到本机档案并重启 —— 那是执行位置的切换，不属于「添加一台电脑」。
        final String[] tabNames = { "配对码", "SSH" };
        for (int i = 0; i < tabNames.length; i++) {
            final int idx = i;
            TextView tab = DshUi.label(this, tabNames[i], 11.5f,
                    i == 1 ? 0xFFFFFFFF : DshUi.label(this), i == 1);
            tab.setGravity(android.view.Gravity.CENTER);
            tab.setPadding(dp(8), dp(8), dp(8), dp(8));
            if (i == 1) {
                tab.setBackground(DshUi.pillDrawable(this, DshUi.ink(this)));
            }
            tab.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (idx == 0) {
                        startActivity(new Intent(SshConfigActivity.this, PairActivity.class));
                    }
                }
            });
            tabs.addView(tab, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }

        root.addView(DshUi.label(this,
                "页面由那台电脑的 dsh web 经隧道提供，不启动手机本机引擎。",
                11.5f, DshUi.ink2(this), false), marginTop(matchWrap(), 14));

        // ---- 卡片 1：主机地址 / SSH 端口 / 用户名 ----
        LinearLayout card1 = groupCard(root);
        hostInput = field(card1, "主机地址，如 100.99.226.70",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                DshUi.ink3(this), DshUi.ink(this));
        LinearLayout row1 = twoColumnRow(card1);
        portInput = field(row1, "SSH 端口", InputType.TYPE_CLASS_NUMBER,
                DshUi.ink3(this), DshUi.ink(this));
        portInput.setLayoutParams(DshUi.half(this, true));
        userInput = field(row1, "用户名", InputType.TYPE_CLASS_TEXT,
                DshUi.ink3(this), DshUi.ink(this));
        userInput.setLayoutParams(DshUi.half(this, false));

        // ---- 端口映射示意（数值以下面的输入框为准）----
        root.addView(DshUi.label(this, "端口映射", 11, DshUi.ink3(this), true),
                marginTop(matchWrap(), 16));
        LinearLayout map = new LinearLayout(this);
        map.setOrientation(LinearLayout.HORIZONTAL);
        map.setGravity(android.view.Gravity.CENTER_VERTICAL);
        map.addView(pill("手机 3081"));
        map.addView(DshUi.label(this, "  一 隧道 →  ", 11, DshUi.BRAND, true));
        map.addView(pill("电脑 3080"));
        root.addView(map, marginTop(matchWrap(), 6));

        LinearLayout card2 = groupCard(root);
        LinearLayout row2 = twoColumnRow(card2);
        localPortInput = field(row2, "手机端口", InputType.TYPE_CLASS_NUMBER,
                DshUi.ink3(this), DshUi.ink(this));
        localPortInput.setLayoutParams(DshUi.half(this, true));
        targetPortInput = field(row2, "电脑端口", InputType.TYPE_CLASS_NUMBER,
                DshUi.ink3(this), DshUi.ink(this));
        targetPortInput.setLayoutParams(DshUi.half(this, false));
        card2.addView(DshUi.label(this,
                "填电脑上 dsh web 实际监听的端口，与手机本机引擎的 3080 无关。",
                10.5f, DshUi.ink3(this), false), marginTop(matchWrap(), 10));

        // ---- dsh web 令牌：掩码 + 显示开关 ----
        root.addView(DshUi.label(this, "dsh web 令牌", 11, DshUi.ink3(this), true),
                marginTop(matchWrap(), 16));
        LinearLayout card3 = groupCard(root);
        LinearLayout tokenRow = new LinearLayout(this);
        tokenRow.setOrientation(LinearLayout.HORIZONTAL);
        tokenRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        tokenInput = field(tokenRow, "从电脑端启动日志复制",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                DshUi.ink3(this), DshUi.ink(this));
        tokenInput.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final boolean[] visible = { false };
        final TextView showToken = DshUi.link(this, "显示", 12, DshUi.ink(this), true);
        showToken.setPadding(dp(10), dp(8), 0, dp(8));
        showToken.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                visible[0] = !visible[0];
                int sel = tokenInput.getSelectionEnd();
                tokenInput.setInputType(InputType.TYPE_CLASS_TEXT
                        | (visible[0] ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                                     : InputType.TYPE_TEXT_VARIATION_PASSWORD));
                tokenInput.setSelection(sel);
                showToken.setText(visible[0] ? "隐藏" : "显示");
            }
        });
        tokenRow.addView(showToken);
        card3.addView(tokenRow, matchWrap());
        card3.addView(DshUi.label(this,
                "回环请求同样需要令牌；本机只保存你填的值。",
                10.5f, DshUi.ink3(this), false), marginTop(matchWrap(), 8));

        // ---- 高级：公钥登记与 TOFU（可展开，不是死链接）----
        final boolean[] open = { false };
        final TextView advancedBody = DshUi.label(this,
                "首次连接要把本机公钥登记到那台机器：本项目自带的 pc-sshd 读用户目录下的 "
                + ".dsh/ssh/authorized_keys，标准 sshd 读 ~/.ssh/authorized_keys。\n"
                + "主机公钥按 TOFU 钉住：首次见到会记录，之后不一致直接阻断。",
                10.5f, DshUi.ink2(this), false);
        advancedBody.setVisibility(View.GONE);
        final TextView advanced = DshUi.link(this,
                "▸ 高级：公钥登记与 TOFU 指纹说明", 11, DshUi.ink(this), true);
        advanced.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                open[0] = !open[0];
                advancedBody.setVisibility(open[0] ? View.VISIBLE : View.GONE);
                advanced.setText((open[0] ? "▾" : "▸") + " 高级：公钥登记与 TOFU 指纹说明");
            }
        });
        root.addView(advanced, marginTop(matchWrap(), 14));
        root.addView(advancedBody, marginTop(matchWrap(), 6));

        // ---- 底部双按钮 ----
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        testButton = new Button(this);
        testButton.setText("测试连接");
        testButton.setAllCaps(false);
        testButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        testButton.setTextColor(DshUi.ink(this));
        testButton.setBackground(DshUi.ghostDrawable(this));
        testButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { testConnection(); }
        });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bl.setMargins(0, 0, dp(5), 0);
        actions.addView(testButton, bl);

        saveButton = new Button(this);
        saveButton.setText("保存并连接");
        saveButton.setAllCaps(false);
        saveButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        saveButton.setTextColor(0xFFFFFFFF);
        saveButton.setBackground(DshUi.primaryDrawable(this));
        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveAndConnect(); }
        });
        LinearLayout.LayoutParams br = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        br.setMargins(dp(5), 0, 0, 0);
        actions.addView(saveButton, br);
        root.addView(actions, marginTop(matchWrap(), 18));

        // ---- 状态 / 指纹 / 公钥（业务方法仍引用，语义不变）----
        statusView = new TextView(this);
        statusView.setTextColor(DshUi.ink(this));
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        statusView.setLineSpacing(dp(2), 1f);
        statusView.setVisibility(View.GONE);
        root.addView(statusView, marginTop(matchWrap(), 16));

        fingerprintView = new TextView(this);
        fingerprintView.setTextColor(DshUi.ink2(this));
        fingerprintView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        fingerprintView.setTypeface(android.graphics.Typeface.MONOSPACE);
        fingerprintView.setLineSpacing(dp(2), 1f);
        fingerprintView.setVisibility(View.GONE);
        root.addView(fingerprintView, marginTop(matchWrap(), 12));

        pubkeyView = new TextView(this);
        pubkeyView.setTextColor(DshUi.ink2(this));
        pubkeyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        pubkeyView.setTypeface(android.graphics.Typeface.MONOSPACE);
        pubkeyView.setLineSpacing(dp(2), 1f);
        pubkeyView.setTextIsSelectable(true);   // 用户要把它复制到电脑上，必须能选中
        pubkeyView.setVisibility(View.GONE);
        root.addView(pubkeyView, marginTop(matchWrap(), 12));

        setContentView(scroll);
        try {
            getWindow().setStatusBarColor(DshPalette.systemBar(this));
            getWindow().getDecorView().setSystemUiVisibility(
                    DshPalette.systemUiFlags(this, getWindow().getDecorView().getSystemUiVisibility()));
        } catch (Throwable ignored) {
        }
    }

    /** 分组卡片（v2 稿：白底 + 18dp 圆角 + 1px 边框）。 */
    private LinearLayout groupCard(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(DshUi.cardDrawable(this));
        c.setPadding(dp(14), dp(12), dp(14), dp(14));
        parent.addView(c, marginTop(matchWrap(), 6));
        return c;
    }

    /** 一行两列容器。 */
    private LinearLayout twoColumnRow(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        parent.addView(r, marginTop(matchWrap(), 8));
        return r;
    }

    /** 端口映射示意里的小胶囊。 */
    private TextView pill(String text) {
        TextView t = DshUi.label(this, text, 11, DshUi.ink(this), true);
        t.setBackground(DshUi.pillDrawable(this, DshUi.card(this)));
        t.setPadding(dp(10), dp(7), dp(10), dp(7));
        return t;
    }

    /** 一行输入框（含提示文案与 v2 稿统一背景）。 */
    private EditText field(LinearLayout root, String hint, int inputType, int muted, int fg) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(muted);
        e.setTextColor(fg);
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setBackground(DshUi.inputDrawable(this));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(e, marginTop(matchWrap(), 10));
        return e;
    }

    /** 已存过配置就回填，省得每次重填。 */
    private void prefill() {
        // 注意用 savedSsh() 而不是「切到 ssh 档再切回来」：后者会落盘两次，
        // 用户只是打开配置页，档案不该被动过。
        HostProfile.Profile p = HostProfile.get(this).savedSsh();
        if (p == null) {
            portInput.setText("22");
            localPortInput.setText("3081");
            targetPortInput.setText(String.valueOf(SshForm.DEFAULT_TARGET_PORT));
            return;
        }
        hostInput.setText(p.sshHost);
        portInput.setText(String.valueOf(p.sshPort));
        userInput.setText(p.sshUser);
        localPortInput.setText(String.valueOf(p.localPort));
        targetPortInput.setText(String.valueOf(p.sshTargetPort));
        tokenInput.setText(p.sshToken);
    }

    // ---- 测试连接 ----

    private void testConnection() {
        final String host = hostInput.getText().toString().trim();
        final int port = SshForm.parsePort(portInput.getText().toString(), 22);
        final String user = userInput.getText().toString().trim();
        final int localPort = SshForm.parsePort(localPortInput.getText().toString(), 3081);
        final int targetPort = SshForm.parsePort(targetPortInput.getText().toString(),
                SshForm.DEFAULT_TARGET_PORT);
        final String token = tokenInput.getText().toString().trim();
        String bad = validate(host, port, user, localPort, targetPort);
        if (bad == null) bad = SshForm.validateToken(token);
        if (bad != null) {
            setStatus(bad, true);
            return;
        }
        if (!DshSshTunnel.available(this)) {
            setStatus(DshSshTunnel.unavailableReason(this), true);
            return;
        }

        testButton.setEnabled(false);
        saveButton.setEnabled(false);
        setStatus("正在测试 " + user + "@" + host + ":" + port + "…", false);

        new Thread(new Runnable() {
            @Override
            public void run() {
                final StringBuilder log = new StringBuilder();
                boolean ok = false;
                try {
                    ok = runTest(host, port, user, localPort, targetPort, token, log);
                } catch (Throwable t) {
                    log.append("测试过程异常：").append(t.getMessage()).append('\n');
                }
                final boolean passed = ok;
                final String text = log.toString();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        testButton.setEnabled(true);
                        saveButton.setEnabled(true);
                        setStatus(text.trim(), !passed);
                    }
                });
            }
        }, "dsh-ssh-test").start();
    }

    /** 真实序列：取公钥 → 比对指纹 → 生成密钥 → 写 known_hosts → 起隧道 → 等端口 → 试取页面。 */
    private boolean runTest(String host, int port, String user, int localPort,
                            int targetPort, String token, StringBuilder log) {
        // 1) 主机公钥
        final boolean hasKeyscan = DshSshTunnel.keyscanAvailable(this);
        String line = DshSshTunnel.scanKeyLine(this, host, port);
        String fp = SshHostKey.fingerprintOfKeyLine(line);
        if (fp == null) {
            log.append("✗ 取不到主机公钥。\n")
               .append(hasKeyscan ? "" : "  本包不带 ssh-keyscan，试探连接也没拿到。\n")
               .append("  查：地址与端口对不对、对端 sshd 在不在跑、手机和它同网段或同一 VPN。\n");
            return false;
        }
        showFingerprint(fp);

        // 2) 指纹比对（测试阶段不钉住）
        String pinned = DshSshTunnel.pinned(this, host, port);
        SshHostKey.Decision d = SshHostKey.decide(pinned, fp);
        if (d == SshHostKey.Decision.MISMATCH) {
            log.append("✗ 主机指纹和上次记录不一致，已拒绝继续。\n")
               .append("  上次记录：").append(pinned).append('\n')
               .append("  本次取得：").append(fp).append('\n')
               .append("  对端重装过系统会这样；也可能是中间人。确认无误后清除本机记录再连。\n");
            return false;
        }
        log.append(d == SshHostKey.Decision.MATCH
                ? "✓ 主机指纹与上次一致\n"
                : "· 首次见到这台主机，保存并连接时会钉住它的指纹\n");

        // 3) 密钥对（公钥要用户贴到对端）
        String pubkey = DshSshTunnel.ensureKeyPair(this);
        if (pubkey == null) {
            log.append("✗ 生成 SSH 密钥失败（bundle 里缺 ssh-keygen 或目录不可写）。\n");
            return false;
        }
        showPubkey(pubkey.trim());
        log.append("· 本机公钥已就绪（见下方，需加到对端 authorized_keys）\n");

        // 4) 起隧道，等本地端口 accept 才算通
        DshSshTunnel.writeKnownHost(this, host, port, line);
        Process pr = DshSshTunnel.start(this,
                HostProfile.sshProfileFor(host, port, user, localPort, targetPort, token));
        if (pr == null) {
            log.append("✗ 隧道进程没能创建（bundle 里的 ssh 不可执行？）。\n");
            return false;
        }
        boolean up = DshSshTunnel.waitLocalPort(localPort, 12000);
        if (!up) {
            DshSshTunnel.stop();
            log.append("✗ 隧道起来了但 127.0.0.1:").append(localPort).append(" 没通。\n")
               .append("  最常见：本机公钥还没加到对端的 authorized_keys。\n")
               .append("  把上面那行公钥追加到 ~/.ssh/authorized_keys 后再测一次。\n");
            return false;
        }
        log.append("✓ 隧道已建立：127.0.0.1:").append(localPort)
           .append(" → ").append(host).append(":").append(targetPort).append("\n");

        // 5) 端口通不等于页面能进：隧道那一头的 dsh 对回环请求也要令牌。
        //    这一步把「通了但 401」和「通了且能进」分开，否则用户点进去只看到一张 401。
        HttpProbe p = probe(SshForm.sshUrl(localPort, token));
        DshSshTunnel.stop();
        // 判定统一走 SshForm.probeVerdict（纯函数，桌面断言覆盖）。
        // 真 dsh web 接受令牌后并不直接给页面：回 303 + Location: ./，
        // 同时下发 HttpOnly / SameSite=Strict 的签名 cookie，WebView 跟随跳转后才拿到 200。
        // 探针关掉了自动跟随，所以这里拿到的是 303；只认 200 会把正常的登录流程
        // 误报成「那一头不像是 dsh 的界面」。
        int verdict = SshForm.probeVerdict(p.code, p.location);
        if (verdict == SshForm.VERDICT_OK) {
            if (p.code == 303) {
                log.append("✓ 电脑侧 dsh 已接受访问令牌（HTTP 303 → 跳转发签名 cookie），")
                   .append("点「保存并连接」即可进入。\n");
            } else {
                log.append("✓ 电脑侧 dsh 应答 200，点「保存并连接」即可进入。\n");
            }
            return true;
        }
        if (verdict == SshForm.VERDICT_TOKEN) {
            log.append("✗ 隧道通了，但电脑侧 dsh 拒绝访问（HTTP ").append(p.code).append("）。\n")
               .append("  访问令牌不对或没填。在电脑上取一次令牌再填到上面那栏。\n");
            return false;
        }
        if (verdict == SshForm.VERDICT_NO_REPLY) {
            log.append("✗ 隧道通了，但请求没有应答（").append(p.code == 0 ? "连不上" : "超时").append("）。\n")
               .append("  多半是远端端口填错了：电脑上那个端口没有 dsh。\n");
            return false;
        }
        log.append("· 隧道通了，电脑侧应答 HTTP ").append(p.code)
           .append("（那一头有服务，但不像是 dsh 的界面）。\n");
        return true;
    }

    /** 一次探测的结果：状态码 + 重定向目标。 */
    private static final class HttpProbe {
        final int code;
        final String location;
        HttpProbe(int code, String location) {
            this.code = code;
            this.location = location;
        }
    }

    /**
     * 取一次 HTTP 状态码与 Location（后台线程同步调用）。
     * 不跟随重定向：真 dsh 的 303 就是「令牌被接受」的信号；跟过去反而会因为
     * 没有 CookieManager 拿着 cookie 而拿到 401，把成功误判成失败。
     *
     * @return code：连不上 0，超时 -1；location 可能为 null
     */
    private static HttpProbe probe(String url) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "dsh-phone-sshtest");
            int code = c.getResponseCode();
            return new HttpProbe(code, c.getHeaderField("Location"));
        } catch (java.net.SocketTimeoutException e) {
            return new HttpProbe(-1, null);
        } catch (Throwable t) {
            return new HttpProbe(0, null);
        } finally {
            if (c != null) { try { c.disconnect(); } catch (Throwable ignored) { } }
        }
    }

    // ---- 保存并连接 ----

    private void saveAndConnect() {
        final String host = hostInput.getText().toString().trim();
        final int port = SshForm.parsePort(portInput.getText().toString(), 22);
        final String user = userInput.getText().toString().trim();
        final int localPort = SshForm.parsePort(localPortInput.getText().toString(), 3081);
        final int targetPort = SshForm.parsePort(targetPortInput.getText().toString(),
                SshForm.DEFAULT_TARGET_PORT);
        final String token = tokenInput.getText().toString().trim();
        String bad = validate(host, port, user, localPort, targetPort);
        if (bad == null) bad = SshForm.validateToken(token);
        if (bad != null) {
            setStatus(bad, true);
            return;
        }
        HostProfile hp = HostProfile.get(this);
        hp.setSsh(host, port, user, localPort, targetPort, token);
        if (!hp.setCurrent(HostProfile.KIND_SSH)) {
            setStatus("保存失败：档案状态异常。重启应用后再试。", true);
            return;
        }
        // 切档必须归零 cookie 与预热实例（R-22：防跨主机串会话）
        ProfileSession.resetRuntime();
        // 必须重启到**新实例**：CLEAR_TOP 会复用栈里已有的主界面，onCreate 不跑，
        // 它继续用切档前的档案（真机上表现为「host 对、令牌空 → 401」）。
        ProfileSession.restartInto(this, new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_ENTER_REMOTE, true));
    }

    /** 校验委托给 {@link SshForm}（纯函数，桌面 JDK 里有断言）。 */
    private String validate(String host, int port, String user, int localPort, int targetPort) {
        return SshForm.validate(host, port, user, localPort, targetPort);
    }

    // ---- 小工具 ----

    private void setStatus(final String text, final boolean error) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusView.setVisibility(View.VISIBLE);
                statusView.setText(text);
                // 单色稿没有红色错误字：失败靠**虚线边框 + 近黑字**区分（mono/README §3）
                statusView.setTextColor(error ? DshUi.ink(SshConfigActivity.this)
                        : DshUi.label(SshConfigActivity.this));
                statusView.setBackground(error
                        ? DshUi.dashedCardDrawable(SshConfigActivity.this,
                                DshUi.ink2(SshConfigActivity.this))
                        : null);
                int h = error ? dp(10) : dp(2);
                int v = error ? dp(9) : dp(2);
                statusView.setPadding(h, v, h, v);
            }
        });
    }

    private void showFingerprint(final String fp) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                fingerprintView.setVisibility(View.VISIBLE);
                fingerprintView.setText("主机指纹 " + fp);
            }
        });
    }

    private void showPubkey(final String pubkey) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                pubkeyView.setVisibility(View.VISIBLE);
                pubkeyView.setText("本机公钥（长按选中复制到对端 ~/.ssh/authorized_keys）：\n" + pubkey);
            }
        });
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
        d.setStroke(1, DshUi.line(this));
        return d;
    }

    /** 供其它页面启动本页。 */
    static Intent intent(Activity from) {
        return new Intent(from, SshConfigActivity.class);
    }
}
