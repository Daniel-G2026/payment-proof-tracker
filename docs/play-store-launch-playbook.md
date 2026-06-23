# Play Store Launch Playbook

A reusable, lessons-learned guide for taking an Android app from "code complete"
to "live on Google Play." Written from the ProofLedger v1 launch, but the
**lessons are generalized** — ProofLedger specifics appear as worked examples.
Hand this (and `mobile-development-things-to-know.md`) to Claude/Codex as context
when shipping the next app.

> **The golden rule — the consistency triangle.** Three things must agree, or
> Google rejects or pulls the app: **(1) what the shipped build actually does**,
> **(2) the privacy policy**, **(3) the Data Safety form**. Google cross-checks
> the form against observed network/runtime behavior. Every time you change one,
> change the other two. Most launch pain traces back to these drifting apart.

---

## Phase 0 — Developer account & device verification

- A **Personal** developer account costs **$25** one-time and requires identity
  verification.
- **On-device verification is real and blocks you:** Google asks you to open the
  **Play Console app** on a **Play-certified Android device**, signed in with the
  *exact* developer Google account. **An emulator cannot do this** — Android
  Studio emulators are uncertified (restricted Play catalog) and can't be
  certified. Use a real retail phone. The #1 failure is the phone's active Google
  account not matching the developer account.
- **New Personal accounts must run closed testing first:** ~**20 testers for 14
  continuous days** before you're allowed to publish to production. This is a
  *lead-time* constraint — start the testing track early; it gates your launch
  date more than any code does.

## Phase 1 — App identity & signing

- **Package name = `applicationId`** (from `app/build.gradle.kts`), not
  `namespace` (they're often equal). It is **permanent** once the first AAB is
  uploaded — never renameable or reusable for another app. Choose deliberately.
- **Free vs Paid is a one-way door:** Free → Paid is **not** allowed (Paid → Free
  is). Pick Free unless you truly want an upfront price gate.
- **"Free" does not mean you can't charge.** Free = free to *download*. You
  monetize on top via in-app subscription, or — for premium/B2B — via **direct
  billing** (invoice/Paystack/bank transfer) while the app stays a free **login
  client** with no purchase UI. See *Monetization* below.
- **Play App Signing** is auto-enrolled: Google holds the *app signing key*; your
  keystore is the *upload key*. Upshot: a lost **upload** key is recoverable via
  Google; a lost app signing key is not (Google holds it). Still back up your
  upload keystore off-machine.

## Phase 2 — A release build that's actually shippable

`assembleDebug` proves almost nothing about the artifact you ship. The release
AAB differs in ways that bite late:

- **Signing fallback trap:** a typical `build.gradle.kts` loads signing creds
  from a gitignored `keystore.properties`; **if that file is absent it silently
  falls back to debug-signing** and the build still "succeeds." A debug-signed
  AAB is **not publishable**. Always confirm `keystore.properties` is present and
  **verify the signature on the output** (`apksigner verify --print-certs`)
  before submitting. (Corollary: tools/agents reporting "no keystore.properties"
  may just be checking the wrong directory — verify on disk.)
- **Minification (R8) + resource shrinking** are on for release. Reflection-using
  libs (Moshi, Retrofit, Room, Hilt) and any new SDK need keep rules. Most SDKs
  ship *consumer* ProGuard rules — confirm R8 produced **no `missing_rules.txt`**
  and **smoke-test the actual minified artifact on a real device**, not debug.
- **Point the build at production:** the API base URL must be set to prod (e.g.
  in `local.properties`); a debug default (`10.0.2.2`/localhost) ships an app
  that's broken for real users.
- **"Merged ≠ shipped":** code merged to `main` only reaches users when a fresh
  `bundleRelease` is built **and uploaded** to Play. The backend half may deploy
  automatically; the mobile half never does.
- Bump `versionCode` (must increase every upload) and `versionName`.

## Phase 3 — Store listing

- **App name** ≤ 30 chars · **short description** ≤ 80 · **full description** ≤
  4000. Write in the product's plain user language.
- **Category:** for a money/records tool aimed at businesses, **Business** often
  beats **Finance** — the Finance category invites extra financial-app scrutiny
  you may not need (you don't move money).
- **Required graphics — exact specs (Google rejects on these):**
  - **App icon:** 512×512, 32-bit PNG **with** alpha, ≤ 1 MB.
  - **Feature graphic:** 1024×500, JPEG or 24-bit PNG **no alpha**, ≤ 1 MB.
  - **Phone screenshots:** **2–8**, PNG/JPEG **no alpha**, each side 320–3840 px,
    **longer side ≤ 2× shorter (max 2:1)**, ≤ 8 MB. 1080×1920 (9:16) is the safe size.
  - **Tablet (7"/10") screenshots are optional** — leaving them empty does *not*
    block publishing; it just shows a "not designed for tablets" note + lower
    large-screen ranking. Fine for a phone-first app.
- **The 2:1 cap bites:** a raw screenshot from a modern phone is ~20:9 (2.22:1)
  and is **rejected**. Don't upload raws — frame each onto a 1080×2160 (2:1) canvas.
- **Screenshots in ~10 min via emulator + adb (no design tool):**
  1. Run the app on an emulator with seeded data (a debug build pointed at **prod**
     works) or a real device.
  2. Clean status bar with SystemUI **demo mode**:
     `adb shell am broadcast -a com.android.systemui.demo -e command enter`, then
     `clock -e hhmm 1000`, `battery -e level 100 -e plugged false`,
     `network -e wifi show -e level 4 -e fully true`, `notifications -e visible false`;
     `… -e command exit` when done.
  3. Capture with `adb exec-out screencap -p > shot.png` — use `exec-out`, **not**
     `shell screencap` (Windows CRLF corrupts the PNG). Navigate with
     `adb shell input tap X Y` / `keyevent`.
  4. Frame each onto a branded 1080×2160 canvas + caption with a small Pillow script
     (reuse `store-assets/frame_screenshots.py`). Output no-alpha RGB, upload-ready.
  - **Order them as a story; lead with your differentiator** (niche app → niche
    screens first).
  - **Scrub sensitive pixels:** a real bank receipt / account number / phone number
    shown as proof must be **blurred or pixelated** before a public listing — and
    store only the redacted copy (never commit the original).
- **Editability:** the **512 icon + listing text/screenshots change anytime** (short
  review, no new build); the **launcher icon ships in the AAB** (needs a `versionCode`
  bump + re-upload to reach users).

## Phase 4 — App content declarations (the gating checklist)

These must all be cleared before submission. The non-obvious ones:

### Privacy policy URL
- Must be **public, no login**. A **GitHub gist works** — but the gist file
  **must be named `*.md`** or GitHub renders it as **plain text** and ignores all
  markdown (headers/bold show literally or get stripped). Renaming the file does
  **not** change the gist URL, so you can fix formatting without re-pasting links.

### App access (reviewer login) — mandatory for login-gated apps
- Reviewers need **working credentials**. Create a **dedicated, seeded test
  account on production** (never your real account); add sample data so it
  doesn't look broken.
- **OTP / passwordless / magic-link apps are the hard case** — there's no static
  password, and the reviewer can't receive a code sent to your inbox. Options,
  best first:
  1. **Fixed test OTP** at the auth provider, if supported. *(Supabase note:
     fixed test OTP exists for **phone only**, not email — so this was unavailable
     for ProofLedger's email-OTP.)*
  2. **Give the reviewer a dedicated inbox's webmail login** so they fetch the
     code themselves. Put the inbox login in the App-access *instructions* field.
     - **Gmail caps accounts per phone number** — you may be unable to create
       another. **Outlook.com / Proton** don't have that friction; use them.
     - **Warm the inbox up** (sign in a few times, **no 2FA**, add a recovery
       address) so the reviewer's login isn't security-challenged.
- **Don't build a username/password feature just to satisfy the reviewer** — it's
  a new auth surface + a forced rebuild + a weaker security story (OTP-only is a
  feature for a financial app). A 2-minute alternate inbox beats it.

### Data Safety
- **Declare what the app *accesses or uses*, not what could incidentally appear
  in user uploads.** If you don't parse/store a data type, you don't declare it —
  you declare the **container** (Photos / Files and docs). *Example: an offer
  letter may print a buyer's postal **address**, but the scan only extracts
  name/amounts/dates → **Address = No**; the document is covered by Photos/Files
  and docs.*
- **"Sharing" excludes service providers.** Data sent to processors acting on
  your behalf (auth host, hosting, object storage, an AI API, analytics, crash
  reporting) is **collected but not shared** → **Shared = No** is usually correct
  across the board.
- **"Ephemeral" = in-memory only, never stored** → **No** for anything you
  persist (which, for a records app, is everything — even short-retention blobs).
- **Advertising ID:** **No** unless an SDK adds the `AD_ID` permission — verify
  against the **merged manifest** (`build/intermediates/.../merged_manifest/.../AndroidManifest.xml`),
  not just the source manifest. Analytics SDKs like PostHog/Sentry use their own
  anonymous IDs, not the Google Advertising ID.
- **Required vs Optional:** auto-collected data with no user opt-out (analytics,
  crash logs) is **Required** ("users can't turn it off"); data tied to an
  optional feature (a phone number, an attached photo) is **Optional**.
- Keep a per-app answer map (see `play-data-safety-DRAFT.md`) and re-check it
  whenever a new SDK or data flow is added.

### Data deletion URL — for any account-creating app
A public page that: **(1)** names the app + developer as shown on the listing,
**(2)** prominently lists the **steps** to request deletion, **(3)** specifies
what's **deleted vs kept** and any retention period.
- **Email-based deletion is acceptable** — there's no requirement for an in-app
  "delete account" button.
- **One page can serve both** the full-account-deletion field and the optional
  "delete some data without deleting the account" field — just include a section
  for each.

### Content rating vs Target audience — different things
- **Content rating** (IARC questionnaire) rates *content*; a business/finance tool
  is **"Everyone."**
- **Target audience** declares *who the app is for*. For an adults' business/
  financial tool, select **18+ only** — including under-18 triggers the strict
  **Families Policy** and child-data obligations. Selecting 18+ **does not block**
  younger users from installing; it's a declaration of intent. A separate
  **"restrict minors"** toggle *does* filter the app from accounts known to be
  minors — fine (often preferable) to enable for a financial app.

### The quick ones
- **Ads → No** (and for premium/B2B, keep it No — ads contradict premium
  positioning and add advertising-ID/consent burden).
- **Financial features → none** for a record-keeping tool (no loans, crypto,
  investments, or payment processing).
- **Government / News / Health → No.**

## Phase 5 — Observability (optional, dev-only) and its consistency chain

Crash reporting (e.g. Sentry) + product analytics (e.g. PostHog) are worth having
from day one, but **enabling them is a chain, not a toggle** — and it ripples
into the consistency triangle:
1. Create the provider projects → get the **DSN** / **API key**.
2. Put keys in `local.properties` (gitignored; key-guarded code stays no-op
   without them).
3. **Rebuild** the AAB — the keys only take effect in a fresh build that's then
   uploaded.
4. **Re-verify** the minified build (new SDKs → recheck R8 keep rules).
5. **Update the privacy policy** (present tense + list the new providers).
6. **Add the Data Safety rows** (App interactions / Crash logs / Diagnostics /
   Device IDs). Sharing still **No** (service providers).
- Architect analytics behind an interface (`Analytics` + `NoOp` impl, key-guarded
  DI) so it's literally a config flip and screens never call the SDK directly.

## Phase 6 — Closed testing → production

- Set up the **closed testing** track, add the upload (the first installable-from-
  Play build against prod), and recruit your **~20 testers**. For a B2B product,
  your testers can be real prospects piloting for free.
- Use the closed test to seed the reviewer test account and to do the real-device
  QA you can't do on an emulator.
- After the 14-day window, promote to production (consider a **staged %
  rollout** so a bad build is recoverable; keep the previous AAB).

---

## Monetization notes (decide early — it shapes the build)

- **Free + in-app subscription** (Google Play Billing) is the default for
  self-serve consumer/prosumer apps. Google takes **15%** of subscription revenue
  on the first $1M/yr (then 30%); for in-app *digital* goods you generally **must**
  use Play Billing.
- **Premium B2B (e.g. per-seat, high monthly price)** is usually billed
  **directly** (invoice/Paystack/bank transfer), with the Play app as a free
  **login client** and **no purchase UI**. Reasons: Play Billing maps poorly to
  org/seat licensing; buyer-side Play payments are weak in some markets (e.g.
  Nigeria); you keep the 15–30% and own the relationship.
- **Anti-steering:** if you bill outside Play for digital goods, don't put
  "subscribe on our website" CTAs/links inside the app. Confirm against current
  Play Payments policy.
- This model needs an **org/tenant + seats + entitlement gating + provisioning**
  on the backend — a real phase. You can charge first (manual provisioning) and
  build the system once a paying customer pulls for it.

## One-screen pre-submit checklist

- [ ] Real device verified; developer account identity-verified.
- [ ] `applicationId` final; Free/Paid chosen.
- [ ] Release AAB **signed with the upload key** (signature verified), minified,
      smoke-tested on a real device, pointed at **prod**, `versionCode` bumped.
- [ ] Store listing: name/short/full/category + **512 icon (alpha)**, **1024×500
      feature graphic (no alpha)**, **≥2 screenshots (no alpha, ≤2:1, sensitive
      pixels blurred)**.
- [ ] Privacy policy URL live & public (gist is `*.md`).
- [ ] App access: seeded prod test account; reviewer can actually get in
      (OTP inbox login documented).
- [ ] Data Safety matches the build; privacy policy matches Data Safety.
- [ ] Data deletion URL (3 requirements) live.
- [ ] Content rating done; Target audience 18+; Ads/Financial/Gov/News/Health
      answered.
- [ ] Observability keys in (if used) + policy/Data Safety updated to match.
- [ ] Closed testing running (≥20 testers, 14 days) before production.
