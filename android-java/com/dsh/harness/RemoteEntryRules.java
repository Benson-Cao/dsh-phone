package com.dsh.harness;

/**
 * 「进入这台电脑」的档案分流规则（纯函数，桌面断言覆盖，零 Android 依赖）。
 *
 * <p>为什么要有它：r113 的按需连接守卫会在远端档启动时停在 OfflineActivity 的
 * choice 页。早年版 `enterRemote()` 无条件 `setRelay(...) + setCurrent(relay)`，
 * 于是 SSH 档用户点一次「进入这台电脑」，档案就被悄悄改成 Relay 档；
 * 接着去连一个根本没配置的 Relay（`relayUrl` 形如 `http://<电脑局域网IP>:8899`），
 * 界面报「连不上这台电脑」，而且失败页显示的是 Relay 地址与 Relay 原因 ——
 * 排查方向整个被带偏。
 *
 * <p>⚠️ 8899 是**电脑端** Relay 的监听地址，不是手机本地端口。手机侧任何地方
 * 都不该出现 `127.0.0.1:8899` —— 手机上的 127.0.0.1 指的是手机自己。
 * 这个归属在 p1/relay/src/server.js 的 `requestOrigin()` 里定死了：relayUrl 由
 * 客户端实际使用的 Host 头反推，所以手机端填的「电脑地址」就是这一项。
 *
 * <p>按需连接守卫只是「要不要自动连」，不该改变「连的是哪一种档案」。
 */
public final class RemoteEntryRules {

    public static final String KIND_SSH = "ssh";

    private RemoteEntryRules() {}

    /**
     * @param currentKind {@code HostProfile.Profile.kind}
     * @return true = 保持当前档案原样，只跳过按需连接守卫直接进主界面；
     *         false = 走 Relay 三要素（地址 / 主机 ID / 令牌）补齐并切档的老流程
     */
    public static boolean keepCurrentProfile(String currentKind) {
        return KIND_SSH.equals(currentKind);
    }
}
