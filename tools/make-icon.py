"""从 tools/logo-source.png 生成手机与电脑端图标（透明背景）。

用法：python tools/make-icon.py
产物：
  tools/pocket.png                 电脑端页面 LOGO / favicon（透明）
  tools/pocket.ico                 桌面快捷方式图标（透明）
  android/.../mipmap-*/ic_launcher.png      旧版图标（API < 26）
  android/.../mipmap-*/ic_launcher_fg.png   自适应图标前景（API 26+）
"""
import os
from PIL import Image, ImageChops, ImageDraw, ImageFilter

SRC = "tools/logo-source.png"
# 只取 P 主体，避开下方的 pocket 文字
CROP = (267, 145, 797, 675)
SIZE = 1024
INNER = 0.72  # P 在画布中的占比，留出自适应图标安全区


def load_mark():
    src = Image.open(SRC).convert("RGB")
    crop = src.crop(CROP).resize((SIZE, SIZE), Image.LANCZOS)
    # 按亮度生成 alpha：背景接近纯黑 → 透明；P 与光晕保留
    gray = crop.convert("L")
    alpha = gray.point(lambda v: 0 if v < 14 else min(255, int((v - 14) * 255 / 78)))

    # 最外圈渐隐到全透明，避免残留一圈淡淡的方框
    edge = int(SIZE * 0.06)
    fade = Image.new("L", (SIZE, SIZE), 0)
    ImageDraw.Draw(fade).rectangle(
        [edge, edge, SIZE - 1 - edge, SIZE - 1 - edge], fill=255
    )
    fade = fade.filter(ImageFilter.GaussianBlur(edge * 0.8))
    alpha = ImageChops.multiply(alpha, fade)

    rgba = crop.convert("RGBA")
    rgba.putalpha(alpha)
    return rgba


mark = load_mark()
inner = int(SIZE * INNER)
pic = mark.resize((inner, inner), Image.LANCZOS)
canvas = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
offset = (SIZE - inner) // 2
canvas.alpha_composite(pic, (offset, offset))

# 电脑端
canvas.resize((512, 512), Image.LANCZOS).save("tools/pocket.png")
canvas.resize((256, 256), Image.LANCZOS).save(
    "tools/pocket.ico",
    sizes=[(256, 256), (128, 128), (64, 64), (48, 48), (32, 32), (16, 16)],
)

# 手机端
RES = "android/app/src/main/res"
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
for name, size in DENSITIES.items():
    folder = os.path.join(RES, "mipmap-" + name)
    os.makedirs(folder, exist_ok=True)
    canvas.resize((size, size), Image.LANCZOS).save(os.path.join(folder, "ic_launcher.png"))
    adaptive = int(round(size * 108 / 48))
    canvas.resize((adaptive, adaptive), Image.LANCZOS).save(
        os.path.join(folder, "ic_launcher_fg.png")
    )

# 预览：分别放到浅色/深色底上，检查透明边缘
for tone, color in (("light", (238, 236, 245, 255)), ("dark", (12, 11, 16, 255))):
    bg = Image.new("RGBA", (512, 512), color)
    bg.alpha_composite(canvas.resize((512, 512), Image.LANCZOS))
    bg.convert("RGB").save(".build/logo-preview-" + tone + ".png")

print("ok")
