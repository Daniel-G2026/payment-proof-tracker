"""Recreate the ProofLedger 'PL + ledger' monogram as a clean geometric mark
and render the store assets from it.

Colors match the SHIPPED app: ink #1A1A1A + lime #C9E926 on white.
Everything is drawn from build_mark() so the 512 icon, the launcher icon, the
feature graphic and the small-size legibility test are all the exact same mark.

Outputs (store-assets/):
  app-icon-512.png      512x512 RGBA  (Play app icon, white square; Play rounds it)
  icon-48-test.png      48x48         (legibility check at launcher size)
  feature-graphic.png   1024x500 RGB  (no alpha)
"""
from PIL import Image, ImageDraw
import os

ROOT = os.path.dirname(os.path.abspath(__file__))
SHOT = os.path.join(ROOT, "screenshots", "current.png")

INK = (26, 26, 26)          # #1A1A1A  NeutralInk
LIME = (201, 233, 38)       # #C9E926  ProofLime
WHITE = (255, 255, 255)

SS = 4  # supersample factor

# ---- mark geometry, authored on a 0..1000 artboard (variant C) -------------
STEM = (170, 150, 310, 864)
BOWL = (170, 150, 660, 520); BOWL_R = 185
CNTR = (315, 262, 512, 402); CNTR_R = 70           # counter (the P hole, cut out)
BARS = [                                           # lime ledger: top line + L-foot
    ((360, 600, 760, 712), 56),
    ((300, 752, 905, 864), 56),                    # bottom = longest = L-foot
]
CONTENT = (170, 150, 905, 864)  # bbox of all ink+lime


def _s(box, k):
    return [v * k for v in box]


def build_mark(size, ink=INK):
    """Return an RGBA image of the mark, tightly cropped, at `size` px wide-ish.
    `ink` is the color of the P (use white on dark backgrounds)."""
    A = 1000 * SS
    img = Image.new("RGBA", (A, A), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    k = SS
    # P: bowl, then stem, then punch the transparent counter
    d.rounded_rectangle(_s(BOWL, k), radius=BOWL_R * k, fill=ink)
    d.rectangle(_s(STEM, k), fill=ink)
    d.rounded_rectangle(_s(CNTR, k), radius=CNTR_R * k, fill=(0, 0, 0, 0))
    # ^ counter must be transparent, not white, so it works on any bg. Redraw via mask:
    # (rounded_rectangle with transparent fill doesn't erase; do it with a cutout)
    cut = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(cut).rounded_rectangle(_s(CNTR, k), radius=CNTR_R * k, fill=(0, 0, 0, 255))
    # erase counter region from img
    r, g, b, a = img.split()
    ca = cut.split()[3]
    from PIL import ImageChops
    a = ImageChops.subtract(a, ca)
    img = Image.merge("RGBA", (r, g, b, a))
    d = ImageDraw.Draw(img)
    # lime ledger lines
    for box, rad in BARS:
        d.rounded_rectangle(_s(box, k), radius=rad * k, fill=LIME)
    # crop to content bbox
    img = img.crop([int(v * k) for v in CONTENT])
    # downsample
    w = CONTENT[2] - CONTENT[0]
    h = CONTENT[3] - CONTENT[1]
    scale = size / max(w, h)
    img = img.resize((max(1, int(w * scale)), max(1, int(h * scale))), Image.LANCZOS)
    return img


def square_icon(S, fill_frac=0.72, bg=WHITE):
    icon = Image.new("RGBA", (S, S), bg + (255,))
    mark = build_mark(int(S * fill_frac))
    x = (S - mark.width) // 2
    y = (S - mark.height) // 2
    icon.alpha_composite(mark, (x, y))
    return icon


def make_icon():
    square_icon(512).save(os.path.join(ROOT, "app-icon-512.png"))
    print("wrote app-icon-512.png")
    square_icon(48).convert("RGB").resize((192, 192), Image.NEAREST).save(
        os.path.join(ROOT, "icon-48-test.png"))
    print("wrote icon-48-test.png (rendered at 48, upscaled 4x to inspect)")


# ---- feature graphic -------------------------------------------------------
def vgradient(w, h, top, bottom):
    base = Image.new("RGB", (w, h), top)
    px = base.load()
    for yy in range(h):
        t = yy / (h - 1)
        c = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
        for xx in range(w):
            px[xx, yy] = c
    return base


def make_feature():
    W, H = 1024, 500
    bg = vgradient(W, H, (20, 20, 22), (10, 10, 12)).convert("RGBA")  # near-black
    d = ImageDraw.Draw(bg)

    # phone mockup (real screenshot) bleeding in from the right
    shot = Image.open(SHOT).convert("RGB")
    ph_h = 470
    sc = ph_h / shot.height
    pw = int(shot.width * sc)
    shot = shot.resize((pw, ph_h), Image.LANCZOS)
    rad = 34
    m = Image.new("L", (pw, ph_h), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, pw, ph_h], radius=rad, fill=255)
    shot.putalpha(m)
    bez = 10
    frame = Image.new("RGBA", (pw + bez * 2, ph_h + bez * 2), (0, 0, 0, 0))
    ImageDraw.Draw(frame).rounded_rectangle(
        [0, 0, pw + bez * 2, ph_h + bez * 2], radius=rad + bez, fill=(40, 40, 44, 255))
    frame.alpha_composite(shot.convert("RGBA"), (bez, bez))
    fx, fy = W - frame.size[0] + 28, (H - frame.size[1]) // 2 + 26
    bg.alpha_composite(frame, (fx, fy))

    # mark, top-left (white P on the dark banner)
    mark = build_mark(132, ink=WHITE)
    bg.alpha_composite(mark, (70, 70))

    # wordmark + tagline using Inter
    from PIL import ImageFont
    fdir = os.path.join(ROOT, "..", "android", "app", "src", "main", "res", "font")
    title = ImageFont.truetype(os.path.join(fdir, "inter_semibold.ttf"), 74)
    tag = ImageFont.truetype(os.path.join(fdir, "inter_medium.ttf"), 31)
    sub = ImageFont.truetype(os.path.join(fdir, "inter_medium.ttf"), 25)
    tx, ty = 70, 240
    d.text((tx, ty), "Proof", font=title, fill=WHITE)
    pw2 = d.textlength("Proof", font=title)
    d.text((tx + pw2, ty), "Ledger", font=title, fill=LIME)
    d.text((tx, ty + 96), "Record money in and out — and keep the proof.",
           font=tag, fill=(235, 235, 235))
    d.text((tx, ty + 142), "Works offline · Who paid · Who owes",
           font=sub, fill=(150, 150, 150))

    out = os.path.join(ROOT, "feature-graphic.png")
    bg.convert("RGB").save(out)
    print("wrote feature-graphic.png")


if __name__ == "__main__":
    make_icon()
    make_feature()
