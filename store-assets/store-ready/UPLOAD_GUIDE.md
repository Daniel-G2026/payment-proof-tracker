# Play Console — what to upload from this folder

These are the **final, polished** listing assets (designed externally), verified
Play-compliant. Upload them in the Play Console "Store listing" → "Graphics".

| Play slot | Upload this file |
|-----------|------------------|
| **App icon** | `01_ProofLedger_App_Icon_512_FULLBLEED_for_Play.png` |
| **Feature graphic** | `02_ProofLedger_Feature_Graphic_1024x500.png` |
| **Phone screenshots** | all 7 in `Screenshots_1080x1920/`, in order `01…07` |

## Why two icon files
- `…_FULLBLEED_for_Play.png` — the monogram on a **full white square, no rounded
  corners, no shadow**. This is the one Play wants: Google applies the squircle
  mask + elevation itself, so a pre-rounded/shadowed icon would get
  **double-rounded** and look wrong.
- `01_ProofLedger_App_Icon_512x512.png` — the original **squircle-with-shadow**
  version. Keep it for marketing / website / social — **do not** use it for the
  Play app-icon slot.

## Verified specs (all files)
- Icon: 512×512, no alpha.
- Feature graphic: 1024×500, no alpha.
- Screenshots: 1080×1920 (9:16, within the ≤2:1 cap), no alpha.

## Notes
- The proof screenshot uses a **mock receipt with fake account numbers** — safe to
  publish.
- Screenshots + the 512 store icon are **editable anytime** in Play Console (short
  review, no app build). The **launcher icon inside the app** still needs a fresh
  `:app:bundleRelease` with a bumped `versionCode` to change on users' phones.
