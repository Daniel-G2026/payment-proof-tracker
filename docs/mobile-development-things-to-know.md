# Mobile Development Things To Know

This is a practical reference for building and testing this Android app, plus general lessons that apply to many mobile projects.

## Local Backend Setup

Docker and Alembic are not required for every mobile app. They are needed here because the Android app talks to a local FastAPI backend, and that backend needs a local Postgres database.

For this project, the local stack has three layers:

1. Docker Desktop
   - Runs the container engine.
   - Must be open/running before local Postgres can run.

2. `docker compose up -d`
   - Starts the local services from `docker-compose.yml`.
   - In this repo, that means Postgres on `localhost:5432` and Redis on `localhost:6379`.
   - Run it after starting Docker Desktop. If services are already running, it is safe to run again.

3. FastAPI backend
   - Runs the API server the Android app talks to.
   - Uses `DATABASE_URL` from `backend/.env`.

Normal local startup:

```powershell
docker compose up -d
cd backend
uv run alembic upgrade head
uv run uvicorn app.main:app --reload
```

`uv run alembic upgrade head` applies database migrations. You do not strictly need it every single time, but it is safe to run. Run it when setting up the backend, after pulling new migrations, or after adding/changing tables.

If `/health` works but app data does not sync, remember: `/health` does not prove Postgres is working. Real routes need the database.

## Emulator Networking

The Android emulator cannot use `localhost` to reach your computer's backend. Inside the emulator, `localhost` means the emulator itself.

Use:

```properties
API_BASE_URL=http://10.0.2.2:8000/
```

in `android/local.properties`.

Useful checks:

```powershell
Invoke-WebRequest http://localhost:8000/health -UseBasicParsing
```

If the host backend works but the app does not sync, check:

- Docker Desktop is running.
- Postgres is listening on `localhost:5432`.
- Backend is running with `uv run uvicorn app.main:app --reload`.
- `android/local.properties` points to `http://10.0.2.2:8000/`.
- The installed APK was rebuilt after changing `local.properties`.

## Offline-First Sync

This app should save local data first, then sync when possible.

Good flow:

1. Save to Room immediately.
2. Mark the row as pending, such as `PENDING_UPLOAD`.
3. Try the backend.
4. If the backend succeeds, mark the local row `SYNCED`.
5. If the backend fails, keep the local row pending and retry later.

For dependent records, sync in dependency order:

1. Counterparties first.
2. Transactions second.
3. Screenshot proof backup after the transaction exists online.

Do not silently degrade a transaction from a selected counterparty to only a plain name. Locally, the transaction should keep the `counterpartyId`. If the counterparty cannot sync, the transaction should remain pending until the counterparty sync succeeds.

## UI and Infrastructure Boundaries

The UI will be polished and redesigned later. Expect future work such as settings, multiple businesses, tabs, navigation updates, dashboard redesign, and refined transaction flows.

Keep these boundaries clear:

- Compose screens own layout, visual state, and user interaction only.
- Screens should read ViewModel state and call ViewModel event functions.
- Screens should not call Room DAOs, Retrofit/API services, WorkManager, file storage, or token storage directly.
- ViewModels should coordinate screen state, validation, and repository actions.
- Repositories/services should own data behavior, sync, proof backup, auth/session behavior, and file operations.
- Infrastructure should not import Compose or depend on screen layout.
- If a redesigned screen needs different display data, prefer adding a UI state/model instead of reshaping database entities for presentation.

This keeps the app ready for UI polish without forcing database, network, proof backup, or sync rewrites.

## Figma-to-Compose UI Alignment

The Figma prototype defines visual direction, while Android still owns native
accessibility, keyboard behavior, system bars, scrolling, and back navigation.
Match the design closely without turning screenshots into fixed-position
layouts.

### Translate the system, not isolated pixels

- Start with shared colors, type, spacing, shapes, borders, control heights,
  icons, and screen gutters.
- Reuse `ProofLedgerDimens`, `ProofLedgerShapes`, `ProofIcons`, and the shared
  components under `ui/components`.
- Keep screen content inside the shared scaffold/header and system-inset
  handling. Do not add per-screen status-bar padding.
- Use 20dp horizontal content gutters, 16dp panel radii, 14dp control radii,
  quiet one-pixel borders, and 48dp minimum touch targets unless the design
  contract is intentionally revised.
- A visually small icon may sit inside a transparent 48dp hit target.

### Fonts and licensing

- Interface copy uses bundled Inter.
- Financial digits use bundled DM Mono.
- The Naira sign and surrounding text stay in Inter; only numeric characters
  switch to DM Mono through the shared `MoneyAmount` composable.
- Keep font files in `android/app/src/main/res/font` and their license notices
  in `android/app/src/main/assets/licenses`.
- Do not rely on a font being installed on the developer machine or device.

### Responsive density and system insets

- Design and compare near the prototype width, then verify at 360dp and 430dp.
- Prefer weighted rows, bounded widths, horizontal scrolling for filter sets,
  and adaptive text over fixed coordinates.
- Use the shared screen padding modifier and Scaffold insets so content clears
  status, navigation, and gesture areas.
- Test keyboard-visible forms. The focused field and primary action must remain
  reachable by scrolling.
- Test at font scales 1.0 and 1.3 with long business, category, project, unit,
  and person names.

### Adaptive financial typography

- Format money centrally; do not build currency strings ad hoc in screens.
- Whole Naira values omit `.00`; actual kobo retain two decimals.
- Render financial summaries with `MoneyAmount` so the Naira sign and digits
  use the correct fonts and large values can step down before clipping.
- Test zero, negative values where supported, kobo, millions, billions, and
  the longest values the product can realistically store.

### Accessibility versus visual fidelity

- Figma controls hierarchy, color, density, and composition. Android controls
  semantic roles, focus order, back behavior, dynamic type, and minimum touch
  areas.
- Do not shrink hit targets or disable text scaling merely to match a static
  frame.
- Icon-only actions need content descriptions. Decorative icons should not be
  announced.
- Color cannot be the only signal for money direction, proof state, errors, or
  sync state.

### Screenshot-overlay QA

1. Capture the matching Figma frame and emulator screen at approximately the
   same 393-411dp width.
2. Overlay them at partial opacity or compare them side by side.
3. Check hierarchy first: header, major totals, primary action, sections, and
   bottom navigation.
4. Then check type baselines, gutters, panel bounds, row heights, icon size,
   borders, and empty space.
5. Repeat after testing 360dp, 430dp, font scale 1.3, keyboard display, proof
   images, empty/error/loading states, and very large Naira amounts.

### Avoid raw Material defaults

Material components are implementation primitives, not the finished visual
language. Apply the ProofLedger shape, colors, typography, dimensions, and
state treatment whenever using a Material field, menu, dialog, button, chip,
or navigation component. If a default cannot be styled faithfully, compose the
surface from the app's shared primitives instead of accepting visual drift.

## Screenshot Proof Backup

Proof images are different from normal transaction data because mobile data is expensive for many users, especially in Nigeria.

Rules for this app:

- Always preserve the original screenshot locally.
- Store a local proof record in Room.
- Upload only when the user's backup preference allows it.
- Backup options should include Wi-Fi only, manual backup, and important proofs only.
- Show backup status clearly:
  - Saved on phone only
  - Waiting for Wi-Fi
  - Pending backup
  - Backing up
  - Backed up
  - Backup failed

Compression policy:

- If original proof is `<= 500 KB`, upload it as-is.
- If larger, create an optimized upload copy.
- Resize to max long edge around `1800 px`.
- Encode upload copy as JPEG quality `85`.
- Use the compressed copy only if it is smaller.
- Never replace the local original unless the user explicitly chooses a storage-saving feature.

## Debugging Sync Problems

When something does not sync, identify which link in the chain is broken:

```text
Android app -> FastAPI backend -> Postgres database
```

Common symptoms:

- App shows data locally, but backend does not update:
  - Local Room write succeeded.
  - Backend write failed or was never retried.

- `/health` works, but app requests time out:
  - FastAPI is running.
  - Postgres may be stopped or unreachable.

- Proof backup stays pending or backing up:
  - Transaction may still be pending locally.
  - Backend may not know the transaction yet.
  - Backend may reject image type or size.
  - Network/backend/database may be unavailable.

Useful Android tools:

```powershell
adb devices
adb logcat
adb install -r android\app\build\outputs\apk\debug\app-debug.apk
```

Useful backend checks:

```powershell
Invoke-WebRequest http://localhost:8000/health -UseBasicParsing
Test-NetConnection -ComputerName localhost -Port 5432
```

## Debug vs Release Builds

There are two builds of this app and they exist for different reasons. Use the right one for the task.

### Debug build — your everyday loop

This is what you get when you press the green ▶ Run button in Android Studio, or run:

```powershell
cd android
./gradlew installDebug
```

- Fast to compile (~10 seconds incremental).
- Not minified. Logs are visible in `adb logcat`. Reflection works freely.
- Signed with the Android Studio debug key, which is fine for personal devices.
- **This is the build you use for 95% of development.**

### Release build — what real users would get

This is the minified, Play-Store-shaped APK. Build with:

```powershell
cd android
./gradlew :app:assembleRelease
```

Install with:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\release\app-release.apk
```

- Slower (~1.5 minutes).
- Minified by R8: dead code stripped, classes/methods renamed.
- `BuildConfig.DEBUG`-gated logs are stripped, so `adb logcat` is quieter.
- Currently signed with the debug key as a convenience — fine for installing on your own device, NOT fine for Play Store submission.
- Mapping file at `app/build/outputs/mapping/release/mapping.txt` is the only way to decode obfuscated stack traces later. Keep it.

### When to bother with a release build

You do NOT need to rebuild and reinstall the release APK on every change. Use it only when:

- You are about to publish to the Play Store.
- You added a new library (or upgraded one to a major version) that might use reflection — Moshi, kotlinx.serialization, Hilt, anything new.
- You changed `proguard-rules.pro` or the R8/minify config.
- A user reports a crash you cannot reproduce in debug. Release-only crashes are almost always R8 stripping something it shouldn't.

In steady state this is maybe once a month, plus once per release.

### Release-only failure modes to watch for

R8 fails silently in specific ways. If a release smoke test shows any of these, suspect R8 first:

- Dashboard shows zero transactions even though the API returned data → Moshi adapter for a DTO got renamed. Add the DTO class to keep rules.
- App goes to sign-in on launch even though you were signed in before → Supabase session decode failed because a `@Serializable` class lost its `$serializer`. Add a `-keep @kotlinx.serialization.Serializable class * { *; }` rule if missing.
- Random `ClassNotFoundException` on startup → a Hilt-generated or reflection-loaded class got stripped. Stack trace will name it; add a keep rule.

Always smoke-test a release build by going through: sign in → see dashboard data → open a transaction → create a transaction → sign out → sign back in → data still there.

### Before Play Store submission

Before the first real release:

- Replace the debug signing config with a real release keystore loaded from `local.properties` (so credentials aren't committed).
- Confirm `isDebuggable = false` on the release build type (it already is).
- Upload the `mapping.txt` for that build to Play Console so future crash reports come back de-obfuscated.

## App Startup UX

Avoid remote-first startup when building offline-first apps.

Better pattern:

- Use cached local profile/business/transactions to show the app quickly.
- Start observing Room immediately.
- Run remote refresh in the background.
- Show a small syncing state instead of showing zeros as if they are final.

If the dashboard shows zeros for several seconds and then suddenly updates, the UI is probably waiting on a remote refresh before reading local data.

## Forms, Pickers, and Input UX

Hard-won, generalizable rules for any data-entry surface (forms, dialogs,
selectors). These are framework-agnostic; the examples are Compose but the
principles apply to any mobile UI toolkit. Treat each as a default to follow
unless there's a specific reason not to.

### 1. Never ship a raw framework default that breaks the visual language

A toolkit's stock dialog/sheet/menu inherits the toolkit's default theme, not
your app's. Dropping a default `AlertDialog` (or any stock component) into a
custom-designed app produces a surface that clashes — wrong colours, wrong
corners, wrong typography (the classic "why is there a purple card?" moment).

- **Rule:** build dialogs, bottom sheets, and pickers from your own surface
  primitives — your card colour, your radius, your text colours, your buttons —
  not the framework's themed defaults.
- **Smell:** a screen looks "off-brand" or suddenly colourful/greyer than the
  rest of the app. It's almost always a stock component leaking the base theme.

### 2. Selectors must be bounded and searchable — never dump the whole list

A picker that renders every row works with 5 items and collapses with 500. It
doesn't scale, and it buries the one item the user wants.

- **Rule:** a selector = a **search field** + a **bounded** result list (show
  the top N matches, not all) + an **inline "create new"** for the common case
  where the thing isn't there yet. The user searches or creates; they never
  scroll an unbounded wall.
- **Smell:** `list.forEach { Row(...) }` directly over a repository's full
  collection inside a dialog. Cap it and add a query.

### 3. Disambiguate list items with a secondary label

When two items can share a primary label (two people named "Ada"), a single
line is ambiguous — the user can't tell which is which.

- **Rule:** show a small **subtitle** carrying disambiguating context (type,
  role, category, last-four, location — whatever distinguishes them).
- **Example:** in a person picker, render the counterparty **type** under the
  name. In a unit picker, render the project.

### 4. Offer a filter for large selectable sets

Search narrows by name; a **filter** narrows by category. Both together make a
big set tractable.

- **Rule:** for sets with meaningful categories (person type, status, project),
  add quick **filter chips** (an "All" plus each category) above the results.
- Reuse the *same* filter component the rest of the app uses, so it looks and
  behaves identically everywhere.

### 5. Make repeatable-input forms look like a list

A single empty row reads as "fill in this one field." Users then submit after
one entry and are surprised the form wanted several.

- **Rule:** seed multi-item entry forms with **two rows** (each removable with
  an ✕), so the shape says "this is a list of things." Provide an obvious
  "Add another" affordance, but don't rely on the user discovering it to learn
  the form is repeatable.
- **Smell:** the form starts with exactly one input group for something that is
  conceptually a list (line items, installments, attendees).

### 6. Separate view mode from edit mode; never leave a form sitting in edit

Once data is saved, showing it in the same editable form is confusing: it isn't
clear whether you're looking at saved state or a draft, and the primary button's
meaning can silently change (e.g. "Save" quietly becoming "Save as new
version") based on hidden state.

- **Rule:** saved data renders **read-only** with an explicit **"Edit" /
  "Revise"** entry point. Tapping it enters edit mode; only then does the action
  button take on its edit-mode meaning. First-time creation opens straight into
  edit mode.
- **Why it matters:** the user is never surprised by what a button does, and
  "view" vs "change" is a deliberate choice, not an accident of state.
- **Smell:** the same screen shows an always-present editable form plus a button
  whose label depends on whether a record already exists.

### 7. Format numeric input inline, as the user types

Raw digit strings (`1250000`) are hard to read and easy to mis-enter. People
read grouped numbers (`1,250,000`).

- **Rule:** apply digit grouping (and currency conventions) **on every
  keystroke**, storing the canonical value separately from the display string.
  Use the **same** formatter across every money field in the app.
- **Smell:** one form groups digits and another shows raw numbers — inconsistent
  formatters are a tell that the logic wasn't centralised.

### 8. Constrained inputs use pickers, not free text

Dates, times, and enumerated values typed by hand invite malformed input
(`2026-13-40`, `tomrrow`) and force the user to know the exact format.

- **Rule:** use the platform **date/time picker** for dates/times and a
  selector for enums. Free-text entry is for genuinely open fields (names,
  notes) only.
- **Bonus:** extract one shared "open the date picker" helper and call it from
  every form, so date entry feels identical app-wide.

### Quick self-check before shipping a form or picker

- Does any dialog/sheet use a stock component that ignores the app theme?
- Could this list grow large? If so, is it searchable + bounded + create-inline?
- Can two rows look identical? If so, add a subtitle.
- Is a "list-shaped" form starting with a single row?
- After save, does the screen drop to a clean read-only view with an explicit
  edit entry — or does it linger in edit mode?
- Do money fields group digits, and do dates/enums use pickers?

## Browse Lists That Accumulate

The "Forms, Pickers, and Input UX" rules above (§2 *bounded + searchable
selectors*, §4 *filter large sets*) are about form **pickers**. The same rule
applies to **browse / navigation lists** — any on-screen list that grows without
bound as the business is used. If a list can only ever get longer, a plain
`items.forEach { Row(...) }` is a latent bug: eventually the user is scrolling a
wall to find one thing.

**Anything that accumulates gets a query bar.** What accumulates in this app:
units (houses) per project, projects, people, transactions, categories, person
types, schedule versions, sync issues — anything the user keeps adding to over
months. Assume every one of these will be long for a real user, even if it's
short in dev.

The pattern (mirror `CounterpartyListScreen` / `TransactionListScreen`, which
already do this):

- **Search field at the top**, filtering the full set by name/label, local-first
  (filter the Room-backed list; don't round-trip the network to search).
- When the query is blank, show only a **bounded recent slice** (~5 most recent,
  newest first) — not the whole list.
- **Be honest about truncation.** Show a "Showing 5 of 23 — search to find the
  rest" line so a hidden item never reads as "doesn't exist." Never silently cap.
- Keep **create-inline** where it exists (e.g. "Add house") above the list, so
  adding doesn't require leaving the screen.

Rule of thumb: if you're about to render a list that can only grow, add the
query bar + recents cap *before* you ship it — not after a user has 80 rows.
(Known current offender to retrofit: the units list on `ProjectDetailScreen`.)

## General Mobile Lessons

- The emulator is not the phone; networking and Wi-Fi constraints can behave differently.
- WorkManager jobs may wait for constraints such as unmetered network.
- If a user taps an immediate action like `Back up proof`, give immediate feedback and a clear error if it fails.
- Do not swallow sync errors forever. It is okay to keep data pending, but developers need logs and users need understandable status.
- Always rebuild and reinstall the app after changing Gradle config, `local.properties`, networking, or dependency injection setup.
- Keep local-first behavior honest: local data should be useful even when backend services are down.
