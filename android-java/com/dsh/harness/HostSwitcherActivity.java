package com.dsh.harness;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.InetSocketAddress;
import java.net.Socket;
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

    private TextView hostsSection;
    private LinearLayout listBox;
    private TextView refreshButton;
    private TextView diagBar;
    /** 本机 / SSH 两张卡各留引用：探测结果回来后只重建卡内那一行，不整页重画。 */
    private LinearLayout localCard;
    private LinearLayout sshCard;

    private String relayOrigin;
    private String token;
    /** 这台 SSH 主机当前不可用（失败页置位 + 本页 TCP 探测结果）。 */
    private boolean sshLinkFailed;
    /** SSH 主机 22 端口是否可达（null = 正在探测）。只写进副标题，不决定标签。 */
    private Boolean sshPortReachable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        relayOrigin = DshDeviceIdentity.lastRelayUrl(this);
        token = DshTokenStore.load(this);
        sshLinkFailed = LinkState.sshFailed(this);

        // r145：上次 SSH 连接已确认失败时，这一页不能再把那台机器显示成「正在使用」。
        // 直接回落到本机引擎：连不上的档留着只会停在失败页；SSH 配置仍在，
        // 随时可从下面的「SSH 直连」卡切回去（switchToSsh 会清掉失败标记）。
        // r147：失败标记**保留**——它是这台主机的事实，回落后下一屏还得据此显示「离线」。
        if (HostProfile.KIND_SSH.equals(HostProfile.get(this).current().kind) && sshLinkFailed) {
            toast("SSH 连接失败，已切回本机引擎。");
            switchToLocal();
            return;
        }
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

        // r144：这里原本有一条常驻状态行，出错时套近黑虚线框。本机档下 /api/hosts
        // 本来就会连不上电脑端 Relay，那条「连不上电脑」是纯粹的误报；按真机反馈整块删除。
        // 操作反馈改走 Toast（不占布局、不常驻），链路体检仍由下面的诊断条承担。

        // ---- 本机：这台手机自己也是一个「执行位置」----
        // 设计稿修订版 §4.7 曾把本机从这一页摘掉（理由是「选一台电脑」和「切执行位置」
        // 混在同一张卡里）。真机上暴露出反向问题：人在远端档案里想切回本机时，
        // 这一页**没有任何本机条目** —— 只能退出应用，再去 08 失败页走兜底路径。
        // 现在单独成组（不并进「已绑定」那张卡），两块各说一件事，互不混淆。
        // 位置在本机优先：它是**此刻就有**的那一项，而远端清单可能是 0 台。
        root.addView(DshUi.section(this, "本机"), DshUi.matchWrapTop(this, 12));
        localCard = DshUi.cardBox(this);
        localCard.addView(localRow());
        root.addView(localCard, DshUi.matchWrapTop(this, 2));

        // ---- SSH 直连主机：不经 Relay，/api/hosts 里根本不会有它 ----
        // r141 补了这一行，但当时取的是 current()，只有「当前档就是 ssh」才画 ——
        // 于是 r144 真机反馈「从 ssh 切换到本机后，ssh 连接就消失了」。
        // setCurrent(local) 只换当前档指针，ssh 配置仍在 dsh_profiles.xml；
        // savedSsh() 与当前档无关，用它常驻渲染。r147：只要当前档不是 ssh，
        // 就说明此刻没有连到这台机器，标签一律「离线」，不再给「可切换」的假希望。
        // 端口可达性只作为副标题的诊断信息（能连 22 端口 ≠ SSH 认证能过）。
        HostProfile.Profile ssh = HostProfile.get(this).savedSsh();
        if (ssh != null) {
            root.addView(DshUi.section(this, "SSH 直连"), DshUi.matchWrapTop(this, 12));
            sshCard = DshUi.cardBox(this);
            sshCard.addView(directRow(ssh));
            root.addView(sshCard, DshUi.matchWrapTop(this, 2));
        }

        // ---- 已绑定：远端电脑清单 ----
        hostsSection = DshUi.section(this, "已绑定");
        root.addView(hostsSection, DshUi.matchWrapTop(this, 12));
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(listBox, DshUi.matchWrapTop(this, 2));

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

        // r147：底部「返回应用」按真机反馈删除。应用栏左上角已有「‹」承担返回，
        // 同一个动作在两处出现、中间还隔着两张按钮，只会让人以为下面那个另有用意。
        // 「连 SSH 主机」早前按设计稿 03 删除，这里也不留替补卡。

        setContentView(scroll);
        try {
            getWindow().setStatusBarColor(DshPalette.systemBar(this));
            getWindow().getDecorView().setSystemUiVisibility(
                    DshPalette.systemUiFlags(this, getWindow().getDecorView().getSystemUiVisibility()));
        } catch (Throwable ignored) {
        }
    }

    // 这里原本有 factRow()（「归属与在线口径」三行）。那张卡按设计稿修订版 §4.7/§4.8
    // 删除，行构造函数一并移除 —— 留着不用等于给下一个人「这里还有一块 UI」的错觉。

    /** 拉清单 + 探测 SSH 主机（后台线程）。缺凭据时静默收起「已绑定」，不弹「连不上电脑」。 */
    private void reload() {
        diagBar.setText(diagText());
        // 每次进页 / 点「刷新状态」都重新判一次 SSH 主机可达性：电脑恢复后
        // 标签能自己从「离线」变回「可切换」，不必先切过去失败一次。
        probeSsh();
        if (relayOrigin == null || token == null) {
            // 本机 / SSH 档下没配对过、令牌不可用都是常态；这条清单本来就是可选的。
            hostsSection.setVisibility(View.GONE);
            listBox.removeAllViews();
            setRefreshEnabled(false);
            return;
        }
        setRefreshEnabled(false);
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
            // 拉不到远端清单不是「用户做错了什么」：当前执行位置可能就是本机 / SSH，
            // 电脑端 Relay 根本不必在跑。静默收起这一节，链路体检交给常驻诊断条。
            hostsSection.setVisibility(View.GONE);
            return;
        }
        List<DshHostsClient.Host> rows = r.rows;
        if (rows == null || rows.isEmpty()) {
            hostsSection.setVisibility(View.GONE);
            listBox.addView(emptyCard("还没有绑定电脑", "点下面的「添加电脑」，用配对码或 SSH 连一台；上面的「本机」随时可用。"));
            return;
        }
        hostsSection.setVisibility(View.VISIBLE);
        hostsSection.setText("已绑定 · " + rows.size());
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

    /**
     * 本机一行：方块头像 + 名字/摘要 + 状态标签。
     *
     * <p>与远端行同构是刻意的 —— 用户在这一页做的判断是「在哪台机器上继续」，
     * 本机只是其中一台，视觉上不该是另一套东西。
     */
    private View localRow() {
        HostProfile.Profile cur = HostProfile.get(this).current();
        boolean inUse = HostProfile.KIND_LOCAL.equals(cur.kind);

        LinearLayout row = DshUi.rowBox(this);
        row.addView(DshUi.avatar(this, "本", 0), avatarLp());
        row.addView(deviceColumn("这台手机",
                "127.0.0.1:" + DshProcessManager.DSH_PORT + " · 只绑回环 · 本机引擎", null));
        row.addView(inUse ? DshUi.tagOn(this, "正在使用")
                : DshUi.tagWait(this, sshLinkFailed ? "切回本机" : "可切换"));

        // 已经在本机时不给点击：同档切换是无操作，点了只会让人怀疑没生效。
        if (inUse) return row;
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 失败态一步直达，不再多一次确认；正常态保留确认（不迁移会话的口径要说到）。
                if (sshLinkFailed) switchToLocal();
                else confirmSwitchLocal();
            }
        });
        DshUi.pressable(row);
        return row;
    }

    /** SSH 直连一行：档案里就有全部信息，不需要网络请求；与当前档无关，始终显示。 */
    private View directRow(final HostProfile.Profile p) {
        final boolean inUse = HostProfile.KIND_SSH.equals(HostProfile.get(this).current().kind);
        LinearLayout row = DshUi.rowBox(this);
        String host = p.sshHost == null || p.sshHost.isEmpty() ? "?" : p.sshHost;
        row.addView(DshUi.avatar(this, host.substring(0, 1), 0), avatarLp());
        String meta = "127.0.0.1:" + p.localPort + " → 电脑:" + p.sshTargetPort + " · ssh 隧道"
                + (sshPortReachable == null ? " · 检测中"
                        : (sshPortReachable ? " · 22 端口可达" : " · 主机不可达"));
        row.addView(deviceColumn(p.sshUser + "@" + host, meta, null));
        // r147（真机二次修正）：**只有当前档就是 ssh 且隧道真通**才算「正在使用」。
        // 只要当前档是本机/Relay，这条隧道此刻就不存在 —— 标签必须写「离线」。
        // 首版曾用「TCP 探 22 端口是否通」决定「可切换 ↔ 离线」，真机上被证伪：
        // 100.99.226.70:22 一直通，卡的是公钥没被 authorized_keys 接受，
        // 失败页明确写着「隧道没有建起来」，切换器却说「可切换」，自相矛盾。
        View tag = inUse
                ? (sshLinkFailed ? DshUi.tagOff(this, "连接失败") : DshUi.tagOn(this, "正在使用"))
                : DshUi.tagOff(this, "离线");
        row.addView(tag);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (inUse) {
                    toast(p.sshUser + "@" + p.sshHost
                            + " 是当前档案指向的机器（SSH 直连，不经 Relay 清单）。");
                } else {
                    confirmSwitchSsh(p);
                }
            }
        });
        DshUi.pressable(row);
        return row;
    }

    /** 只重画 SSH 卡内那一行（探测回来 / 标签状态变化时用）。 */
    private void refreshSshCard() {
        if (sshCard == null) return;
        HostProfile.Profile p = HostProfile.get(this).savedSsh();
        sshCard.removeAllViews();
        if (p != null) sshCard.addView(directRow(p));
    }

    /** 本机卡的标签也受失败标记影响（「切回本机」↔「可切换」）。 */
    private void refreshLocalCard() {
        if (localCard == null) return;
        localCard.removeAllViews();
        localCard.addView(localRow());
    }

    /**
     * 后台探测 SSH 主机可达性：只对 sshHost:sshPort 做一次 TCP 握手，不认证、不读数据。
     *
     * <p>为什么还要探：端口不可达是比「上次认证失败」更前置的故障，写进副标题
     * 能让失败页那四条检查项有侧重点（先看网络还是先看公钥）。
     *
     * <p><b>关键口径（真机实测修正）</b>：探测结果<strong>只进副标题，不决定标签</strong>。
     * 100.99.226.70:22 一直是通的，卡住的是公钥未被 authorized_keys 接受；
     * 拿 TCP 握手当「可切换」的依据会与失败页自相矛盾。端口不通时顺手置失败标记，
     * 端口通不动任何已记录的失败证据。超时 1.2s，不阻塞界面，不进 UI 线程。
     */
    private void probeSsh() {
        final HostProfile.Profile p = HostProfile.get(this).savedSsh();
        if (p == null || p.sshHost == null || p.sshHost.isEmpty()) return;
        final int port = p.sshPort > 0 ? p.sshPort : 22;
        sshPortReachable = null;
        refreshSshCard();
        new Thread(new Runnable() {
            @Override public void run() {
                Socket s = null;
                boolean reachable = false;
                try {
                    s = new Socket();
                    s.connect(new InetSocketAddress(p.sshHost, port), 1200);
                    reachable = true;
                } catch (Throwable ignored) {
                    // 超时 / 无路由 / 拒绝连接都等同「这台主机现在连不上」
                } finally {
                    try { if (s != null) s.close(); } catch (Throwable ignored) { }
                }
                final boolean ok = reachable;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        sshPortReachable = ok;
                        if (!ok) {
                            // 连 22 端口都不通：这台主机现在确实连不上。
                            LinkState.markSshFailed(HostSwitcherActivity.this);
                            sshLinkFailed = true;
                        }
                        // ok 时不动失败标记：端口通只说明网络层没断，不能推翻
                        // 「上次隧道没建起来」这个已被失败页确认过的事实。
                        refreshSshCard();
                        refreshLocalCard();
                    }
                });
            }
        }, "dsh-ssh-probe").start();
    }

    /** 重连已存的 SSH 档：当前是离线态，先把「可能仍连不上」说在前面。 */
    private void confirmSwitchSsh(final HostProfile.Profile p) {
        String lead = sshLinkFailed
                ? "上次连接没成功，现在这台主机仍是离线状态，切过去可能停在连接失败页。\n\n"
                : "";
        // r148：统一圆角卡片 + 项目单色按钮，替掉系统方角灰卡与红字按钮。
        DshDialog.with(this)
                .title("重连这台 SSH 主机？")
                .message(lead + "重新连到 " + p.sshUser + "@" + p.sshHost + "，"
                        + "会话与文件各自留在原来的机器上，不会互相带走。")
                .negative("取消", null)
                .positive("重试", new DshDialog.Action() {
                    @Override public void run() { switchToSsh(); }
                })
                .show();
    }

    private void switchToSsh() {
        HostProfile hp = HostProfile.get(this);
        // 用户主动重连：旧失败标记不能留着，否则 MainActivity 尝试连隧道期间
        // 再打开切换器会被判成「又失败了」而切回本机。
        LinkState.clearSshFailed(this);
        if (!hp.setCurrent(HostProfile.KIND_SSH)) {
            toast("切不回 SSH：已存档案不可用，去「添加电脑」重新填一次。");
            return;
        }
        // 与 SshConfigActivity「保存并连接」同一条路径：归零 cookie + 预热，
        // 并重启到全新 MainActivity（带 enterRemote 跳过按需连接守卫）。
        ProfileSession.resetRuntime();
        ProfileSession.restartInto(this, new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_ENTER_REMOTE, true));
    }

    /** 切到本机前确认一次：口径与远端切换一致（不迁移会话）。 */
    private void confirmSwitchLocal() {
        DshDialog.with(this)
                .title("切到本机引擎？")
                .message("本机是这台手机上另跑的一份 dsh，会话与文件都在本机。"
                        + "切过去不会带走电脑上的会话，也不会删除电脑上的任何东西。")
                .negative("取消", null)
                .positive("切到本机", new DshDialog.Action() {
                    @Override public void run() { switchToLocal(); }
                })
                .show();
    }

    private void switchToLocal() {
        // r147：不清失败标记。切到本机是换执行位置，不是把那台电脑修好了；
        // 下一屏的「SSH 直连」卡要继续如实显示「离线」。
        HostProfile hp = HostProfile.get(this);
        if (!hp.setCurrent(HostProfile.KIND_LOCAL)) {
            toast("切不到本机：本机档案不可用。");
            return;
        }
        // 切档必须归零 cookie 与预热实例（R-22：防跨主机串会话）
        ProfileSession.resetRuntime();
        // r144：原来用 finish()+startActivity()，栈里旧 MainActivity 不会重建，
        // 返回键还能回到切档前的档案。统一走 restartInto（与 08 页 useLocal 一致）。
        ProfileSession.restartInto(this, new Intent(this, MainActivity.class));
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
                    toast(displayName(h) + " 已经是这台手机的绑定目标"
                            + "（归属，不代表此刻正在使用）。");
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

    // ---- 操作反馈 ----

    /**
     * 轻量反馈：r144 起顶部不再有常驻状态行（虚线错误框已删除）。
     * 只用于「用户刚做的那个动作结果如何」，链路状态归下面的诊断条。
     */
    private void toast(String msg) {
        if (msg == null || msg.isEmpty()) return;
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
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

        DshDialog.with(this)
                .title("连接诊断")
                .message(sb.toString())
                .negative("复制", new DshDialog.Action() {
                    @Override public void run() { copyToClipboard(diagText()); }
                })
                .positive("关闭", null)
                .show();
    }

    private void copyToClipboard(String text) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(
                    android.content.ClipData.newPlainText("dsh-diagnostics", text));
            toast("诊断摘要已复制。");
        } catch (Throwable t) {
            toast("复制失败：" + t.getMessage());
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
        DshDialog.with(this)
                .title("切换到「" + label + "」？")
                .message("切换主机不会带走会话、文件与插件。\n"
                        + "切过去之后，界面会重建并显示这台电脑自己的内容。")
                .negative("取消", null)
                .positive("切换", new DshDialog.Action() {
                    @Override public void run() { doSwitch(h); }
                })
                .show();
    }

    private void doSwitch(final DshHostsClient.Host h) {
        // 刷新按钮压暗已经是加载态，不再往页面顶部塞一条常驻文字。
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
            toast(f.title + "：" + f.action);
            return;
        }
        // 服务端已重绑（令牌不变、绑定变）。本地档案要跟上，否则重启又回到旧主机。
        String bound = r.hostId != null ? r.hostId : h.hostId;
        HostProfile hp = HostProfile.get(this);
        hp.setRelay(relayOrigin, bound, token);
        if (!hp.setCurrent(HostProfile.KIND_RELAY)) {
            toast("切换已生效，但本地档案没保存成功；重启后可能需要再切一次。");
            return;
        }
        ProfileSession.resetRuntime();
        // 同 SshConfigActivity：必须重启到新实例，否则主界面复用旧档案、仍是上一台主机
        ProfileSession.restartInto(this, new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_ENTER_REMOTE, true));
    }
}
