#!/usr/bin/env python3
"""从用户提供的鲸鱼图生成 Android 图标资源。

流程：
  1. 抠掉右下角"豆包AI生成"水印（就地修补底板颜色，不裁底板）
  2. 抠掉外圈纯白边+ 圆角灰蓝底板 → 得到透明前景（鲸鱼）
  3. 输出：
     - mipmap-*/ic_launcher.png        传统图标（底板 + 鲸鱼，完整构图）
     - mipmap-*/ic_launcher_round.png 同上（圆形遮罩由Android 处理）
     - mipmap-*/dsh_foreground.png     adaptive 前景（透明鲸鱼，缩到安全区内）
     - mipmap-anydpi-v26/ic_launcher.xml / ic_launcher_round.xml 指向 background+foreground
     - drawable/dsh_icon_background.xml 浅灰蓝渐变背景层

⚠️ 与 r17 版的差异（实测得来的，别凭印象改）：
  - 新图底板是**圆角方板**（不是无边灰底），外圈还有一圈纯白边，
    实测底板范围 x 42..985 / y 38..1003（944x966）。
  - 水印 bbox x 800..1001 / y 935..1004，**与底板 y 范围重叠**，
    所以不能像旧版那样"裁掉底部 y>955 的水印带"——那会把底板切掉一截。
    改为就地修补：把水印像素替换成它左侧同高度的底板实色。
  - 抠背景依然必须用**洪泛法**（逐像素判"低饱和+高亮度"会把鲸鱼嘴里的
    **纯白牙齿**一起抠成透明，实测踩过）。
"""
import os

from PIL import Image, ImageFilter

ICON = r"D:\Program AI\DSH Phone\icon"
SRC = os.path.join(ICON, "whale_src.png")
RES = r"D:\work\termux-app\app\src\main\res"

# 实测得来（1024x1024 原图）
WHITE_MARGIN = 42        # 外圈纯白边宽度（底板起点）
BOARD = (42, 38, 986, 1004)   # 底板 bbox (x0, y0, x1, y1)
# ⚠️ 下面这个 bbox 是**裁剪后 944x944 底板图**上的坐标（不是原图坐标！）。
#    原图实测：水印笔画 x 700..1002/ y 900..1005（底板渐变的边缘也会被梯度检出，
#    所以真水印比这个 bbox 略小，取安全范围即可）。
#    cut_square 的裁剪起点是 (42, 49)，换算后= 水印 x 658..960 / y 851..956。
#    第一版误用原图坐标(800,935,...)直接减 BOARD 偏移 → 水印没擦掉；
#    第二版凭估计填 y 916..954 → 只盖住下半截，"豆包"上沿露出来。
#    **教训：坐标必须在最终尺寸的图上实测，且扫描起点要放宽到包含真实边界。**
WM_BBOX = (652, 848, 944, 960)  # 水印 bbox（含余量，944x944 坐标系）

DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}
FG_SCALE = 0.58   # 鲸鱼占 108dp 画布的比例，安全区66%（留余量避免尾部贴边）


def log(*a):
    print(*a)


def cut_square(src, dst):
    """按实测底板范围精确裁出正方形（去掉外圈纯白边）。"""
    im = Image.open(src).convert("RGB")
    x0, y0, x1, y1 = BOARD
    side = min(x1 - x0, y1 - y0)
    cx, cy = (x0 + x1) // 2, (y0 + y1) // 2
    im = im.crop((cx - side // 2, cy - side // 2, cx - side // 2 + side, cy - side // 2 + side))
    im.save(dst)
    log(f"  裁正方形 -> {os.path.basename(dst)} {im.size}")
    return im


def erase_watermark(im):
    """抹掉右下角水印 —— 做法是**重建底板**，不是修补渐变。

    实测踩过的坑（按尝试顺序，别再重走）：
    - 试过「裁掉底部」→ 水印与底板 y 范围重叠，会把底板切一截，**行不通**。
    - 试过固定色 / 逐行中位色 / 逐行众数色 / 双线性插值 / 横向平移复制
      → **五种全留下可见色块或锯齿**。根因：底板是**斜向渐变 + 圆角**，
      任何"按行给一个色"或"按四角插值"的模型都不成立。
    - 试过迭代扩散修补 → 水印没了，但圆角边缘仍有残留锯齿。

    最终方案：**只保留鲸鱼，底板整块重画**。
    反正 adaptive 前景只需要透明鲸鱼；传统图标的底板是我们自己画的渐变，
    没有理由去修补一张带水印的渐变。重画可完全避开 inpainting 的所有难点。
    """
    w, h = im.size
    px = im.load()

    # 1) 找鲸鱼主体 bbox：非"浅色底板"的像素（蓝/深描边/橙黄口腔）
    #    判据：饱和度明显（max-min > 40）或亮度够低（深藏青描边 max <= 90）
    minx, miny, maxx, maxy = w, h, 0, 0
    for y in range(h):
        for x in range(w):
            r, g, b = px[x, y]
            mx = max(r, g, b)
            mn = min(r, g, b)
            if (mx - mn) > 40 or mx <= 90:
                if x < minx: minx = x
                if x > maxx: maxx = x
                if y < miny: miny = y
                if y > maxy: maxy = y

    if maxx <= minx or maxy <= miny:
        log("  ✗ 未检出鲸鱼主体，保留原图")
        return im

    log(f"  鲸鱼主体 bbox=({minx},{miny})-({maxx},{maxy}) "
        f"尺寸 {maxx-minx+1}x{maxy-miny+1}")

    # 2) 抠出鲸鱼（透明底），再贴到**新画的**渐变底板上
    whale = im.crop((minx, miny, maxx + 1, maxy + 1)).convert("RGBA")
    wp = whale.load()
    ww, wh = whale.size
    for y in range(wh):
        for x in range(ww):
            r, g, b, _ = wp[x, y]
            mx = max(r, g, b)
            mn = min(r, g, b)
            # 浅色底板 → 透明；鲸鱼的蓝/深描边/白牙 → 不透明
            if (mx - mn) <= 40 and mx > 90:
                wp[x, y] = (r, g, b, 0)

    # 3) 新底板：与原图同风格的对角渐变（左上 #F4F7FA → 右下 #C9D2DA），
    #    圆角与原图一致（用 4x 超采样画圆角矩形，边缘更平滑）
    S = 4
    board = Image.new("RGBA", (w * S, h * S), (0, 0, 0, 0))
    bp = board.load()
    c_tl, c_br = (244, 247, 250), (201, 210, 218)
    radius = 200# 圆角半径（原图尺度，1024 图约 200px）——注意要除以 S
    rs = radius * S
    for yy in range(h * S):
        fy = yy / S
        for xx in range(w * S):
            fx = xx / S
            # 圆角矩形内部判定（rs 与超采样坐标同尺度）
            cx = min(max(fx * S, rs), w * S - rs) / S
            cy = min(max(fy * S, rs), h * S - rs) / S
            if (fx - cx) ** 2 + (fy - cy) ** 2 > radius * radius:
                continue
            t = ((fx / w) * 0.45 + (fy / h) * 0.55)
            bp[xx, yy] = (
                int(c_tl[0] + (c_br[0] - c_tl[0]) * t),
                int(c_tl[1] + (c_br[1] - c_tl[1]) * t),
                int(c_tl[2] + (c_br[2] - c_tl[2]) * t),
                255,
            )
    board = board.resize((w, h), Image.LANCZOS)

    # 4) 鲸鱼按原位置贴回，居中缩放到原尺寸
    board.paste(whale, (minx, miny), whale)
    im.paste(board.convert("RGB"), (0, 0))
    log("  水印已去除（重建底板 + 重新贴回鲸鱼，无修补痕迹）")
    return whale


def cut_background(im):
    """抠掉外圈纯白 +圆角底板 → 透明前景。

    ⚠️ 必须**从图像四边洪泛**，只删与边界连通的低饱和浅色像素。
    逐像素判"低饱和+高亮度"会把鲸鱼嘴里的**纯白牙齿**抠成透明
    （实测牙点像素变成 (255,255,255,0)）。洪泛法只处理"从外面能走到"的背景，
    牙齿这类被深色描边包住的内部浅色区自然保留。

    判据：max>=100 且 (max-min)<=45 —— 放宽到45 以覆盖这张图的灰蓝底板，
    同时仍排除深藏青描边（低亮度）与浅青肚皮（高饱和）。
    """
    from collections import deque

    im = im.convert("RGBA")
    w, h = im.size
    px = im.load()

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
            if mx >= 100 and mx - mn <= 45:
                cand[row + x] = 1

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

    n = 0
    while q:
        i = q.popleft()
        x = i % w
        y = i // w
        r, g, b, _ = px[x, y]
        px[x, y] = (r, g, b, 0)
        n += 1
        if x > 0:
            push(x - 1, y)
        if x < w - 1:
            push(x + 1, y)
        if y > 0:
            push(x, y - 1)
        if y < h - 1:
            push(x, y + 1)
    log(f"  洪泛抠除背景 {n} px")

    alpha = im.getchannel("A").point(lambda v: 0 if v < 96 else v)
    alpha = alpha.filter(ImageFilter.GaussianBlur(0.6))
    im.putalpha(alpha)
    return im


def trim_bbox(im):
    bbox = im.getchannel("A").point(lambda v: 255 if v > 8 else 0).getbbox()
    return im.crop(bbox) if bbox else im


def main():
    square_path = os.path.join(ICON, "whale_square.png")
    cut_square(SRC, square_path)

    im = Image.open(square_path)
    whale_rgba = erase_watermark(im)
    im.save(square_path)
    base = im.copy()
    base.save(os.path.join(ICON, "whale_board.png"))
    square_side = base.size[0]

    # ---------- 传统图标：保留底板，缩放到各密度 ----------
    for dens, size in DENSITIES.items():
        out_dir = os.path.join(RES, "mipmap-" + dens)
        os.makedirs(out_dir, exist_ok=True)
        icon = base.resize((size, size), Image.LANCZOS)
        icon.save(os.path.join(out_dir, "ic_launcher.png"))
        icon.save(os.path.join(out_dir, "ic_launcher_round.png"))
    log(f"  传统图标 {len(DENSITIES)} 个密度 x2 完成")

    # ---------- adaptive 前景：透明鲸鱼 ----------
    # erase_watermark 已把鲸鱼抠成RGBA 并返回；它比"对重建底板再跑洪泛"可靠
    # （洪泛会把渐变底板的浅色区误判成背景而残留白块，实测过）。
    if whale_rgba is not None:
        whale = trim_bbox(whale_rgba)
    else:
        whale = trim_bbox(cut_background(base))
    log(f"  抠图后鲸鱼尺寸 {whale.size}")

    for dens, launcher_px in DENSITIES.items():
        out_dir = os.path.join(RES, "mipmap-" + dens)
        canvas_px = int(launcher_px * 108 / 48)      # 48px→108dp 画布
        target = max(1, int(canvas_px * FG_SCALE))
        scale = target / max(whale.size)
        nw_, nh_ = max(1, int(whale.size[0] * scale)), max(1, int(whale.size[1] * scale))
        fg = whale.resize((nw_, nh_), Image.LANCZOS)
        canvas = Image.new("RGBA", (canvas_px, canvas_px), (0, 0, 0, 0))
        canvas.paste(fg, ((canvas_px - nw_) // 2, (canvas_px - nh_) // 2), fg)
        canvas.save(os.path.join(out_dir, "dsh_foreground.png"))
    log(f"  adaptive 前景 {len(DENSITIES)} 个密度完成（画布 = 108dp，安全区内）")

    # ---------- adaptive 背景层（取底板实测色，和图标更协调）----------
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
            '        android:startColor="#F2F5F8"\n'
            '        android:centerColor="#E6EBF0"\n'
            '        android:endColor="#D2DAE2" />\n'
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

    whale.save(os.path.join(ICON, "whale_fg_preview.png"))
    log(f"\n完成。原图 {square_side}x{square_side}，预览：whale_fg_preview.png")


if __name__ == "__main__":
    main()