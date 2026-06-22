"""Render icon variants at real small sizes to compare legibility."""
from PIL import Image, ImageDraw, ImageFont, ImageChops
import os

ROOT = os.path.dirname(os.path.abspath(__file__))
INK = (26, 26, 26); LIME = (201, 233, 38); WHITE = (255, 255, 255)
SS = 4

STEM_X = (170, 310)
BOWL = (170, 150, 660, 520); BOWL_R = 185
CNTR = (315, 262, 512, 402); CNTR_R = 70


def _s(b, k): return [v * k for v in b]


def build(bars, stem_bottom):
    A = 1000 * SS; k = SS
    img = Image.new("RGBA", (A, A), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle(_s(BOWL, k), radius=BOWL_R * k, fill=INK)
    d.rectangle(_s((STEM_X[0], 150, STEM_X[1], stem_bottom), k), fill=INK)
    cut = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(cut).rounded_rectangle(_s(CNTR, k), radius=CNTR_R * k, fill=(0, 0, 0, 255))
    r, g, b, a = img.split()
    a = ImageChops.subtract(a, cut.split()[3])
    img = Image.merge("RGBA", (r, g, b, a)); d = ImageDraw.Draw(img)
    for box, rad in bars:
        d.rounded_rectangle(_s(box, k), radius=rad * k, fill=LIME)
    bbox = (170, 150, max(x for (_, _, x, _), _ in bars + [((0,0,885,0),0)]),
            stem_bottom)
    img = img.crop([int(v * k) for v in bbox])
    return img, (bbox[2] - bbox[0], bbox[3] - bbox[1])


def tile(bars, stem_bottom, S, frac=0.74):
    mark, (w, h) = build(bars, stem_bottom)
    icon = Image.new("RGBA", (S, S), WHITE + (255,))
    sc = (S * frac) / max(w, h)
    mark = mark.resize((max(1, int(w * sc)), max(1, int(h * sc))), Image.LANCZOS)
    icon.alpha_composite(mark, ((S - mark.width) // 2, (S - mark.height) // 2))
    return icon


VARIANTS = {
    "A  3 thin (current)": ([((365,572,700,640),34),((365,668,790,736),34),((310,764,885,832),34)], 832),
    "B  3 thick, spaced":  ([((360,556,700,648),46),((360,690,800,782),46),((300,824,905,916),46)], 916),
    "C  2 thick":          ([((360,600,760,712),56),((300,752,905,864),56)], 864),
    "D  2 thick + bigger": ([((360,600,775,716),58),((300,758,915,874),58)], 874),
}


def main():
    cols = list(VARIANTS.items())
    small = [48, 72, 96]
    pad = 24
    cell = 200  # display cell (upscaled)
    label_h = 70
    big_h = 220
    W = pad + len(cols) * (cell + pad)
    H = label_h + big_h + pad + len(small) * (cell + pad) + pad
    sheet = Image.new("RGB", (W, H), (245, 245, 245))
    d = ImageDraw.Draw(sheet)
    try:
        f = ImageFont.truetype(os.path.join(ROOT, "..", "android", "app", "src",
            "main", "res", "font", "inter_semibold.ttf"), 26)
        fs = ImageFont.truetype(os.path.join(ROOT, "..", "android", "app", "src",
            "main", "res", "font", "inter_medium.ttf"), 22)
    except Exception:
        f = fs = ImageFont.load_default()

    for ci, (name, (bars, sb)) in enumerate(cols):
        x = pad + ci * (cell + pad)
        d.text((x, 18), name, font=f, fill=(20, 20, 20))
        # big reference (200px)
        big = tile(bars, sb, big_h).convert("RGB")
        sheet.paste(big, (x, label_h))
        # small sizes, upscaled NEAREST so pixelation is honest
        for ri, s in enumerate(small):
            y = label_h + big_h + pad + ri * (cell + pad)
            t = tile(bars, sb, s).convert("RGB").resize((cell, cell), Image.NEAREST)
            sheet.paste(t, (x, y))
            d.text((x + 4, y + cell - 26), f"{s}px", font=fs, fill=(120, 120, 120))
    out = os.path.join(ROOT, "icon-variants.png")
    sheet.save(out)
    print("wrote", out)


if __name__ == "__main__":
    main()
