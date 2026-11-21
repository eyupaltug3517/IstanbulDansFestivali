"""Original 256x240 pixel-art scenes used as stand-in game screens for marketing shots."""
import random
from PIL import Image, ImageDraw

W, H = 256, 240

def block(d, x, y, c, edge):
    d.rectangle([x, y, x + 15, y + 15], fill=c)
    d.rectangle([x, y, x + 15, y], fill=edge); d.rectangle([x, y, x, y + 15], fill=edge)
    d.rectangle([x + 15, y, x + 15, y + 15], fill=(0, 0, 0)); d.rectangle([x, y + 15, x + 15, y + 15], fill=(0, 0, 0))

def hero(d, x, y, body, visor):
    # little robot adventurer (original character)
    d.rectangle([x + 3, y, x + 12, y + 8], fill=body)
    d.rectangle([x + 5, y + 3, x + 11, y + 5], fill=visor)
    d.rectangle([x + 1, y + 9, x + 14, y + 17], fill=body)
    d.rectangle([x + 3, y + 18, x + 6, y + 23], fill=(40, 40, 60)); d.rectangle([x + 9, y + 18, x + 12, y + 23], fill=(40, 40, 60))
    d.rectangle([x + 7, y - 3, x + 8, y - 1], fill=visor)

def platformer():
    # Dusk forest: gradient sky, moon, pine silhouettes, mossy stone ground, crystal platform.
    im = Image.new("RGB", (W, H)); d = ImageDraw.Draw(im)
    top, bot = (40, 24, 88), (240, 120, 96)
    for y in range(H):
        t = y / H
        d.line([(0, y), (W, y)], fill=tuple(int(top[i] + (bot[i] - top[i]) * t) for i in range(3)))
    d.ellipse([190, 22, 222, 54], fill=(255, 236, 190)); d.ellipse([200, 22, 230, 50], fill=(170, 90, 130))
    for x0, h in [(0, 70), (30, 90), (70, 60), (110, 100), (160, 75), (200, 95), (235, 65)]:
        d.polygon([(x0, 192), (x0 + 18, 192 - h), (x0 + 36, 192)], fill=(36, 28, 70))
    for x in range(0, W, 16):
        for y, top_ in ((192, True), (208, False), (224, False)):
            d.rectangle([x, y, x + 15, y + 15], fill=(70, 80, 110)); d.rectangle([x + 1, y + 1, x + 14, y + 7], fill=(90, 100, 135))
            d.rectangle([x, y + 15, x + 15, y + 15], fill=(30, 30, 50)); d.rectangle([x + 15, y, x + 15, y + 15], fill=(30, 30, 50))
            if top_: d.rectangle([x, y, x + 15, y + 3], fill=(80, 200, 140)); d.rectangle([x + 3, y + 4, x + 5, y + 5], fill=(80, 200, 140))
    for x in (96, 112, 128):
        d.polygon([(x, 136), (x + 8, 124), (x + 16, 136), (x + 8, 144)], fill=(120, 230, 240)); d.line([(x + 8, 126), (x + 8, 142)], fill=(230, 255, 255))
    for i, x in enumerate((180, 196, 212)):
        y = 104 + (i % 2) * 6
        d.polygon([(x + 4, y), (x + 8, y + 5), (x + 4, y + 10), (x, y + 5)], fill=(255, 214, 90)); d.point((x + 3, y + 3), fill=(255, 255, 255))
    hero(d, 60, 168, (230, 57, 70), (255, 230, 120))
    d.ellipse([196, 178, 216, 192], fill=(150, 220, 90)); d.rectangle([201, 182, 203, 184], fill=(20, 20, 20)); d.rectangle([209, 182, 211, 184], fill=(20, 20, 20))
    hud(d, "SKOR 004200", "x 07")
    return im

def space():
    im = Image.new("RGB", (W, H), (8, 8, 28)); d = ImageDraw.Draw(im)
    rnd = random.Random(3)
    for _ in range(90):
        x, y = rnd.randrange(W), rnd.randrange(H); c = rnd.choice([(255, 255, 255), (150, 170, 255), (255, 220, 150)])
        d.point((x, y), fill=c)
    d.ellipse([170, 30, 240, 100], fill=(200, 90, 40)); d.ellipse([176, 40, 230, 70], fill=(230, 130, 60)); d.arc([150, 55, 260, 80], 160, 380, fill=(240, 200, 120), width=2)
    sx, sy = 112, 180
    d.polygon([(sx + 16, sy), (sx + 30, sy + 28), (sx + 2, sy + 28)], fill=(220, 220, 235)); d.rectangle([sx + 13, sy + 10, sx + 19, sy + 18], fill=(60, 170, 255))
    d.rectangle([sx + 8, sy + 28, sx + 12, sy + 34], fill=(255, 140, 0)); d.rectangle([sx + 20, sy + 28, sx + 24, sy + 34], fill=(255, 140, 0))
    for y in (150, 120, 90):
        d.rectangle([sx + 15, y, sx + 17, y + 8], fill=(255, 240, 80))
    for ex, ey in [(40, 60), (90, 40), (140, 70), (60, 110)]:
        d.polygon([(ex, ey), (ex + 16, ey), (ex + 8, ey + 12)], fill=(80, 220, 120)); d.rectangle([ex + 6, ey + 3, ex + 9, ey + 5], fill=(255, 40, 60))
    hud(d, "SKOR 018750", "x 03")
    return im

def racing():
    im = Image.new("RGB", (W, H), (60, 188, 252)); d = ImageDraw.Draw(im)
    d.rectangle([0, 96, W, H], fill=(0, 148, 60))
    for i in range(0, 144):
        y = 96 + i; half = 20 + i * 0.9
        d.line([(128 - half, y), (128 + half, y)], fill=(100, 100, 110))
        if (i // 8) % 2 == 0:
            d.line([(128 - half - 6, y), (128 - half, y)], fill=(252, 252, 252)); d.line([(128 + half, y), (128 + half + 6, y)], fill=(252, 252, 252))
        else:
            d.line([(128 - half - 6, y), (128 - half, y)], fill=(230, 40, 40)); d.line([(128 + half, y), (128 + half + 6, y)], fill=(230, 40, 40))
        if (i // 10) % 2 == 0 and i > 10:
            d.line([(127, y), (129, y)], fill=(252, 252, 252))
    d.polygon([(0, 96), (50, 70), (110, 96)], fill=(120, 120, 160)); d.polygon([(140, 96), (200, 60), (256, 96)], fill=(100, 100, 150))
    def car(x, y, c, s=1):
        d.rectangle([x, y + 6 * s, x + 32 * s, y + 16 * s], fill=c); d.rectangle([x + 6 * s, y, x + 26 * s, y + 8 * s], fill=c)
        d.rectangle([x + 9 * s, y + 2 * s, x + 23 * s, y + 6 * s], fill=(150, 220, 255))
        d.rectangle([x - 2 * s, y + 12 * s, x + 4 * s, y + 18 * s], fill=(20, 20, 20)); d.rectangle([x + 28 * s, y + 12 * s, x + 34 * s, y + 18 * s], fill=(20, 20, 20))
    car(140, 120, (40, 120, 230)); car(96, 190, (230, 57, 70), 1)
    hud(d, "TUR 2/3", "182 km/s")
    return im

def puzzle():
    # Gem-matching column puzzle: round gems, a falling pair, sparkle on a match.
    im = Image.new("RGB", (W, H), (20, 36, 52)); d = ImageDraw.Draw(im)
    for y in range(0, H, 8):
        for x in range(0, W, 8):
            if (x // 8 + y // 8) % 2 == 0: d.rectangle([x, y, x + 7, y + 7], fill=(24, 42, 60))
    d.rounded_rectangle([60, 28, 196, 224], radius=6, fill=(8, 14, 24), outline=(90, 200, 220))
    cols = [(230, 57, 70), (255, 196, 0), (60, 200, 120), (60, 140, 255), (190, 90, 230)]
    rnd = random.Random(11)
    def gem(x, y, c):
        d.ellipse([x, y, x + 14, y + 14], fill=c); d.ellipse([x + 3, y + 3, x + 6, y + 6], fill=(255, 255, 255))
    for row in range(5):
        for c in range(8):
            if rnd.random() < 0.8: gem(64 + c * 16 + 1, 206 - row * 16, rnd.choice(cols))
    gem(113, 70, cols[1]); gem(113, 86, cols[3])
    for sx, sy in [(150, 140), (158, 132), (166, 140), (158, 148)]:
        d.point((sx, sy), fill=(255, 255, 255))
    d.line([(150, 140), (166, 140)], fill=(255, 255, 200)); d.line([(158, 132), (158, 148)], fill=(255, 255, 200))
    hud(d, "SKOR 3150", "SEVİYE 9", left=True)
    return im

FONT = {  # 3x5 pixel font for HUD text (original)
    'S': ["111", "100", "111", "001", "111"], 'K': ["101", "110", "100", "110", "101"], 'O': ["111", "101", "101", "101", "111"],
    'R': ["110", "101", "110", "101", "101"], 'x': ["000", "101", "010", "101", "000"], 'T': ["111", "010", "010", "010", "010"],
    'U': ["101", "101", "101", "101", "111"], 'E': ["111", "100", "110", "100", "111"], 'V': ["101", "101", "101", "101", "010"],
    'İ': ["010", "000", "010", "010", "010"], 'Y': ["101", "101", "010", "010", "010"], 'k': ["100", "101", "110", "101", "101"],
    'm': ["000", "110", "111", "101", "101"], 's': ["000", "011", "010", "001", "110"], '/': ["001", "001", "010", "100", "100"],
    ' ': ["000"] * 5,
}
DIG = ["111101101101111", "010110010010111", "111001111100111", "111001111001111", "101101111001001",
       "111100111001111", "111100111101111", "111001010010010", "111101111101111", "111101111001111"]

def text(d, s, x, y, c=(255, 255, 255)):
    for ch in s:
        rows = [DIG[int(ch)][i * 3:i * 3 + 3] for i in range(5)] if ch.isdigit() else FONT.get(ch, FONT[' '])
        for ry, r in enumerate(rows):
            for rx, v in enumerate(r):
                if v == "1": d.rectangle([x + rx * 2, y + ry * 2, x + rx * 2 + 1, y + ry * 2 + 1], fill=c)
        x += 8

def hud(d, a, b, left=False):
    text(d, a, 10, 10); text(d, b, 256 - 10 - 8 * len(b), 10)

for name, fn in [("platformer", platformer), ("space", space), ("racing", racing), ("puzzle", puzzle)]:
    fn().save(f"{name}.png")
print("ok")
