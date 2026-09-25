"""
Builds the app's icon, title art and store assets from the Broadcast source
art (docs/DESIGN.md 11), generated with Nano Banana and kept in source/:

    source/gd-monogram.png       the GD score-bug mark on navy, in a frame
    source/title-background.png  the title screen's night stadium

The mark is cut out of its navy square with a soft edge and placed in the
adaptive icon's safe zone; the navy becomes the vector background layer.

    python3 draw-assets.py            # from docs/play-store/assets
"""
import os

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "../../../app/src/main/res")
FONT = os.path.join(RES, "font/big_shoulders_display.ttf")

NAVY = (10, 18, 36)      # turf, #0A1224
CHALK = (238, 243, 250)
CYAN = (34, 211, 238)
ORANGE = (255, 107, 26)

# A launcher may mask the 108dp canvas to a 66dp circle. The mark is a wide
# parallelogram with cut corners, so 56dp across keeps it inside.
MARK_WIDTH = 56 / 108
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def mark():
    """The GD mark alone, on transparency, cropped tight."""
    im = Image.open(os.path.join(HERE, "source/gd-monogram.png")).convert("RGB")
    w, h = im.size

    def frame(p):
        return p[2] > 150 and p[0] > 60

    row = [im.getpixel((x, h // 2)) for x in range(w)]
    col = [im.getpixel((w // 2, y)) for y in range(h)]
    x0 = next(x for x in range(w) if not frame(row[x]))
    x1 = w - 1 - next(x for x in range(w) if not frame(row[w - 1 - x]))
    y0 = next(y for y in range(h) if not frame(col[y]))
    y1 = h - 1 - next(y for y in range(h) if not frame(col[h - 1 - y]))
    inner = im.crop((x0 + 4, y0 + 4, x1 - 4, y1 - 4))
    bg = inner.getpixel((6, 6))
    px = inner.load()
    iw, ih = inner.size
    out = Image.new("RGBA", inner.size)
    q = out.load()
    for y in range(ih):
        for x in range(iw):
            d = sum(abs(a - b) for a, b in zip(px[x, y], bg))
            q[x, y] = px[x, y] + (max(0, min(255, (d - 18) * 255 // 60)),)
    return out.crop(out.getbbox())


def placed(m, size, width_share):
    """The mark centred on a transparent square."""
    canvas = Image.new("RGBA", (size, size))
    w = round(size * width_share)
    h = round(m.height * w / m.width)
    canvas.alpha_composite(m.resize((w, h), Image.LANCZOS), ((size - w) // 2, (size - h) // 2))
    return canvas


def on_navy(img):
    base = Image.new("RGBA", img.size, NAVY + (255,))
    base.alpha_composite(img)
    return base


def round_mask(img):
    mask = Image.new("L", img.size)
    ImageDraw.Draw(mask).ellipse((0, 0, img.width - 1, img.height - 1), fill=255)
    out = Image.new("RGBA", img.size)
    out.paste(img, mask=mask)
    return out


def font(px, weight=800):
    f = ImageFont.truetype(FONT, px)
    try:
        f.set_variation_by_axes([weight])
    except Exception:
        pass
    return f


def main():
    m = mark()
    for name, scale in DENSITIES.items():
        fg = placed(m, round(108 * scale), MARK_WIDTH)
        d = os.path.join(RES, f"drawable-{name}")
        os.makedirs(d, exist_ok=True)
        fg.save(os.path.join(d, "ic_launcher_foreground.png"))
        # Legacy launchers (below API 26 none are supported, but the files
        # are kept current): the full icon at 48dp, square and round.
        legacy = on_navy(placed(m, round(48 * scale), 0.62))
        md = os.path.join(RES, f"mipmap-{name}")
        legacy.convert("RGB").save(os.path.join(md, "ic_launcher.webp"), quality=95)
        round_mask(legacy).save(os.path.join(md, "ic_launcher_round.webp"), quality=95)

    # Play Store icon: the mark on navy, full square (the store masks it).
    on_navy(placed(m, 512, 0.62)).convert("RGB").save(os.path.join(HERE, "icon-512.png"))

    # Title screen art for the app, as WebP so it stays small.
    bg = Image.open(os.path.join(HERE, "source/title-background.png")).convert("RGB")
    nodpi = os.path.join(RES, "drawable-nodpi")
    os.makedirs(nodpi, exist_ok=True)
    bg.save(os.path.join(nodpi, "title_background.webp"), quality=86)
    # The mark alone, for the title screen's wordmark.
    m.resize((480, round(m.height * 480 / m.width)), Image.LANCZOS).save(
        os.path.join(nodpi, "title_mark.webp"), lossless=True)

    # Feature graphic, 1024x500: the stadium band, the mark, the name.
    fw, fh = 1024, 500
    crop = bg.resize((fw, round(bg.height * fw / bg.width)), Image.LANCZOS)
    top = round(crop.height * 0.18)
    feature = crop.crop((0, top, fw, top + fh)).convert("RGBA")
    shade = Image.new("RGBA", (fw, fh), NAVY + (120,))
    feature.alpha_composite(shade)
    mk = m.resize((260, round(m.height * 260 / m.width)), Image.LANCZOS)
    feature.alpha_composite(mk, (70, (fh - mk.height) // 2))
    d = ImageDraw.Draw(feature)
    d.text((370, 150), "GRIDIRON", font=font(120), fill=CHALK)
    d.text((370, 260), "DYNASTY", font=font(120), fill=ORANGE)
    d.rectangle((372, 392, 700, 398), fill=CYAN)
    feature.convert("RGB").save(os.path.join(HERE, "feature-graphic-1024x500.png"))


if __name__ == "__main__":
    main()
