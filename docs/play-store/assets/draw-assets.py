"""
Draws the app's mark and the store assets.

The wordmark is type, and the launcher icon's foreground is therefore drawn
here as pixels rather than written as vector paths: the font is a variable
TrueType and nothing in the toolchain converts glyphs to paths. The adaptive
background stays vector - it is three flat colours - so only the lettering is
a bitmap.

Colours are docs/DESIGN.md's: turf ground, quiet yard lines, chalk and pylon
type, the stripe underneath.

    python3 draw-assets.py <output dir>
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

TURF = (15, 42, 32)
TURF_LINE = (42, 77, 60)
CHALK = (238, 241, 234)
PYLON = (255, 107, 26)
STRIPE = (242, 213, 48)

FONT = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "../../../app/src/main/res/font/big_shoulders_display.ttf",
)
SS = 4  # draw big, shrink down, so no edge is ragged

# A launcher may mask the icon to a circle of 66 of the 108dp canvas, so the
# lettering has to fit inside that circle, not merely inside the square.
SAFE_WIDTH = 0.44


def _font(px, weight=800):
    font = ImageFont.truetype(FONT, px)
    try:
        font.set_variation_by_axes([weight])
    except Exception:
        pass  # a static build of the face is fine too
    return font


def _fit(d, text, target_width, weight=800):
    """The font size at which [text] is exactly target_width wide."""
    size = int(target_width)
    for _ in range(40):
        font = _font(size, weight)
        left, _, right, _ = d.textbbox((0, 0), text, font=font)
        width = right - left
        if abs(width - target_width) <= 1 or size <= 4:
            return font
        size = max(4, int(size * target_width / max(width, 1)))
    return _font(size, weight)


def _centred(d, centre, text, font, fill):
    left, top, right, bottom = d.textbbox((0, 0), text, font=font)
    d.text((centre[0] - (right + left) / 2, centre[1] - (bottom + top) / 2),
           text, font=font, fill=fill)


def wordmark(size, transparent=False, safe=True):
    """GRID over IRON, with the line to gain under it."""
    big = size * SS
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0) if transparent else TURF + (255,))
    d = ImageDraw.Draw(img)
    if not transparent:
        for frac in (0.24, 0.76):
            x = big * frac
            d.line([(x, 0), (x, big)], fill=TURF_LINE, width=max(1, int(big * 0.014)))
    width = big * (SAFE_WIDTH if safe else 0.68)
    font = _fit(d, "GRID", width)
    _centred(d, (big / 2, big * 0.345), "GRID", font, CHALK)
    _centred(d, (big / 2, big * 0.545), "IRON", font, PYLON)
    rule = width * 0.92
    d.line([(big / 2 - rule / 2, big * 0.675), (big / 2 + rule / 2, big * 0.675)],
           fill=STRIPE, width=int(big * 0.026))
    return img.resize((size, size), Image.LANCZOS)


def feature(out):
    """The 1024 x 500 graphic: the name on the sheet, ruled like a call sheet."""
    W, H = 1024, 500
    img = Image.new("RGB", (W, H), TURF)
    d = ImageDraw.Draw(img)
    for i in range(1, 12):
        x = W * i / 12
        d.line([(x, 0), (x, H)], fill=TURF_LINE, width=2)
    title = _fit(d, "GRIDIRON", W * 0.52)
    _centred(d, (W * 0.40, H * 0.40), "GRIDIRON", title, CHALK)
    _centred(d, (W * 0.40, H * 0.63), "DYNASTY", title, PYLON)
    d.line([(W * 0.14, H * 0.80), (W * 0.66, H * 0.80)], fill=STRIPE, width=8)
    sub = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 28)
    d.text((W * 0.71, H * 0.46), "You are the", font=sub, fill=(157, 179, 167))
    d.text((W * 0.71, H * 0.53), "general manager.", font=sub, fill=(157, 179, 167))
    img.save(f"{out}/feature-graphic-1024x500.png")


def launcher(res):
    """The icon Android draws: a bitmap foreground, and the legacy densities."""
    for folder, px in [("drawable-mdpi", 108), ("drawable-hdpi", 162),
                       ("drawable-xhdpi", 216), ("drawable-xxhdpi", 324),
                       ("drawable-xxxhdpi", 432)]:
        os.makedirs(f"{res}/{folder}", exist_ok=True)
        wordmark(px, transparent=True).save(f"{res}/{folder}/ic_launcher_foreground.png")
    for folder, px in [("mipmap-mdpi", 48), ("mipmap-hdpi", 72), ("mipmap-xhdpi", 96),
                       ("mipmap-xxhdpi", 144), ("mipmap-xxxhdpi", 192)]:
        full = wordmark(px, safe=False)
        full.convert("RGB").save(f"{res}/{folder}/ic_launcher.webp", "WEBP", quality=95)
        mask = Image.new("L", (px * 4, px * 4), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, px * 4 - 1, px * 4 - 1), fill=255)
        round_icon = wordmark(px * 4, safe=False)
        round_icon.putalpha(mask)
        round_icon.resize((px, px), Image.LANCZOS).save(
            f"{res}/{folder}/ic_launcher_round.webp", "WEBP", quality=95)


if __name__ == "__main__":
    out = sys.argv[1]
    wordmark(512, safe=False).convert("RGB").save(f"{out}/icon-512.png")
    feature(out)
    res = os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../../app/src/main/res")
    launcher(os.path.normpath(res))
    print("drawn")
