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
    /** 底部双按钮走 {@link DshUi#button}（TextView），不再用系统 Button —— 见 buildUi 注释。 */
    private TextView testButton;
    private TextView saveButton;
    private TextView statusView;
    private TextView fingerprintView;
    private TextView pubkeyView;
    /** 端口映射示意图的容器：端口值改动时整块重画（两端文案都由输入决定）。 */
    private LinearLayout mapBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        prefill();
        refreshMap();   // 回填完端口再画映射示意，否则图上永远是默认 3081/3080
    }

    /** 返回上一级也禁用转场动画：两条切换路径顶部都不动。 */
    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    /**
     * r147：「‹」与系统返回键都回固定的上一级（03 连接电脑页），不走历史栈；
     * 理由与配对页 {@code goUp()} 相同（见 PairActivity）。
     */
    private void goUp() {
        startActivity(new Intent(this, HostSwitcherActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        overridePendingTransition(0, 0);
        finish();
    }

    /** 分段是同级替换：栈里始终只留一个「添加电脑」页。 */
    private void replaceWith(Intent target) {
        startActivity(target);
        overridePendingTransition(0, 0);
        finish();
    }

    @Override
    public void onBackPressed() { goUp(); }

    // ---- UI ----

    private void buildUi() {
        // 设计稿 05「添加电脑 · SSH」（mono / v4 第 5 屏）：
        // 应用栏 + 分段控件 + 导语 + 主机卡（字段带标题）+ 端口映射（示意 + 两列字段）
        // + 令牌卡 + 可展开的高级说明 + 「首次连接」两条 info + 底部双按钮。
        //
        // ⚠️ 本页此前是 v2 彩色稿的写法：自绘应用栏（‹ 26sp + 标题 18sp，没有圆钮与发丝线）、
        //    把 hint 当字段标签、用一个 pill 拼「一 隧道 →」、底部是系统 Button（13sp、
        //    自带最小宽度与内边距）。单色稿落地后它是唯一还留着旧视觉的页面 ——
        //    同一份「字段 + 说明」在 04 配对页是一套字号，在这里是另一套。
        //    所以整页按设计稿重建；业务方法（prefill / testConnection / runTest /
        //    saveAndConnect / setStatus / showFingerprint / showPubkey）一行不改。
        // r145：应用栏 + 分段控件固定在 ScrollView 之外，与 04 配对页共用同一骨架。
        // 切换分段只换内容，顶部「‹ 添加电脑」这一行不再随转场动画或表单滚动而动。
        LinearLayout page = new LinearLayout(this);
        LinearLayout root = DshUi.fixedSegmentedPage(this, page, "添加电脑",
                new View.OnClickListener() {
                    @Override public void onClick(View v) { goUp(); }
                }, 1, new DshUi.OnIndexClick() {
                    @Override public void onIndex(int index) {
                        if (index == 0) replaceWith(new Intent(SshConfigActivity.this, PairActivity.class));
                    }
                });

        // ---- 导语 ----
        TextView lead = DshUi.label(this,
                "页面由那台电脑的 dsh web 经隧道提供，不启动手机本机引擎。",
                12.5f, DshUi.label(this), false);
        lead.setLineSpacing(DshUi.dp(this, 2), 1f);
        root.addView(lead, DshUi.matchWrapTop(this, 9));

        // ---- 卡片 1：主机地址 / SSH 端口 / 用户名（设计稿 .field）----
        LinearLayout card1 = DshUi.cardBox(this);
        root.addView(card1, DshUi.matchWrapTop(this, 12));

        hostInput = fieldBlock(card1, "主机地址", "100.99.226.70",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, null);

        LinearLayout row1 = fieldRow(card1);
        portInput = fieldCell(row1, "SSH 端口", "22", InputType.TYPE_CLASS_NUMBER, true);
        userInput = fieldCell(row1, "用户名", "dsh", InputType.TYPE_CLASS_TEXT, false);

        // ---- 端口映射：示意 + 两个真实端口 ----
        // 设计稿这一屏把「手机端口 ↔ 电脑端口」画成一张示意图，因为它最容易填反：
        // 手机端口是本机隧道入口，电脑端口是对端 dsh web 的监听口，两者没有默认对应关系。
        root.addView(DshUi.section(this, "端口映射"), DshUi.matchWrapTop(this, 14));

        LinearLayout card2 = DshUi.cardBox(this);
        root.addView(card2, DshUi.matchWrapTop(this, 6));

        mapBox = new LinearLayout(this);
        mapBox.setOrientation(LinearLayout.VERTICAL);
        card2.addView(mapBox, DshUi.matchWrap());
        refreshMap();

        LinearLayout row2 = fieldRow(card2);
        localPortInput = fieldCell(row2, "手机端口", "3081", InputType.TYPE_CLASS_NUMBER, true);
        targetPortInput = fieldCell(row2, "电脑端口", "3080", InputType.TYPE_CLASS_NUMBER, false);

        TextView mapHelp = DshUi.help(this, DshUi.emphasis(this,
                new String[] { "填电脑上 ", "dsh web 实际监听的端口", "，与手机本机引擎的 3080 无关。" },
                new boolean[] { false, true, false }));
        mapHelp.setPadding(DshUi.dp(this, 12), 0, DshUi.dp(this, 12), DshUi.dp(this, 9));
        card2.addView(mapHelp, DshUi.matchWrap());

        // ---- dsh web 令牌：掩码 + 显示开关 ----
        root.addView(DshUi.section(this, "dsh web 令牌"), DshUi.matchWrapTop(this, 14));

        LinearLayout card3 = DshUi.cardBox(this);
        root.addView(card3, DshUi.matchWrapTop(this, 6));

        LinearLayout tokenField = new LinearLayout(this);
        tokenField.setOrientation(LinearLayout.VERTICAL);
        tokenField.setPadding(DshUi.dp(this, 12), DshUi.dp(this, 9),
                DshUi.dp(this, 12), DshUi.dp(this, 9));
        tokenField.addView(DshUi.fieldLabel(this, "令牌", null), DshUi.matchWrap());

        LinearLayout tokenRow = new LinearLayout(this);
        tokenRow.setOrientation(LinearLayout.HORIZONTAL);
        tokenRow.setGravity(Gravity.CENTER_VERTICAL);
        tokenField.addView(tokenRow, DshUi.matchWrapTop(this, 5));

        tokenInput = DshUi.input(this, "从电脑端启动日志复制",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tokenInput.setLayoutParams(new LinearLayout.LayoutParams(
                0, DshUi.dp(this, 37), 1f));
        tokenRow.addView(tokenInput);

        final boolean[] visible = { false };
        final TextView showToken = DshUi.link(this, "显示", 11, DshUi.ink(this), true);
        showToken.setPadding(DshUi.dp(this, 10), 0, 0, 0);
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

        TextView tokenHelp = DshUi.help(this,
                "从电脑端启动日志复制；回环请求同样需要令牌。");
        tokenHelp.setPadding(0, DshUi.dp(this, 5), 0, 0);
        tokenField.addView(tokenHelp, DshUi.matchWrap());
        card3.addView(tokenField, DshUi.matchWrap());

        // ---- 「首次连接」两条口径（设计稿 .info ×2）----
        root.addView(DshUi.section(this, "首次连接"), DshUi.matchWrapTop(this, 14));
        root.addView(DshUi.infoCard(this, 1, DshUi.emphasis(this,
                new String[] { "核对", "主机指纹", "（TOFU）；之后指纹变化直接阻断。" },
                new boolean[] { false, true, false })), DshUi.matchWrapTop(this, 6));
        root.addView(DshUi.infoCard(this, 2, DshUi.emphasis(this,
                new String[] { "私钥只存这台手机；删除档案只清本地设置，", "不影响远端会话", "。" },
                new boolean[] { false, true, false })), DshUi.matchWrapTop(this, 7));

        // ---- 高级说明（可展开，不是死链接）----
        // 「测试连接」拿到的主机指纹、生成的本机公钥都放进这块折叠体里：
        // 它们是排查材料，常态下不该占页面正文（设计稿把这条收进了 fold）。
        final LinearLayout advancedBody = new LinearLayout(this);
        advancedBody.setOrientation(LinearLayout.VERTICAL);
        advancedBody.setVisibility(View.GONE);

        TextView advText = DshUi.help(this,
                "首次连接要把本机公钥登记到那台机器：本项目自带的 pc-sshd 读用户目录下的 "
                + ".dsh/ssh/authorized_keys，标准 sshd 读 ~/.ssh/authorized_keys。\n"
                + "主机公钥按 TOFU 钉住：首次见到会记录，之后不一致直接阻断。");
        advText.setPadding(0, 0, 0, 0);
        advancedBody.addView(advText, DshUi.matchWrap());

        fingerprintView = DshUi.mono(this, "", 11, DshUi.ink2(this), false);
        fingerprintView.setLineSpacing(DshUi.dp(this, 2), 1f);
        fingerprintView.setVisibility(View.GONE);
        advancedBody.addView(fingerprintView, DshUi.matchWrapTop(this, 8));

        pubkeyView = DshUi.mono(this, "", 11, DshUi.ink2(this), false);
        pubkeyView.setLineSpacing(DshUi.dp(this, 2), 1f);
        pubkeyView.setTextIsSelectable(true);   // 用户要把它复制到电脑上，必须能选中
        pubkeyView.setVisibility(View.GONE);
        advancedBody.addView(pubkeyView, DshUi.matchWrapTop(this, 8));

        final boolean[] open = { false };
        final TextView advanced = DshUi.link(this,
                "▸ 高级：公钥登记与 TOFU 指纹说明", 11, DshUi.ink(this), true);
        advanced.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                open[0] = !open[0];
                advancedBody.setVisibility(open[0] ? View.VISIBLE : View.GONE);
                advanced.setText((open[0] ? "▾" : "▸") + " 高级：公钥登记与 TOFU 指纹说明");
            }
        });
        root.addView(advanced, DshUi.matchWrapTop(this, 12));
        root.addView(advancedBody, DshUi.matchWrapTop(this, 8));

        // ---- 状态行（测试连接的结果就地显示在按钮上方）----
        statusView = DshUi.label(this, "", 12.5f, DshUi.ink(this), false);
        statusView.setLineSpacing(DshUi.dp(this, 2), 1f);
        statusView.setVisibility(View.GONE);
        root.addView(statusView, DshUi.matchWrapTop(this, 12));

        root.addView(DshUi.spacer(this));

        // ---- 底部双按钮（设计稿 .btnrow：等分，次按钮在左）----
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(actions, DshUi.matchWrapTop(this, 6));

        testButton = DshUi.button(this, "测试连接", DshUi.BTN_SECONDARY);
        testButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { testConnection(); }
        });
        actions.addView(testButton, DshUi.half(this, true));

        saveButton = DshUi.button(this, "保存并连接", DshUi.BTN_PRIMARY);
        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveAndConnect(); }
        });
        actions.addView(saveButton, DshUi.half(this, false));

        setContentView(page);
        try {
            getWindow().setStatusBarColor(DshPalette.systemBar(this));
            getWindow().getDecorView().setSystemUiVisibility(
                    DshPalette.systemUiFlags(this, getWindow().getDecorView().getSystemUiVisibility()));
        } catch (Throwable ignored) {
        }
    }

    /** 竖排字段块：标题 + 输入框 + 可选说明（设计稿 .field，整块带 12/9 内边距）。 */
    private EditText fieldBlock(LinearLayout parent, String labelText, String hint,
                                int inputType, CharSequence help) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(DshUi.dp(this, 12), DshUi.dp(this, 9),
                DshUi.dp(this, 12), DshUi.dp(this, 9));
        col.addView(DshUi.fieldLabel(this, labelText, null), DshUi.matchWrap());
        EditText in = DshUi.input(this, hint, inputType);
        col.addView(in, DshUi.matchWrapTop(this, 5));
        if (help != null) {
            TextView h = DshUi.help(this, help);
            h.setPadding(0, DshUi.dp(this, 5), 0, 0);
            col.addView(h, DshUi.matchWrap());
        }
        parent.addView(col, DshUi.matchWrap());
        return in;
    }

    /** 两列字段的横向容器；两格之间的竖发丝线由 {@link #fieldCell} 添加。 */
    private LinearLayout fieldRow(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        parent.addView(row, DshUi.matchWrap());
        return row;
    }

    /**
     * 两列字段中的一格（各占一半宽）。
     *
     * <p>中间的 1dp 竖线用一条独立 View 画，而不是给格子加右边框 ——
     * Android 的 View 没有 border，用「相邻两格内边距 + 一条线」才能两边都对齐。
     */
    private EditText fieldCell(LinearLayout row, String labelText, String hint,
                               int inputType, boolean left) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(DshUi.dp(this, 12), DshUi.dp(this, 9),
                DshUi.dp(this, 12), DshUi.dp(this, 9));
        cell.addView(DshUi.fieldLabel(this, labelText, null), DshUi.matchWrap());
        EditText in = DshUi.input(this, hint, inputType);
        cell.addView(in, DshUi.matchWrapTop(this, 5));
        row.addView(cell, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (left) {
            View line = new View(this);
            line.setBackgroundColor(DshUi.line(this));
            row.addView(line, new LinearLayout.LayoutParams(
                    Math.max(1, DshUi.dp(this, 1)),
                    LinearLayout.LayoutParams.MATCH_PARENT));
        }
        return in;
    }

    /**
     * 重画端口映射示意：两端文案都取输入框当前值（空则显示默认），
     * 让「我填的到底是哪一头」在图上直接对得上。
     */
    private void refreshMap() {
        if (mapBox == null) return;
        String local = localPortInput == null ? "" : localPortInput.getText().toString().trim();
        String target = targetPortInput == null ? "" : targetPortInput.getText().toString().trim();
        if (local.length() == 0) local = "3081";
        if (target.length() == 0) target = "3080";
        mapBox.removeAllViews();
        mapBox.addView(DshUi.mapRow(this, "手机 " + local, "电脑 " + target), DshUi.matchWrap());
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
