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

## General Mobile Lessons

- The emulator is not the phone; networking and Wi-Fi constraints can behave differently.
- WorkManager jobs may wait for constraints such as unmetered network.
- If a user taps an immediate action like `Back up proof`, give immediate feedback and a clear error if it fails.
- Do not swallow sync errors forever. It is okay to keep data pending, but developers need logs and users need understandable status.
- Always rebuild and reinstall the app after changing Gradle config, `local.properties`, networking, or dependency injection setup.
- Keep local-first behavior honest: local data should be useful even when backend services are down.
