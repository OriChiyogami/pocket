"""用原始 LOGO（tools/logo-source.png）直接生成手机与电脑端图标，不做裁剪/透明处理。

用法：python tools/make-icon.py
产物：
  tools/pocket.png                          电脑端页面 LOGO / favicon
  tools/pocket.ico                          桌面快捷方式图标
  android/.../mipmap-*/ic_launcher.png      旧版图标（API < 26）
  android/.../mipmap-*/ic_launcher_bg.png   自适应图标背景（API 26+）
"""
import os
from PIL import Image

SRC = "tools/logo-source.png"
SIZE = 1024

src = Image.open(SRC).convert("RGB")
if src.size != (SIZE, SIZE):
    src = src.resize((SIZE, SIZE), Image.LANCZOS)

# 电脑端
src.resize((512, 512), Image.LANCZOS).save("tools/pocket.png")
src.resize((256, 256), Image.LANCZOS).save(
    "tools/pocket.ico",
    sizes=[(256, 256), (128, 128), (64, 64), (48, 48), (32, 32), (16, 16)],
)

# 手机端
RES = "android/app/src/main/res"
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
for name, size in DENSITIES.items():
    folder = os.path.join(RES, "mipmap-" + name)
    os.makedirs(folder, exist_ok=True)
    src.resize((size, size), Image.LANCZOS).save(os.path.join(folder, "ic_launcher.png"))
    adaptive = int(round(size * 108 / 48))
    src.resize((adaptive, adaptive), Image.LANCZOS).save(
        os.path.join(folder, "ic_launcher_bg.png")
    )

print("ok")
