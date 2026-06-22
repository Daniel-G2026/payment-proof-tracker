"""Frame raw device screenshots into polished, Play-compliant marketing images.

Why: Google Play requires phone screenshots where the longer side is at most
2x the shorter side (<= 2:1). Modern 1080x2400 (20:9 = 2.22:1) shots are
REJECTED raw. This drops each shot onto a 1080x2160 (exactly 2:1) brand canvas
with a caption -- compliant AND nicer-looking than a bare screenshot.

Usage:
  1. Drop raw screenshots in  store-assets/screenshots/raw/  (any portrait size).
  2. Edit CAPTIONS below (filename -> caption). Files w/o an entry get no caption.
  3. python frame_screenshots.py
  Output -> store-assets/screenshots/framed/<name>.png  (24-bit RGB, no alpha).
"""
from PIL import Image, ImageDraw, ImageFont
import os

ROOT = os.path.dirname(os.path.abspath(__file__))
FONT_DIR = os.path.join(ROOT, "..", "android", "app", "src", "main", "res", "font")
RAW_DIR = os.path.join(ROOT, "screenshots", "raw")
OUT_DIR = os.path.join(ROOT, "screenshots", "framed")

GREEN = (30, 138, 90)
GREEN_DK = (20, 96, 62)
LIME = (201, 233, 38)
INK = (26, 26, 26)
WHITE = (255, 255, 255)

# filename (in raw/) -> caption shown above the phone. "" = no caption.
CAPTIONS = {
    "current.png": "Money in, out and net — at a glance",
    # "add-record.png": "Add a record in seconds",
    # "proof.png":      "Keep the payment screenshot as proof",
    # "projects.png":   "See who has paid, who's due, who's behind",
    # "records.png":    "Every record, searchable",
}

W, H = 1080, 2160  # exactly 2:1 -> always within Play's <=2:1 cap


def font(name, size):
    return ImageFont.truetype(os.path.join(FONT_DIR, name), size)


def vgradient(w, h, top, bottom):
    base = Image.new("RGB", (w, h), top)
    px = base.load()
    for y in range(h):
        t = y / (h - 1)
        c = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
        for x in range(w):
            px[x, y] = c
    return base


def wrap(draw, text, fnt, max_w):
    words, lines, cur = text.split(), [], ""
    for wd in words:
        trial = (cur + " " + wd).strip()
        if draw.textlength(trial, font=fnt) <= max_w:
            cur = trial
        else:
            lines.append(cur)
            cur = wd
    if cur:
        lines.append(cur)
    return lines


def frame_one(path, caption):
    bg = vgradient(W, H, GREEN, GREEN_DK)
    d = ImageDraw.Draw(bg)

    top = 90
    if caption:
        cap_f = font("inter_semibold.ttf", 58)
        lines = wrap(d, caption, cap_f, W - 200)
        y = top
        for ln in lines:
            w = d.textlength(ln, font=cap_f)
            d.text(((W - w) / 2, y), ln, font=cap_f, fill=WHITE)
            y += 74
        # lime accent rule under the caption
        d.rounded_rectangle([(W - 110) / 2, y + 14, (W + 110) / 2, y + 23],
                            radius=5, fill=LIME)
        phone_top = y + 70
    else:
        phone_top = top

    shot = Image.open(path).convert("RGB")
    avail_h = H - phone_top - 70
    avail_w = W - 200
    scale = min(avail_w / shot.width, avail_h / shot.height)
    pw, ph = int(shot.width * scale), int(shot.height * scale)
    shot = shot.resize((pw, ph), Image.LANCZOS)

    # rounded corners
    rad = 40
    mask = Image.new("L", (pw, ph), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, pw, ph], radius=rad, fill=255)
    shot.putalpha(mask)

    bez = 12
    frame = Image.new("RGBA", (pw + bez * 2, ph + bez * 2), (0, 0, 0, 0))
    ImageDraw.Draw(frame).rounded_rectangle(
        [0, 0, pw + bez * 2, ph + bez * 2], radius=rad + bez, fill=INK + (255,))
    frame.alpha_composite(shot.convert("RGBA"), (bez, bez))

    fx = (W - frame.size[0]) // 2
    fy = phone_top
    shadow = Image.new("RGBA", bg.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle(
        [fx, fy + 16, fx + frame.size[0], fy + frame.size[1] + 16],
        radius=rad + bez, fill=(0, 0, 0, 60))
    bg = Image.alpha_composite(bg.convert("RGBA"), shadow).convert("RGB")
    bg.paste(frame, (fx, fy), frame)
    return bg


def main():
    os.makedirs(RAW_DIR, exist_ok=True)
    os.makedirs(OUT_DIR, exist_ok=True)
    # seed: if raw/ is empty, fall back to the one screenshot we already have
    raw_files = [f for f in os.listdir(RAW_DIR) if f.lower().endswith((".png", ".jpg", ".jpeg"))]
    if not raw_files:
        legacy = os.path.join(ROOT, "screenshots", "current.png")
        if os.path.exists(legacy):
            frame_one(legacy, CAPTIONS.get("current.png", "")).save(
                os.path.join(OUT_DIR, "01-home.png"))
            print("wrote", os.path.join(OUT_DIR, "01-home.png"), "(from current.png)")
        else:
            print("No screenshots in", RAW_DIR)
        return
    for i, f in enumerate(sorted(raw_files), 1):
        out = os.path.join(OUT_DIR, f"{i:02d}-{os.path.splitext(f)[0]}.png")
        frame_one(os.path.join(RAW_DIR, f), CAPTIONS.get(f, "")).save(out)
        print("wrote", out)


if __name__ == "__main__":
    main()
