"""生成口袋电脑端快捷方式图标：tools/pocket.ico"""
from PIL import Image, ImageDraw

S = 1024
top = (255, 126, 179)
bottom = (255, 77, 141)

img = Image.new("RGBA", (S, S), (0, 0, 0, 0))

grad = Image.new("RGB", (1, S))
for y in range(S):
    t = y / (S - 1)
    grad.putpixel((0, y), tuple(int(top[i] * (1 - t) + bottom[i] * t) for i in range(3)))
grad = grad.resize((S, S))

mask = Image.new("L", (S, S), 0)
ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=232, fill=255)
img.paste(grad, (0, 0), mask)

d = ImageDraw.Draw(img)
white = (255, 255, 255, 255)
# 口袋本体
d.rounded_rectangle([258, 404, 766, 780], radius=70, fill=white)
# 口袋开口的浅色阴影
d.rounded_rectangle([258, 404, 766, 466], radius=36, fill=(255, 236, 244, 255))
# 上方小圆点
d.ellipse([470, 262, 554, 346], fill=white)

img.save("tools/pocket.png")
sizes = [(256, 256), (128, 128), (64, 64), (48, 48), (32, 32), (16, 16)]
img.resize((256, 256), Image.LANCZOS).save("tools/pocket.ico", sizes=sizes)
print("ok")
