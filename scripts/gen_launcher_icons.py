# File: scripts/gen_launcher_icons.py
#
# 从一张方形原图烘出桌面图标的全套位图：自适应图标的三层 + 五个密度的 legacy 方块/圆角图。
#
#   用法：python scripts/gen_launcher_icons.py <原图.png>
#
# 为什么前景不铺满整张画布：启动器会把 108dp 的画布裁成直径 66dp 的圆（或更方的 squircle），
# 铺满就等着被切掉耳朵和裙摆。这里把人脸放在画布中心，前景只占 0.84 倍，
# 圆形遮罩落进去的那一圈正好是脸和花束。
#
# 背景用同一张图的高斯模糊 + 压暗：既补上了前景四周的空隙，色调又天然和前景一致。
# 比纯色底更接近"这就是那张图"，也比直接铺满原图更稳，因为模糊之后没有可辨认的边角会被切。
#
# 单色层（monochrome）只保留 alpha：主题图标会把这一层的颜色整体换成系统色，
# 所以这里把亮度反相当透明度 —— 深色的裙装、眼睛、蝴蝶结留下，浅色头发和背景透明，
# 得到的是暗部剪影而不是一坨实心色块。
import os
import sys
from PIL import Image, ImageChops, ImageDraw, ImageEnhance, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")

# 自适应画布：108dp 换算到各密度的像素
ADAPTIVE = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
# legacy 图标：48dp 换算
LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

FG_RATIO = 0.84
# 以原图比例表示的取景框：横向居中、纵向取顶部起 0.70，脸因此落在画布中心略偏上
CROP = (0.15, 0.00, 0.85, 0.70)


def face_crop(src: Image.Image) -> Image.Image:
    w, h = src.size
    l, t, r, b = (int(CROP[0] * w), int(CROP[1] * h), int(CROP[2] * w), int(CROP[3] * h))
    side = min(r - l, b - t)
    l = (l + r) // 2 - side // 2
    t = max(0, (t + b) // 2 - side // 2)
    return src.crop((l, t, l + side, t + side))


def foreground(src: Image.Image, size: int) -> Image.Image:
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    n = int(size * FG_RATIO)
    fg = face_crop(src).resize((n, n), Image.LANCZOS).convert("RGBA")
    # 取景框里没有任何透明像素，直接 paste 就够，不必走 alpha 合成
    canvas.paste(fg, ((size - n) // 2, (size - n) // 2))
    return canvas


def background(src: Image.Image, size: int) -> Image.Image:
    """整张原图铺满画布后模糊压暗：只当底衬，不指望它被看清"""
    bg = src.resize((size, size), Image.LANCZOS)
    bg = bg.filter(ImageFilter.GaussianBlur(max(2, size // 22)))
    return ImageEnhance.Brightness(bg.convert("RGBA")).enhance(0.88)


def monochrome(src: Image.Image, size: int) -> Image.Image:
    fg = foreground(src, size)
    # 只留暗部：亮度低于 170 的（裙装、眼睛、蝴蝶结、发丝描线）才变成不透明，浅色头发与背景透明
    lum = fg.convert("L").point(lambda v: max(0, min(255, (170 - v) * 3)))
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(Image.new("RGB", (size, size), (255, 255, 255)), (0, 0), lum)
    return out


def legacy(src: Image.Image, size: int, round_mask: bool) -> Image.Image:
    out = background(src, size)
    out.alpha_composite(foreground(src, size))
    if round_mask:
        m = Image.new("L", (size, size), 0)
        ImageDraw.Draw(m).ellipse((0, 0, size - 1, size - 1), fill=255)
        out.putalpha(ImageChops.multiply(out.getchannel("A"), m))
    return out


def save(img: Image.Image, folder: str, name: str) -> None:
    d = os.path.join(RES, folder)
    os.makedirs(d, exist_ok=True)
    img.save(os.path.join(d, name), "PNG", optimize=True)


def main() -> int:
    if len(sys.argv) < 2:
        print("用法：python scripts/gen_launcher_icons.py <原图.png>")
        return 2
    src = Image.open(sys.argv[1]).convert("RGB")
    if src.size[0] != src.size[1]:
        print(f"原图需要是正方形，当前 {src.size}")
        return 2
    for dens, px in ADAPTIVE.items():
        save(foreground(src, px), f"drawable-{dens}", "ic_launcher_foreground.png")
        save(background(src, px), f"drawable-{dens}", "ic_launcher_background.png")
        save(monochrome(src, px), f"drawable-{dens}", "ic_launcher_monochrome.png")
    for dens, px in LEGACY.items():
        save(legacy(src, px, False), f"mipmap-{dens}", "ic_launcher.png")
        save(legacy(src, px, True), f"mipmap-{dens}", "ic_launcher_round.png")
    print("已生成 5 密度 x (前景/背景/单色) + 5 密度 x (方块/圆角)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
