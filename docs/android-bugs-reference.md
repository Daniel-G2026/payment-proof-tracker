# Android Development — Bug Reference

_Phases 1 & 2 of the Payment Proof Tracker (ProofLedger) build._

Every bug we hit while scaffolding the FastAPI backend, the Kotlin + Jetpack Compose Android client, and the Supabase auth integration. Use this as a checklist when starting the next Android project — most of these are recurring traps in the stack rather than mistakes specific to this codebase.

Format for each bug: **What it was → What it affected → Root cause → Fix** (with code where relevant).

## 1. Environment & Tooling

_Issues with the host machine, JDK, Gradle, Kotlin daemon, and annotation processors. The majority of pain in Phase 1+2 came from here, not from product code. Most one-time fixes._

### 1.1  JAVA_HOME not set

- **What it was:** Running ./gradlew.bat from PowerShell failed with 'ERROR: JAVA_HOME is not set and no java command could be found in your PATH.'
- **What it affected:** Every Gradle operation from a terminal. Android Studio itself was unaffected because it ships its own JDK and uses it implicitly.
- **Root cause:** No JDK was installed system-wide. The JDK Android Studio uses (JBR 21) lives inside the Android Studio install directory and isn't on PATH or JAVA_HOME by default.
- **Fix:** Point JAVA_HOME at the bundled JBR. The exact path depends on the Android Studio install; on this machine it was 'C:\Program Files\Android\Android Studio1\jbr'. Set persistently at user level with PowerShell:

```
[Environment]::SetEnvironmentVariable(
    "JAVA_HOME",
    "C:\Program Files\Android\Android Studio1\jbr",
    "User"
)
```

### 1.2  VS Code terminal doesn't see new env vars

- **What it was:** JAVA_HOME was set persistently at the user level, but fresh terminals opened inside VS Code didn't see it. Confirmed via 'echo $env:JAVA_HOME' returning empty.
- **What it affected:** Every VS Code integrated terminal in an already-running VS Code window.
- **Root cause:** VS Code captures its environment block when the VS Code process launches. New integrated terminals inherit that captured snapshot, not the current user environment. Restarting VS Code would pick it up; opening a new tab within an existing window would not.
- **Fix:** Either fully restart VS Code, or (better) declare the env var inside VS Code's own settings.json so every integrated terminal gets it regardless of the host's env:

```
"terminal.integrated.env.windows": {
    "JAVA_HOME": "C:\\Program Files\\Android\\Android Studio1\\jbr"
}
```

### 1.3  Gradle wrapper files missing from repo

- **What it was:** ./gradlew.bat: command not found. The Gradle wrapper scripts and bootstrap JAR weren't committed.
- **What it affected:** Terminal builds. Android Studio works because it regenerates the wrapper when importing a project, but CLI users (and CI eventually) have nothing to run.
- **Root cause:** When scaffolding the Gradle project manually, only the build.gradle.kts / settings.gradle.kts / gradle-wrapper.properties files were created. The wrapper scripts (gradlew, gradlew.bat) and the wrapper JAR (gradle/wrapper/gradle-wrapper.jar) are normally produced by running 'gradle wrapper' once.
- **Fix:** Download the three missing files from the matching Gradle release tag on github.com/gradle/gradle. For Gradle 8.9:

```
curl -o gradlew https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradlew
curl -o gradlew.bat https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradlew.bat
curl -o gradle/wrapper/gradle-wrapper.jar https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar
```

### 1.4  Kotlin compile daemon crashes silently on JDK 21

- **What it was:** Gradle build failed at :app:kspDebugKotlin with 'The daemon has terminated unexpectedly on startup attempt #1 with error code: 0.' The misleading 'error code 0' meant the daemon started, reported ready, then exited cleanly but unexpectedly.
- **What it affected:** Every Gradle compilation. Build never succeeded.
- **Root cause:** Known compatibility issue between Kotlin 2.0.x compile daemon and JDK 21 on Windows. The daemon spawns, reports ready, then exits in a way Gradle interprets as a crash.
- **Fix:** Bypass the daemon by running Kotlin compilation in the Gradle worker process (slower per-build, no startup race). In gradle.properties:

```
kotlin.compiler.execution.strategy=in-process
kotlin.daemon.useFallbackStrategy=true
```

### 1.5  Gradle JVM heap exhausted by KSP + Hilt + Room

- **What it was:** Build occasionally OOMed or terminated annotation processing partway through with no clean error.
- **What it affected:** Builds that ran KSP across multiple annotation processors (Room + Hilt) plus large Kotlin libraries (supabase-kt, Ktor).
- **Root cause:** Default Gradle JVM heap is 2 GB. The combined memory demand of annotation processing on a non-trivial Compose + Hilt + Room + Supabase project pushes past that.
- **Fix:** Raise heap and Metaspace in gradle.properties:

```
org.gradle.jvmargs=-Xmx4096m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8
```

### 1.6  Room exportSchema warning escalates KSP failure

- **What it was:** KSP printed a warning about Room not having a schema export directory. In some KSP/Room version combos this appeared alongside a fatal KSP error.
- **What it affected:** KSP processing of the @Database class.
- **Root cause:** @Database(exportSchema = true) requires a room.schemaLocation annotation processor argument or the androidx.room Gradle plugin. Without either, Room warns; under some combinations the warning escalates.
- **Fix:** For Phase 1+2 we don't consume the schema files yet. Set exportSchema = false on AppDatabase. When you start using migrations seriously (Phase 10+), configure the Room Gradle plugin properly.

```
@Database(
    entities = [...],
    version = 2,
    exportSchema = false,  // re-enable + configure plugin in Phase 10+
)
```

## 2. Library & API Mismatch

_Cases where the code we wrote targeted one API but Gradle resolved a different one. Mostly caused by major-version renames (supabase-kt v2 -> v3, Material3 churn) and by assumptions that didn't match the actual library version on disk._

### 2.1  supabase-kt artifact renamed between v2 and v3

- **What it was:** Gradle sync error: 'Failed to resolve: io.github.jan-tennert.supabase:auth-kt'.
- **What it affected:** Entire Android build. Couldn't resolve Supabase auth library.
- **Root cause:** supabase-kt was renamed across major versions. In v2.x the auth module is 'gotrue-kt' and the package is 'io.github.jan.supabase.gotrue.*'. In v3.x they were renamed to 'auth-kt' and 'io.github.jan.supabase.auth.*'. I'd written v3 code (auth-kt + .auth.* imports) but pinned to v2.6.0 in the version catalog, so the v3 artifact name didn't exist at that version.
- **Fix:** Bump to a v3 release; Ktor must follow because v3 supabase-kt requires Ktor 3. In libs.versions.toml:

```
supabase = "3.0.3"   # was 2.6.0
ktor     = "3.0.2"   # was 2.3.12
```

### 2.2  Compose Material3 ExposedDropdownMenu can't be qualified

- **What it was:** Compile error: Unresolved reference 'ExposedDropdownMenu' on a call written as androidx.compose.material3.ExposedDropdownMenu(...).
- **What it affected:** CreateBusinessScreen.kt — the business-type picker.
- **Root cause:** ExposedDropdownMenu is a member of ExposedDropdownMenuBoxScope (the lambda receiver inside ExposedDropdownMenuBox), not a top-level composable. Fully-qualifying it with the package prefix doesn't resolve.
- **Fix:** Call it unqualified inside the ExposedDropdownMenuBox lambda — it's an extension function on the box scope:

```
ExposedDropdownMenuBox(...) {
    OutlinedTextField(
        ...,
        modifier = Modifier.menuAnchor(
            MenuAnchorType.PrimaryNotEditable, enabled = true
        ).fillMaxWidth(),
    )
    ExposedDropdownMenu(  // unqualified, scope member
        expanded = typeExpanded,
        onDismissRequest = { typeExpanded = false },
    ) { /* items */ }
}
```

### 2.3  menuAnchor() no-arg overload deprecated

- **What it was:** Compile warning escalating to error in stricter builds: menuAnchor() called with no arguments is deprecated.
- **What it affected:** Any OutlinedTextField inside ExposedDropdownMenuBox.
- **Root cause:** Material3 1.3+ introduced explicit anchor types so the dropdown knows whether the anchor is the primary editable field, a secondary affordance, etc.
- **Fix:** Pass MenuAnchorType.PrimaryNotEditable for a read-only text field that opens the menu, or PrimaryEditable if the user types in it:

```
modifier = Modifier
    .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
    .fillMaxWidth()
```

### 2.4  JWT algorithm mismatch: backend HS256 vs Supabase ES256

- **What it was:** Every authenticated backend call returned 401 even though the JWT secret was correct.
- **What it affected:** All protected backend routes from the Android app.
- **Root cause:** Supabase changed the default JWT signing algorithm for new projects from HS256 (symmetric, verified with the project's JWT secret) to ES256 (asymmetric, verified with the project's public key from the JWKS endpoint). The backend only knew how to verify HS256, so it rejected every ES256-signed token regardless of how correct the secret was.
- **Fix:** Accept both algorithms in the verification step. For HS256 use the project JWT secret; for ES256 fetch the public key from <SUPABASE_URL>/auth/v1/.well-known/jwks.json via PyJWT's PyJWKClient. Requires the crypto extra on PyJWT (which brings in 'cryptography'):

```
# pyproject.toml
dependencies = [
    ...,
    "pyjwt[crypto]>=2.9",
]

# app/core/auth.py — branch on JWT header alg
header = jwt.get_unverified_header(token)
if header["alg"] == "HS256":
    claims = jwt.decode(token, settings.supabase_jwt_secret, algorithms=["HS256"], audience="authenticated")
elif header["alg"] == "ES256":
    key = _jwks_client().get_signing_key_from_jwt(token).key
    claims = jwt.decode(token, key, algorithms=["ES256"], audience="authenticated")
```

## 3. Configuration

_Bugs that lived in service settings, manifest declarations, or external dashboards rather than in code. These usually present as 'the code is right but nothing works.'_

### 3.1  Supabase OTP email template sends magic link, not code

- **What it was:** After calling auth.signInWith(OTP) the user received an email containing a clickable 'Sign in' link instead of a 6-digit code. Tapping the link redirected to http://localhost:3000 (empty page).
- **What it affected:** Sign-in flow entirely. No way for the user to verify without entering a code, and the link path required deep linking we deliberately hadn't set up.
- **Root cause:** The Supabase 'Magic Link' email template is shared between magic-link and OTP flows. Default template body uses {{ .ConfirmationURL }} (a link). To get a code-based UX the template must use {{ .Token }} instead.
- **Fix:** In Supabase Studio: Authentication -> Email Templates -> Magic Link. Replace Subject and Body to use {{ .Token }}:

```
<!-- Body -->
<h2>Your sign-in code</h2>
<p>Enter this 6-digit code in the app:</p>
<p style="font-size: 32px; font-weight: bold;">{{ .Token }}</p>
```

### 3.2  OTP length hardcoded to 6 but Supabase default is 8

- **What it was:** User received an 8-digit code in their email but the app's code input truncated it to 6 and the Verify button stayed disabled (gated on length == 6).
- **What it affected:** Verify step of sign-in. Could not complete auth on Supabase projects defaulting to 8-digit codes.
- **Root cause:** Supabase's OTP length is project-configurable; default for newer projects is 8. The client app assumed 6.
- **Fix:** Either change the Supabase project's OTP length to 6 in Authentication -> Providers -> Email, OR widen the client to accept any length the user types and let the backend reject invalid lengths. Practically the cleanest is to align both on 6:

```
fun onCodeChange(value: String) = _state.update {
    it.copy(code = value.filter(Char::isDigit).take(6))
}
// ... and on the button:
enabled = state.code.length == 6
```

### 3.3  Android 9+ blocks cleartext HTTP by default

- **What it was:** Logcat: 'java.net.UnknownServiceException: CLEARTEXT communication to 10.0.2.2 not permitted by network security policy.' Backend never received any request from the app even though emulator Chrome could reach http://10.0.2.2:8000/health.
- **What it affected:** Every backend API call from the app — silently failed before leaving OkHttp.
- **Root cause:** Starting with Android 9 (API 28), apps may not make plain-HTTP connections unless they explicitly opt in. Our local FastAPI is on http (no TLS), so the OS blocked every request to 10.0.2.2.
- **Fix:** Create a network security config that whitelists cleartext for the emulator loopback host, and reference it from the manifest. Production builds talking to https:// don't need this exemption.

```
<!-- res/xml/network_security_config.xml -->
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">10.0.2.2</domain>
        <domain includeSubdomains="true">localhost</domain>
        <domain includeSubdomains="true">127.0.0.1</domain>
    </domain-config>
</network-security-config>

<!-- AndroidManifest.xml, on <application> -->
android:networkSecurityConfig="@xml/network_security_config"
```

## 4. Code Logic

_Bugs in our own application code. These are the ones most worth internalizing because they recur in any Android app talking to an async auth provider and a local cache._

### 4.1  SQLAlchemy 'instance not persistent' in PATCH /me

- **What it was:** pytest error: 'Instance Profile at ... is not persistent within this Session.' Production code worked fine because in real requests, get_current_user and the route share the same DB session — but the test override returned a Profile from a different session.
- **What it affected:** Test test_get_me_and_patch_me. Latent risk in any code path where get_current_user and the route end up on different sessions.
- **Root cause:** The PATCH /me route mutated current_user directly and called db.commit() on a different session — illegal operation on a detached instance.
- **Fix:** Re-fetch the user in the route's session before mutating. This also makes the route robust to any future change in how get_current_user resolves sessions:

```
@router.patch("/me")
def update_me(
    payload: ProfileUpdate,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
) -> ProfileOut:
    profile = db.get(Profile, current_user.id)
    if profile is None:
        raise HTTPException(status_code=404)
    for field, value in payload.model_dump(exclude_unset=True).items():
        setattr(profile, field, value)
    db.commit()
    db.refresh(profile)
    return ProfileOut.model_validate(profile)
```

### 4.2  SQLite :memory: gives each connection its own database

- **What it was:** Every test failed with 'no such table: profiles' even though the fixture had just run create_all().
- **What it affected:** Backend pytest suite against SQLite in-memory.
- **Root cause:** SQLAlchemy's default QueuePool opens a new connection per session. With sqlite:///:memory: each new connection gets its own private database, so tables created via the fixture's connection are invisible to subsequent sessions.
- **Fix:** Use StaticPool so all sessions share the single connection (and therefore the single in-memory database):

```
from sqlalchemy.pool import StaticPool

engine = create_engine(
    "sqlite:///:memory:",
    connect_args={"check_same_thread": False},
    poolclass=StaticPool,
)
```

### 4.3  postgresql.UUID type doesn't play nicely on SQLite

- **What it was:** Tests against SQLite in-memory hit type-handling errors on UUID columns.
- **What it affected:** All test runs after switching to in-memory SQLite for isolation.
- **Root cause:** Models declared columns with 'from sqlalchemy.dialects.postgresql import UUID' — Postgres-specific. SQLite's fallback behavior here is fragile.
- **Fix:** Switch to SQLAlchemy 2.0's dialect-agnostic Uuid type. Compiles to UUID on Postgres (same DDL as before) and to CHAR(32) on SQLite. The Phase 1 Alembic migration is unchanged because it explicitly declares postgresql.UUID; new migrations will use the generic type.

```
# Before:
from sqlalchemy.dialects.postgresql import UUID
id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)

# After:
from sqlalchemy import Uuid
id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)
```

### 4.4  Supabase session restore race on cold start

- **What it was:** After signing in, force-stopping the app, and reopening, the user was sent back to the SignIn screen even though the saved session was present in encrypted storage.
- **What it affected:** Every cold start where the app needed to read the session before issuing an authenticated request.
- **Root cause:** supabase-kt loads persisted sessions from storage asynchronously. When StartupViewModel.resolve() runs immediately on app launch, auth.currentSessionOrNull() returns null because the load hasn't completed yet — even though a valid session is sitting in EncryptedSharedPreferences and will be available a few ms later.
- **Fix:** Call auth.awaitInitialization() before checking the session. Do it in two places: the AuthRepository wrapper, and the AuthInterceptor (which is on a different thread).

```
// AuthRepository.kt
suspend fun currentSession(): UserSession? {
    auth.awaitInitialization()
    return auth.currentSessionOrNull()
}

// AuthInterceptor.kt — inside runBlocking
val token = runBlocking {
    auth.awaitInitialization()
    auth.currentSessionOrNull()?.accessToken
}
```

### 4.5  Multi-user isolation broken — local cache leaks

- **What it was:** Signing in as a different email on the same device still showed the previous user's business on the dashboard.
- **What it affected:** Any device where a second user signs in after the first signs out. Pilot installs where the device passes between people. Also: any sign-out flow that's interrupted before fully completing.
- **Root cause:** On sign-out we cleared the Supabase session but left the local Room cache (LocalProfile, LocalBusiness, selected_business_id metadata) intact. When the next user signed in and GET /businesses returned [], our BusinessRepository.refreshFromRemote() fell back to returning the cached local rows — which still belonged to the previous user. The state machine then auto-selected the lone cached business and routed to Dashboard.
- **Fix:** Two layers. Layer 1: explicitly wipe local data on sign-out, before signing out of Supabase. Layer 2: defensive check in StartupViewModel.resolve() — if the cached profile's id doesn't match the current session's user id, wipe local before routing. Layer 2 catches the case where Layer 1 was bypassed (badly implemented sign-out, app update, etc.).

```
// DashboardViewModel.signOut()
businessRepository.clearAllLocal()  // businesses + selected_business_id
profileRepository.clearLocal()      // local profile
authRepository.signOut()            // last — Supabase session

// StartupViewModel.resolve(), after loading session
val cached = profileRepository.getCurrentProfile()
if (cached != null && session.user?.id != null && cached.id != session.user.id) {
    businessRepository.clearAllLocal()
    profileRepository.clearLocal()
}
```

### 4.6  Friendly OTP error mapping lied about failure cause

- **What it was:** When the user entered a wrong code, the UI showed 'Code expired. Tap Use a different email and try again.' even though the code was simply wrong.
- **What it affected:** UX trust on the SignIn screen.
- **Root cause:** Supabase returns 'Token has expired or is invalid' for both expired AND wrong codes. The client's when-branch checked for 'expired' first, so any failure containing the word 'expired' was misclassified as expiry.
- **Fix:** Collapse to a single accurate message that covers both cases. Log the raw exception for debugging instead of trying to parse it for user-facing text:

```
.onFailure { t ->
    Log.e(TAG, "verifyOtp: failed", t)
    _state.update {
        it.copy(
            loading = false,
            error = "Incorrect code. Check the email and try again, " +
                "or tap 'Use a different email' to get a new one.",
        )
    }
}
```

---

_Generated from `docs/_generate_bugs_doc.py` — edit `CATEGORIES` there and re-run to refresh both this file and the .docx._
