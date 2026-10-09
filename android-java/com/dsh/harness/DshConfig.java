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
}