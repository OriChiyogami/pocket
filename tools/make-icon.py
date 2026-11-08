"""从原始 LOGO 生成手机/电脑图标。

- 去掉底部 pocket 文字
- P 居中，并留出足够裕度（手机自适应图标会被系统再裁一圈）
- 手机自适应图标：深色背景层 + 透明前景层，前景 P 只占 46%，裁切后仍完整

用法：python tools/make-icon.py
"""
import os
from PIL import Image, ImageChops, ImageDraw, ImageFilter

SRC = "tools/logo-source.png"
SIZE = 1024
BOX = (180, 60, 880, 760)   # P 居中、四周留白的方形区域
TEXT_TOP = 660              # 从这里往下是 pocket 文字，用上方背景向下渐隐覆盖
EDGE = 40                   # 裁掉边缘残留的方框感

src = Image.open(SRC).convert("RGB")
bg_color = src.getpixel((2, 2))

# 1) 抹掉底部文字
clean = src.copy()
strip = src.crop((0, TEXT_TOP - 3, SIZE, TEXT_TOP - 2))
for y in range(TEXT_TOP, SIZE):
    fade = max(0.0, 1.0 - (y - TEXT_TOP) / 260.0)
    clean.paste(Image.eval(strip, lambda v, f=fade: int(v * f)), (0, y))

# 2) 取 P，按亮度抠出透明前景（内部深色区域之后会被背景色填上）
mark = clean.crop(BOX).resize((SIZE, SIZE), Image.LANCZOS)
gray = mark.convert("L")
alpha = gray.point(lambda v: 0 if v < 16 else min(255, int((v - 16) * 255 / 70)))
fade = Image.new("L", (SIZE, SIZE), 0)
ImageDraw.Draw(fade).rectangle(
    [EDGE, EDGE, SIZE - 1 - EDGE, SIZE - 1 - EDGE], fill=255
)
fade = fade.filter(ImageFilter.GaussianBlur(EDGE * 0.9))
alpha = ImageChops.multiply(alpha, fade)
mark.putalpha(alpha)


def square(percent):
    """透明画布上居中放 P，占画布 percent。"""
    side = int(SIZE * percent)
    pic = mark.resize((side, side), Image.LANCZOS)
    canvas = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    off = (SIZE - side) // 2
    canvas.alpha_composite(pic, (off, off))
    return canvas


def composed(percent):
    """深色底 + P，用于电脑图标与旧版手机图标。"""
    canvas = Image.new("RGBA", (SIZE, SIZE), (bg_color[0], bg_color[1], bg_color[2], 255))
    canvas.alpha_composite(square(percent))
    return canvas


# 电脑端：保持原图（电脑系统不裁切，原尺寸正好）
pc = src
pc.resize((512, 512), Image.LANCZOS).save("tools/pocket.png")
pc.resize((256, 256), Image.LANCZOS).save(
    "tools/pocket.ico",
    sizes=[(256, 256), (128, 128), (64, 64), (48, 48), (32, 32), (16, 16)],
)

# 手机端
RES = "android/app/src/main/res"
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
legacy = composed(0.58).convert("RGB")
adaptive_bg = Image.new("RGB", (SIZE, SIZE), bg_color)
adaptive_fg = square(0.54)

for name, size in DENSITIES.items():
    folder = os.path.join(RES, "mipmap-" + name)
    os.makedirs(folder, exist_ok=True)
    legacy.resize((size, size), Image.LANCZOS).save(os.path.join(folder, "ic_launcher.png"))
    adaptive = int(round(size * 108 / 48))
    adaptive_bg.resize((adaptive, adaptive), Image.LANCZOS).save(
        os.path.join(folder, "ic_launcher_bg.png")
    )
    adaptive_fg.resize((adaptive, adaptive), Image.LANCZOS).save(
        os.path.join(folder, "ic_launcher_fg.png")
    )

# 3) 预览自适应图标：模拟系统 108dp -> 72dp 的裁切
preview = Image.new("RGBA", (SIZE, SIZE), (bg_color[0], bg_color[1], bg_color[2], 255))
preview.alpha_composite(adaptive_fg)
crop_side = int(SIZE * 72 / 108)
off = (SIZE - crop_side) // 2
preview.crop((off, off, off + crop_side, off + crop_side)).resize((512, 512), Image.LANCZOS) \
    .convert("RGB").save(".build/logo-adaptive-preview.png")

print("ok", bg_color)
