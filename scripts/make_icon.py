#!/usr/bin/env python3
"""从用户提供的鲸鱼图生成 Android 图标资源。

流程：
  1. 裁掉右下角"AI生成"水印带，再居中取正方形；
  2. 按"低饱和 + 高亮度"抠掉浅灰底板 → 得到透明前景（鲸鱼）；
  3. 输出：
     - mipmap-*/ic_launcher.png        传统图标（灰底板 + 鲸鱼，完整构图）
     - mipmap-*/ic_launcher_round.png 同上（圆形遮罩由Android 处理）
     - mipmap-*/dsh_foreground.png     adaptive 前景（透明鲸鱼，缩到安全区内）
     - mipmap-anydpi-v26/ic_launcher.xml / ic_launcher_round.xml 指向 background+foreground
     - drawable/dsh_icon_background.xml 浅灰渐变背景层

鲸鱼在原图里的位置（1024x1024）：主体约 x 170..900、y 270..755，
水印在 y≈955 以下。因此先裁到 1024x944，再居中取 944x944 正方形。
"""
import os
import subprocess
import sys

from PIL import Image, ImageFilter

ICON = r"D:\Program AI\DSH Phone\icon"
SRC = os.path.join(ICON, "whale_src.png")
RES = r"D:\work\termux-app\app\src\main\res"

# 传统图标与 adaptive 前景的目标尺寸
DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}
FG_SCALE = 0.58   # 鲸鱼占 108dp 画布的比例，安全区是 66%（留余量避免尾部贴边）


def log(*a):
    print(*a)


def crop_square(src, dst, top_keep=944):
    """裁掉底部水印带 + 居中取正方形。"""
    im = Image.open(src).convert("RGB")
    w, h = im.size
    im = im.crop((0, 0, w, min(top_keep, h)))
    w, h = im.size
    side = min(w, h)
    left = (w - side) // 2
    im = im.crop((left, 0, left + side, side))
    im.save(dst)
    log(f"  裁正方形 -> {os.path.basename(dst)} {im.size}")
    return im


def cut_background(im):
    """抠掉浅灰底板：**从图像四边洪泛**，只删与边界连通的低饱和浅色像素。

    ⚠️ 不能逐像素按"低饱和 + 高亮度"判背景 —— 鲸鱼嘴里是**纯白的牙齿**，
    会被一起抠成透明（实测踩过：牙点像素变成 (255,255,255,0)）。
    洪泛法只处理"从外面能走到"的背景，牙齿这类被深色描边包住的内部浅色区自然保留。

    判据：max(r,g,b) >= 110 且 (max-min) <= 30，即"浅且几乎无彩色"。
    深藏青描边（低亮度）与浅青肚皮（高饱和）都不满足，安全。
    """
    from collections import deque

    im = im.convert("RGBA")
    w, h = im.size
    px = im.load()

    # 1) 标记候选背景像素
    cand = bytearray(w * h)
    for y in range(h):
        row = y * w
        for x in range(w):
            r, g, b = px[x, y][:3]
            mx = r if r > g else g
            if b > mx:
                mx = b
            mn = r if r < g else g
            if b < mn:
                mn = b
            if mx >= 110 and mx - mn <= 30:
                cand[row + x] = 1

    # 2) 从四边洪泛，把与边界连通的候选像素置为透明
    q = deque()

    def push(x, y):
        i = y * w + x
        if cand[i]:
            cand[i] = 0
            q.append(i)

    for x in range(w):
        push(x, 0)
        push(x, h - 1)
    for y in range(h):
        push(0, y)
        push(w - 1, y)

    while q:
        i = q.popleft()
        x = i % w
        y = i // w
        r, g, b, _ = px[x, y]
        px[x, y] = (r, g, b, 0)
        if x > 0:
            push(x - 1, y)
        if x < w - 1:
            push(x + 1, y)
        if y > 0:
            push(x, y - 1)
        if y < h - 1:
            push(x, y + 1)

    # 3) 极低 alpha 的碎屑清掉（阴影残留），再轻微羽化边缘
    alpha = im.getchannel("A").point(lambda v: 0 if v < 96 else v)
    alpha = alpha.filter(ImageFilter.GaussianBlur(0.6))
    im.putalpha(alpha)
    return im


def trim_bbox(im):
    bbox = im.getchannel("A").point(lambda v: 255 if v > 8 else 0).getbbox()
    return im.crop(bbox) if bbox else im


def main():
    square_path = os.path.join(ICON, "whale_square.png")
    crop_square(SRC, square_path)

    base = Image.open(square_path)
    square_side = base.size[0]

    # ---------- 传统图标：保留灰底板，缩放到各密度 ----------
    # adaptive 场景下这张不参与，但非 adaptive 设备/启动器会用到
    for dens, size in DENSITIES.items():
        out_dir = os.path.join(RES, "mipmap-" + dens)
        os.makedirs(out_dir, exist_ok=True)
        icon = base.resize((size, size), Image.LANCZOS)
        icon.save(os.path.join(out_dir, "ic_launcher.png"))
        icon.save(os.path.join(out_dir, "ic_launcher_round.png"))
    log(f"  传统图标 {len(DENSITIES)} 个密度 x2 完成")

    # ---------- adaptive 前景：透明鲸鱼 ----------
    whale = trim_bbox(cut_background(base))
    log(f"  抠图后鲸鱼尺寸 {whale.size}")

    # 前景画布 108dp，安全区 66dp；这里直接按目标像素算：
    # 前景位图尺寸 = mdpi 108px 基准 * 密度；鲸鱼缩到该画布的 FG_SCALE
    for dens, launcher_px in DENSITIES.items():
        out_dir = os.path.join(RES, "mipmap-" + dens)
        canvas_px = int(launcher_px * 108 / 48)      # 48px→108dp 画布
        target = max(1, int(canvas_px * FG_SCALE))
        scale = target / max(whale.size)
        nw, nh = max(1, int(whale.size[0] * scale)), max(1, int(whale.size[1] * scale))
        fg = whale.resize((nw, nh), Image.LANCZOS)
        canvas = Image.new("RGBA", (canvas_px, canvas_px), (0, 0, 0, 0))
        canvas.paste(fg, ((canvas_px - nw) // 2, (canvas_px - nh) // 2), fg)
        canvas.save(os.path.join(out_dir, "dsh_foreground.png"))
    log(f"  adaptive 前景 {len(DENSITIES)} 个密度完成（画布 = 108dp，安全区内）")

    # ---------- adaptive 背景层 ----------
    draw_dir = os.path.join(RES, "drawable")
    os.makedirs(draw_dir, exist_ok=True)
    bg_xml = os.path.join(draw_dir, "dsh_icon_background.xml")
    with open(bg_xml, "w", encoding="utf-8") as f:
        f.write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<shape xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:shape="rectangle">\n'
            '    <gradient\n'
            '        android:type="linear"\n'
            '        android:angle="270"\n'
            '        android:startColor="#EFEFF1"\n'
            '        android:centerColor="#E2E3E6"\n'
            '        android:endColor="#CDD0D4" />\n'
            '</shape>\n'
        )
    log(f"  背景层 -> {os.path.basename(bg_xml)}")

    # ---------- adaptive icon xml ----------
    anydpi = os.path.join(RES, "mipmap-anydpi-v26")
    os.makedirs(anydpi, exist_ok=True)
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        with open(os.path.join(anydpi, name), "w", encoding="utf-8") as f:
            f.write(
                '<?xml version="1.0" encoding="utf-8"?>\n'
                '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@drawable/dsh_icon_background" />\n'
                '    <foreground android:drawable="@mipmap/dsh_foreground" />\n'
                '</adaptive-icon>\n'
            )
    log("  adaptive icon xml: ic_launcher.xml / ic_launcher_round.xml")

    # 供人工核对
    whale.save(os.path.join(ICON, "whale_fg_preview.png"))
    log(f"\n完成。原图 {square_side}x{square_side}，预览：whale_fg_preview.png")


if __name__ == "__main__":
    main()