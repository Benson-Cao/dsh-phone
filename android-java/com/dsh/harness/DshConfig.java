package com.dsh.harness;

/**
 * 全局可调常量（C-02：原先散落各类的版本号 / registry 地址收敛到一处）。
 *
 * 为什么要集中：这些值跟着上游发布走（npm 包版本、registry 地址）。
 * 散落在 {@link DshMarketInstaller} 里时，升级要翻多个文件、极易漏改；
 * 而漏改的表现是「安装静默失败」——不是编译期能发现的问题，只能靠真机撞。
 *
 * 发布 checklist：升级 dshmarket / pnpm 时**只改这里**。
 * 若同时改了 bundle 内插件的布局，还需递增 {@link DshBootstrap#BUNDLE_REV} 触发重新解包。
 */
public final class DshConfig {

    private DshConfig() {}

    // ===== dshmarket（插件市场本体）=====

    public static final String MARKET_PKG = "dshmarket";

    /** dshmarket 版本。上游发版后改这里。 */
    public static final String MARKET_VERSION = "1.66.11";

    // ===== pnpm（市场装插件的依赖）=====
    // 选 12.10.1 的原因：3.9MB、零依赖、纯 JS（bin/pnpm.mjs）、要求 node>=18。

    /** pnpm 版本。上游发版后改这里。 */
    public static final String PNPM_VERSION = "12.10.1";

    // ===== registry =====
    // 国内直连 registry.npmjs.org 慢/不稳时可换成镜像（如 https://registry.npmmirror.com），
    // 无需改任何调用点。

    public static final String NPM_REGISTRY = "https://registry.npmjs.org";

    public static final String MARKET_TARBALL =
        NPM_REGISTRY + "/" + MARKET_PKG + "/-/" + MARKET_PKG + "-" + MARKET_VERSION + ".tgz";

    public static final String PNPM_TARBALL =
        NPM_REGISTRY + "/pnpm/-/pnpm-" + PNPM_VERSION + ".tgz";

    // ===== C-08 · tarball 完整性校验 =====
    // 从 registry 下载的 tarball 过去是「下载即由 node 解包执行」，中间没有任何
    // 完整性校验 —— 一旦 registry 被劫持或流量被改写，执行的代码就是攻击者的。
    // 现在：下载后、解包**之前**比对 SHA-512，不一致直接失败。
    //
    // 怎么来的（实测值，非抄上游）：
    //   curl -sL <tarball-url> | sha512sum
    // ⚠️ 升级 MARKET_VERSION / PNPM_VERSION 时**必须**同步更新这两个常量，
    //    否则安装会直接失败 —— 这是有意为之的 fail-safe，宁可装不上也不能
    //    装上未经校验的代码。
    //
    // 顺带修正一处不实描述：pnpm 的 tarball 实测 1,034,388 字节（≈1.03MB），
    // 原注释写的 3.9MB 是解包后的大小。

    public static final String MARKET_TGZ_SHA512 =
        "23eaa61afca8f208ea9bf5b45a643e6a91dc9e0dab74af1909eb273d400cfa98a"
        + "18ed1f5efbecba47d85a4d264d6cf8e5303c3bfd097c780202501a7c675db36";

    public static final String PNPM_TGZ_SHA512 =
        "ba40a37eb1a370d60fea8c4cf1d364c13bcccef518e98ad2bcee954f539d38da3"
        + "7458e58261a3fa55e06523450ea0ffff9f497b135be41f2c15c1642bacace6c";
}