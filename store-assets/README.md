# Store assets — Google Play listing

Brand: the **PL + ledger monogram** in ink `#1A1A1A` + lime `#C9E926` on white — the
app's real palette (`NeutralInk` + `ProofLime`). Font: **Inter**.

The mark is geometry-defined once and rendered everywhere, so the store icon and the
installed launcher icon are identical. Two sizes of the mark by design:
- **Variant C (2 ledger lines)** — the app icon + launcher (legible at 48px).
- (3-line version exists in `make_variants.py` for large-only uses if ever wanted.)

Scripts:
- `python make_brand.py` → regenerates `app-icon-512.png`, `feature-graphic.png`, `icon-48-test.png`.
- `python gen_launcher_vector.py` → regenerates the Android launcher foreground VectorDrawable.
- `python frame_screenshots.py` → frames raw device screenshots into Play-compliant images.
- `python make_variants.py` → renders the A/B/C/D icon comparison sheet (`icon-variants.png`).

## The five "Graphics" slots in Play Console

| Slot | Spec | Required? | Status |
|------|------|-----------|--------|
| **App icon** | 512×512, 32-bit PNG **with** alpha, ≤1 MB | ✅ Yes | ✅ `app-icon-512.png` (variant C monogram) |
| **Feature graphic** | 1024×500, JPG or 24-bit PNG **no** alpha, ≤1 MB | ✅ Yes | ✅ `feature-graphic.png` |
| **Phone screenshots** | 2–8 imgs, PNG/JPEG, each side 320–3840 px, **longer side ≤ 2× shorter (max 2:1)**, ≤8 MB. 1080×1920 (9:16) is the sweet spot | ✅ Yes (min 2) | ✅ 7 captioned shots in `screenshots/framed/` (1080×2160) |
| **7-inch tablet** | same rules as phone | ❌ Optional | ⏭️ Skipping (phone-first v1) |
| **10-inch tablet** | same rules as phone | ❌ Optional | ⏭️ Skipping (phone-first v1) |

### Gotchas baked into the above
- **Screenshots: max 2:1.** A raw 1080×2400 (20:9 = 2.22:1) device shot is **rejected**. `frame_screenshots.py` outputs 1080×2160 (exactly 2:1).
- **Screenshots & feature graphic must NOT have an alpha channel** (24-bit). Only the **icon** keeps alpha (32-bit).
- **Tablet shots are optional to publish.** Leaving them empty just shows a "not designed for tablets" note + lower ranking on large screens — it does **not** block release.

## Where the icon lives, and how to change it later
- **Play listing 512 icon** — change anytime in Play Console (short review, no app build).
- **Installed launcher icon** — `android/app/src/main/res/drawable/ic_launcher_foreground.xml`
  (the mark) + `ic_launcher_background.xml` (white). Shipped inside the AAB, so a change
  reaches users only via a new build with a **bumped `versionCode`** + upload to Play.
  Verified building: `:app:assembleDebug` (passes).

## Screenshots — how they were made / how to redo them
Captured from the Pixel_7 emulator via adb (`exec-out screencap`), with SystemUI
demo mode on for a clean status bar:
`adb shell am broadcast -a com.android.systemui.demo -e command enter` (… clock/battery/
network/notifications), and `… command exit` when done. Raw 1080×2400 captures live in
`screenshots/raw/` (N-name.png); `frame_screenshots.py` frames them to 1080×2160 (2:1,
captioned) in `screenshots/framed/`. To change the set: re-capture into `raw/`, edit the
`CAPTIONS` map, rerun. The current 7 are construction-forward (home · arrears · unit
schedule · houses+buyers · proof · reports · add).
