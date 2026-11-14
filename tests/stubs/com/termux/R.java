// 编译期桩（compile-only scaffolding）—— 不参与打包与运行，详见本目录说明。
package com.termux;

/**
 * 桩：Termux 的资源索引类（gradle 生成）。
 *
 * <p>只补本仓库实际引用到的条目：{@code R.anim.slide_in_right} /
 * {@code R.anim.slide_out_left}。真实工程里这些值是 aapt 生成的 int。
 */
public final class R {
    public static final class anim {
        public static final int slide_in_right = 1;
        public static final int slide_out_left = 2;
    }

    private R() {}
}
