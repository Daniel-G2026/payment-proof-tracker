"""Emit the Android adaptive-icon foreground (VectorDrawable) for variant C,
using the SAME geometry as the 512 store icon so they match exactly.

The mark is fit inside the adaptive-icon safe zone (central ~66dp circle of the
108dp viewport) so no launcher mask (circle / squircle / rounded-square) clips it.
Writes android/app/src/main/res/drawable/ic_launcher_foreground.xml.
"""
import os, math

ROOT = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(ROOT, "..", "android", "app", "src", "main", "res",
                   "drawable", "ic_launcher_foreground.xml")

INK = "#1A1A1A"; LIME = "#C9E926"

# variant C geometry on the 0..1000 artboard (identical to make_brand.py)
STEM = (170, 150, 310, 864)
BOWL = (170, 150, 660, 520); BOWL_R = 185
CNTR = (315, 262, 512, 402); CNTR_R = 70
BARS = [((360, 600, 760, 712), 56), ((300, 752, 905, 864), 56)]
CONTENT = (170, 150, 905, 864)

SAFE_R = 32.0  # target radius (dp) for the content's far corners; safe zone = 33

cx = (CONTENT[0] + CONTENT[2]) / 2
cy = (CONTENT[1] + CONTENT[3]) / 2
half_diag = math.hypot((CONTENT[2] - CONTENT[0]) / 2, (CONTENT[3] - CONTENT[1]) / 2)
S = SAFE_R / half_diag


def tx(x): return round((x - cx) * S + 54, 3)
def ty(y): return round((y - cy) * S + 54, 3)
def tr(r): return round(r * S, 3)


def rrect(box, r):
    x0, y0, x1, y1 = box
    x0, y0, x1, y1 = tx(x0), ty(y0), tx(x1), ty(y1)
    r = tr(r)
    return (f"M{x0 + r},{y0} L{x1 - r},{y0} A{r},{r} 0 0 1 {x1},{y0 + r} "
            f"L{x1},{y1 - r} A{r},{r} 0 0 1 {x1 - r},{y1} "
            f"L{x0 + r},{y1} A{r},{r} 0 0 1 {x0},{y1 - r} "
            f"L{x0},{y0 + r} A{r},{r} 0 0 1 {x0 + r},{y0} Z")


def rect(box):
    x0, y0, x1, y1 = box
    x0, y0, x1, y1 = tx(x0), ty(y0), tx(x1), ty(y1)
    return f"M{x0},{y0} L{x1},{y0} L{x1},{y1} L{x0},{y1} Z"


p_bowl = rrect(BOWL, BOWL_R) + " " + rrect(CNTR, CNTR_R)   # evenOdd -> counter hole
p_stem = rect(STEM)
bars = "\n".join(
    f'    <path android:fillColor="{LIME}" android:pathData="{rrect(b, r)}" />'
    for b, r in BARS)

xml = f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="{INK}"
        android:fillType="evenOdd"
        android:pathData="{p_bowl}" />
    <path
        android:fillColor="{INK}"
        android:pathData="{p_stem}" />
{bars}
</vector>
'''

with open(os.path.normpath(OUT), "w", encoding="utf-8") as f:
    f.write(xml)
print("wrote", os.path.normpath(OUT))
print(f"scale={S:.5f}  content maps to x[{tx(170)}..{tx(905)}] y[{ty(150)}..{ty(864)}]")
