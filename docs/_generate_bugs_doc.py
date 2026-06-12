"""Generate android-bugs-reference.docx from a structured bug list.

This script is a one-off generator. It lives in docs/ so the source is
inspectable, but the .docx it produces is what teammates actually read.
Re-run after editing BUGS to regenerate.
"""

from __future__ import annotations

import os
from dataclasses import dataclass

# python-docx is only needed for the .docx output. Import Pt/RGBColor (used by
# the module-level docx helpers) defensively so markdown generation still works
# even if python-docx isn't installed in the active interpreter. Document and the
# enum are imported lazily inside build_document().
try:
    from docx.shared import Pt, RGBColor
except ImportError:  # pragma: no cover - markdown path doesn't need docx
    Pt = RGBColor = None  # type: ignore


@dataclass
class Bug:
    title: str
    what: str
    affected: str
    root_cause: str
    fix: str
    code: str | None = None  # optional code/config snippet


@dataclass
class Category:
    name: str
    blurb: str
    bugs: list[Bug]


CATEGORIES: list[Category] = [
    Category(
        name="Environment & Tooling",
        blurb=(
            "Issues with the host machine, JDK, Gradle, Kotlin daemon, and "
            "annotation processors. The majority of pain in Phase 1+2 came "
            "from here, not from product code. Most one-time fixes."
        ),
        bugs=[
            Bug(
                title="JAVA_HOME not set",
                what=(
                    "Running ./gradlew.bat from PowerShell failed with "
                    "'ERROR: JAVA_HOME is not set and no java command could be "
                    "found in your PATH.'"
                ),
                affected=(
                    "Every Gradle operation from a terminal. Android Studio "
                    "itself was unaffected because it ships its own JDK and "
                    "uses it implicitly."
                ),
                root_cause=(
                    "No JDK was installed system-wide. The JDK Android Studio "
                    "uses (JBR 21) lives inside the Android Studio install "
                    "directory and isn't on PATH or JAVA_HOME by default."
                ),
                fix=(
                    "Point JAVA_HOME at the bundled JBR. The exact path depends "
                    "on the Android Studio install; on this machine it was "
                    "'C:\\Program Files\\Android\\Android Studio1\\jbr'. Set "
                    "persistently at user level with PowerShell:"
                ),
                code=(
                    '[Environment]::SetEnvironmentVariable(\n'
                    '    "JAVA_HOME",\n'
                    '    "C:\\Program Files\\Android\\Android Studio1\\jbr",\n'
                    '    "User"\n'
                    ')'
                ),
            ),
            Bug(
                title="VS Code terminal doesn't see new env vars",
                what=(
                    "JAVA_HOME was set persistently at the user level, but "
                    "fresh terminals opened inside VS Code didn't see it. "
                    "Confirmed via 'echo $env:JAVA_HOME' returning empty."
                ),
                affected=(
                    "Every VS Code integrated terminal in an already-running "
                    "VS Code window."
                ),
                root_cause=(
                    "VS Code captures its environment block when the VS Code "
                    "process launches. New integrated terminals inherit that "
                    "captured snapshot, not the current user environment. "
                    "Restarting VS Code would pick it up; opening a new tab "
                    "within an existing window would not."
                ),
                fix=(
                    "Either fully restart VS Code, or (better) declare the "
                    "env var inside VS Code's own settings.json so every "
                    "integrated terminal gets it regardless of the host's env:"
                ),
                code=(
                    '"terminal.integrated.env.windows": {\n'
                    '    "JAVA_HOME": "C:\\\\Program Files\\\\Android\\\\'
                    'Android Studio1\\\\jbr"\n'
                    '}'
                ),
            ),
            Bug(
                title="Gradle wrapper files missing from repo",
                what=(
                    "./gradlew.bat: command not found. The Gradle wrapper "
                    "scripts and bootstrap JAR weren't committed."
                ),
                affected=(
                    "Terminal builds. Android Studio works because it "
                    "regenerates the wrapper when importing a project, but "
                    "CLI users (and CI eventually) have nothing to run."
                ),
                root_cause=(
                    "When scaffolding the Gradle project manually, only the "
                    "build.gradle.kts / settings.gradle.kts / "
                    "gradle-wrapper.properties files were created. The "
                    "wrapper scripts (gradlew, gradlew.bat) and the wrapper "
                    "JAR (gradle/wrapper/gradle-wrapper.jar) are normally "
                    "produced by running 'gradle wrapper' once."
                ),
                fix=(
                    "Download the three missing files from the matching "
                    "Gradle release tag on github.com/gradle/gradle. For "
                    "Gradle 8.9:"
                ),
                code=(
                    'curl -o gradlew '
                    'https://raw.githubusercontent.com/gradle/gradle/v8.9.0/'
                    'gradlew\n'
                    'curl -o gradlew.bat '
                    'https://raw.githubusercontent.com/gradle/gradle/v8.9.0/'
                    'gradlew.bat\n'
                    'curl -o gradle/wrapper/gradle-wrapper.jar '
                    'https://raw.githubusercontent.com/gradle/gradle/v8.9.0/'
                    'gradle/wrapper/gradle-wrapper.jar'
                ),
            ),
            Bug(
                title="Kotlin compile daemon crashes silently on JDK 21",
                what=(
                    "Gradle build failed at :app:kspDebugKotlin with "
                    "'The daemon has terminated unexpectedly on startup "
                    "attempt #1 with error code: 0.' The misleading 'error "
                    "code 0' meant the daemon started, reported ready, then "
                    "exited cleanly but unexpectedly."
                ),
                affected="Every Gradle compilation. Build never succeeded.",
                root_cause=(
                    "Known compatibility issue between Kotlin 2.0.x compile "
                    "daemon and JDK 21 on Windows. The daemon spawns, "
                    "reports ready, then exits in a way Gradle interprets as "
                    "a crash."
                ),
                fix=(
                    "Bypass the daemon by running Kotlin compilation in the "
                    "Gradle worker process (slower per-build, no startup "
                    "race). In gradle.properties:"
                ),
                code=(
                    'kotlin.compiler.execution.strategy=in-process\n'
                    'kotlin.daemon.useFallbackStrategy=true'
                ),
            ),
            Bug(
                title="Gradle JVM heap exhausted by KSP + Hilt + Room",
                what=(
                    "Build occasionally OOMed or terminated annotation "
                    "processing partway through with no clean error."
                ),
                affected=(
                    "Builds that ran KSP across multiple annotation "
                    "processors (Room + Hilt) plus large Kotlin libraries "
                    "(supabase-kt, Ktor)."
                ),
                root_cause=(
                    "Default Gradle JVM heap is 2 GB. The combined memory "
                    "demand of annotation processing on a non-trivial "
                    "Compose + Hilt + Room + Supabase project pushes past "
                    "that."
                ),
                fix="Raise heap and Metaspace in gradle.properties:",
                code=(
                    'org.gradle.jvmargs=-Xmx4096m -XX:MaxMetaspaceSize=512m '
                    '-Dfile.encoding=UTF-8'
                ),
            ),
            Bug(
                title="Room exportSchema warning escalates KSP failure",
                what=(
                    "KSP printed a warning about Room not having a schema "
                    "export directory. In some KSP/Room version combos this "
                    "appeared alongside a fatal KSP error."
                ),
                affected="KSP processing of the @Database class.",
                root_cause=(
                    "@Database(exportSchema = true) requires a "
                    "room.schemaLocation annotation processor argument or "
                    "the androidx.room Gradle plugin. Without either, Room "
                    "warns; under some combinations the warning escalates."
                ),
                fix=(
                    "For Phase 1+2 we don't consume the schema files yet. "
                    "Set exportSchema = false on AppDatabase. When you start "
                    "using migrations seriously (Phase 10+), configure the "
                    "Room Gradle plugin properly."
                ),
                code=(
                    '@Database(\n'
                    '    entities = [...],\n'
                    '    version = 2,\n'
                    '    exportSchema = false,  // re-enable + configure '
                    'plugin in Phase 10+\n'
                    ')'
                ),
            ),
        ],
    ),
    Category(
        name="Library & API Mismatch",
        blurb=(
            "Cases where the code we wrote targeted one API but Gradle "
            "resolved a different one. Mostly caused by major-version "
            "renames (supabase-kt v2 -> v3, Material3 churn) and by "
            "assumptions that didn't match the actual library version on "
            "disk."
        ),
        bugs=[
            Bug(
                title="supabase-kt artifact renamed between v2 and v3",
                what=(
                    "Gradle sync error: 'Failed to resolve: "
                    "io.github.jan-tennert.supabase:auth-kt'."
                ),
                affected=(
                    "Entire Android build. Couldn't resolve Supabase auth "
                    "library."
                ),
                root_cause=(
                    "supabase-kt was renamed across major versions. In v2.x "
                    "the auth module is 'gotrue-kt' and the package is "
                    "'io.github.jan.supabase.gotrue.*'. In v3.x they were "
                    "renamed to 'auth-kt' and 'io.github.jan.supabase.auth.*'. "
                    "I'd written v3 code (auth-kt + .auth.* imports) but "
                    "pinned to v2.6.0 in the version catalog, so the v3 "
                    "artifact name didn't exist at that version."
                ),
                fix=(
                    "Bump to a v3 release; Ktor must follow because v3 "
                    "supabase-kt requires Ktor 3. In libs.versions.toml:"
                ),
                code=(
                    'supabase = "3.0.3"   # was 2.6.0\n'
                    'ktor     = "3.0.2"   # was 2.3.12'
                ),
            ),
            Bug(
                title="Compose Material3 ExposedDropdownMenu can't be qualified",
                what=(
                    "Compile error: Unresolved reference "
                    "'ExposedDropdownMenu' on a call written as "
                    "androidx.compose.material3.ExposedDropdownMenu(...)."
                ),
                affected="CreateBusinessScreen.kt — the business-type picker.",
                root_cause=(
                    "ExposedDropdownMenu is a member of "
                    "ExposedDropdownMenuBoxScope (the lambda receiver inside "
                    "ExposedDropdownMenuBox), not a top-level composable. "
                    "Fully-qualifying it with the package prefix doesn't "
                    "resolve."
                ),
                fix=(
                    "Call it unqualified inside the ExposedDropdownMenuBox "
                    "lambda — it's an extension function on the box scope:"
                ),
                code=(
                    'ExposedDropdownMenuBox(...) {\n'
                    '    OutlinedTextField(\n'
                    '        ...,\n'
                    '        modifier = Modifier.menuAnchor(\n'
                    '            MenuAnchorType.PrimaryNotEditable, '
                    'enabled = true\n'
                    '        ).fillMaxWidth(),\n'
                    '    )\n'
                    '    ExposedDropdownMenu(  // unqualified, scope member\n'
                    '        expanded = typeExpanded,\n'
                    '        onDismissRequest = { typeExpanded = false },\n'
                    '    ) { /* items */ }\n'
                    '}'
                ),
            ),
            Bug(
                title="menuAnchor() no-arg overload deprecated",
                what=(
                    "Compile warning escalating to error in stricter builds: "
                    "menuAnchor() called with no arguments is deprecated."
                ),
                affected="Any OutlinedTextField inside ExposedDropdownMenuBox.",
                root_cause=(
                    "Material3 1.3+ introduced explicit anchor types so the "
                    "dropdown knows whether the anchor is the primary "
                    "editable field, a secondary affordance, etc."
                ),
                fix=(
                    "Pass MenuAnchorType.PrimaryNotEditable for a read-only "
                    "text field that opens the menu, or PrimaryEditable if "
                    "the user types in it:"
                ),
                code=(
                    'modifier = Modifier\n'
                    '    .menuAnchor(MenuAnchorType.PrimaryNotEditable, '
                    'enabled = true)\n'
                    '    .fillMaxWidth()'
                ),
            ),
            Bug(
                title="JWT algorithm mismatch: backend HS256 vs Supabase ES256",
                what=(
                    "Every authenticated backend call returned 401 even "
                    "though the JWT secret was correct."
                ),
                affected="All protected backend routes from the Android app.",
                root_cause=(
                    "Supabase changed the default JWT signing algorithm for "
                    "new projects from HS256 (symmetric, verified with the "
                    "project's JWT secret) to ES256 (asymmetric, verified "
                    "with the project's public key from the JWKS endpoint). "
                    "The backend only knew how to verify HS256, so it "
                    "rejected every ES256-signed token regardless of how "
                    "correct the secret was."
                ),
                fix=(
                    "Accept both algorithms in the verification step. For "
                    "HS256 use the project JWT secret; for ES256 fetch the "
                    "public key from <SUPABASE_URL>/auth/v1/.well-known/"
                    "jwks.json via PyJWT's PyJWKClient. Requires the crypto "
                    "extra on PyJWT (which brings in 'cryptography'):"
                ),
                code=(
                    '# pyproject.toml\n'
                    'dependencies = [\n'
                    '    ...,\n'
                    '    "pyjwt[crypto]>=2.9",\n'
                    ']\n\n'
                    '# app/core/auth.py — branch on JWT header alg\n'
                    'header = jwt.get_unverified_header(token)\n'
                    'if header["alg"] == "HS256":\n'
                    '    claims = jwt.decode(token, settings.'
                    'supabase_jwt_secret, algorithms=["HS256"], '
                    'audience="authenticated")\n'
                    'elif header["alg"] == "ES256":\n'
                    '    key = _jwks_client().get_signing_key_from_jwt(token).'
                    'key\n'
                    '    claims = jwt.decode(token, key, algorithms=["ES256"], '
                    'audience="authenticated")'
                ),
            ),
        ],
    ),
    Category(
        name="Configuration",
        blurb=(
            "Bugs that lived in service settings, manifest declarations, or "
            "external dashboards rather than in code. These usually present "
            "as 'the code is right but nothing works.'"
        ),
        bugs=[
            Bug(
                title="Supabase OTP email template sends magic link, not code",
                what=(
                    "After calling auth.signInWith(OTP) the user received "
                    "an email containing a clickable 'Sign in' link instead "
                    "of a 6-digit code. Tapping the link redirected to "
                    "http://localhost:3000 (empty page)."
                ),
                affected=(
                    "Sign-in flow entirely. No way for the user to verify "
                    "without entering a code, and the link path required "
                    "deep linking we deliberately hadn't set up."
                ),
                root_cause=(
                    "The Supabase 'Magic Link' email template is shared "
                    "between magic-link and OTP flows. Default template body "
                    "uses {{ .ConfirmationURL }} (a link). To get a "
                    "code-based UX the template must use {{ .Token }} "
                    "instead."
                ),
                fix=(
                    "In Supabase Studio: Authentication -> Email Templates "
                    "-> Magic Link. Replace Subject and Body to use "
                    "{{ .Token }}:"
                ),
                code=(
                    '<!-- Body -->\n'
                    '<h2>Your sign-in code</h2>\n'
                    '<p>Enter this 6-digit code in the app:</p>\n'
                    '<p style="font-size: 32px; font-weight: bold;">'
                    '{{ .Token }}</p>'
                ),
            ),
            Bug(
                title="OTP length hardcoded to 6 but Supabase default is 8",
                what=(
                    "User received an 8-digit code in their email but the "
                    "app's code input truncated it to 6 and the Verify "
                    "button stayed disabled (gated on length == 6)."
                ),
                affected=(
                    "Verify step of sign-in. Could not complete auth on "
                    "Supabase projects defaulting to 8-digit codes."
                ),
                root_cause=(
                    "Supabase's OTP length is project-configurable; default "
                    "for newer projects is 8. The client app assumed 6."
                ),
                fix=(
                    "Either change the Supabase project's OTP length to 6 in "
                    "Authentication -> Providers -> Email, OR widen the "
                    "client to accept any length the user types and let the "
                    "backend reject invalid lengths. Practically the cleanest "
                    "is to align both on 6:"
                ),
                code=(
                    'fun onCodeChange(value: String) = _state.update {\n'
                    '    it.copy(code = value.filter(Char::isDigit).take(6))\n'
                    '}\n'
                    '// ... and on the button:\n'
                    'enabled = state.code.length == 6'
                ),
            ),
            Bug(
                title="Android 9+ blocks cleartext HTTP by default",
                what=(
                    "Logcat: 'java.net.UnknownServiceException: CLEARTEXT "
                    "communication to 10.0.2.2 not permitted by network "
                    "security policy.' Backend never received any request "
                    "from the app even though emulator Chrome could reach "
                    "http://10.0.2.2:8000/health."
                ),
                affected=(
                    "Every backend API call from the app — silently failed "
                    "before leaving OkHttp."
                ),
                root_cause=(
                    "Starting with Android 9 (API 28), apps may not make "
                    "plain-HTTP connections unless they explicitly opt in. "
                    "Our local FastAPI is on http (no TLS), so the OS "
                    "blocked every request to 10.0.2.2."
                ),
                fix=(
                    "Create a network security config that whitelists "
                    "cleartext for the emulator loopback host, and reference "
                    "it from the manifest. Production builds talking to "
                    "https:// don't need this exemption."
                ),
                code=(
                    '<!-- res/xml/network_security_config.xml -->\n'
                    '<network-security-config>\n'
                    '    <domain-config cleartextTrafficPermitted="true">\n'
                    '        <domain includeSubdomains="true">10.0.2.2'
                    '</domain>\n'
                    '        <domain includeSubdomains="true">localhost'
                    '</domain>\n'
                    '        <domain includeSubdomains="true">127.0.0.1'
                    '</domain>\n'
                    '    </domain-config>\n'
                    '</network-security-config>\n\n'
                    '<!-- AndroidManifest.xml, on <application> -->\n'
                    'android:networkSecurityConfig='
                    '"@xml/network_security_config"'
                ),
            ),
        ],
    ),
    Category(
        name="Code Logic",
        blurb=(
            "Bugs in our own application code. These are the ones most "
            "worth internalizing because they recur in any Android app "
            "talking to an async auth provider and a local cache."
        ),
        bugs=[
            Bug(
                title="SQLAlchemy 'instance not persistent' in PATCH /me",
                what=(
                    "pytest error: 'Instance Profile at ... is not persistent "
                    "within this Session.' Production code worked fine "
                    "because in real requests, get_current_user and the "
                    "route share the same DB session — but the test "
                    "override returned a Profile from a different session."
                ),
                affected=(
                    "Test test_get_me_and_patch_me. Latent risk in any code "
                    "path where get_current_user and the route end up on "
                    "different sessions."
                ),
                root_cause=(
                    "The PATCH /me route mutated current_user directly and "
                    "called db.commit() on a different session — illegal "
                    "operation on a detached instance."
                ),
                fix=(
                    "Re-fetch the user in the route's session before "
                    "mutating. This also makes the route robust to any "
                    "future change in how get_current_user resolves "
                    "sessions:"
                ),
                code=(
                    '@router.patch("/me")\n'
                    'def update_me(\n'
                    '    payload: ProfileUpdate,\n'
                    '    current_user: CurrentUser,\n'
                    '    db: Session = Depends(get_db),\n'
                    ') -> ProfileOut:\n'
                    '    profile = db.get(Profile, current_user.id)\n'
                    '    if profile is None:\n'
                    '        raise HTTPException(status_code=404)\n'
                    '    for field, value in payload.model_dump('
                    'exclude_unset=True).items():\n'
                    '        setattr(profile, field, value)\n'
                    '    db.commit()\n'
                    '    db.refresh(profile)\n'
                    '    return ProfileOut.model_validate(profile)'
                ),
            ),
            Bug(
                title="SQLite :memory: gives each connection its own database",
                what=(
                    "Every test failed with 'no such table: profiles' even "
                    "though the fixture had just run create_all()."
                ),
                affected="Backend pytest suite against SQLite in-memory.",
                root_cause=(
                    "SQLAlchemy's default QueuePool opens a new connection "
                    "per session. With sqlite:///:memory: each new "
                    "connection gets its own private database, so tables "
                    "created via the fixture's connection are invisible to "
                    "subsequent sessions."
                ),
                fix=(
                    "Use StaticPool so all sessions share the single "
                    "connection (and therefore the single in-memory "
                    "database):"
                ),
                code=(
                    'from sqlalchemy.pool import StaticPool\n\n'
                    'engine = create_engine(\n'
                    '    "sqlite:///:memory:",\n'
                    '    connect_args={"check_same_thread": False},\n'
                    '    poolclass=StaticPool,\n'
                    ')'
                ),
            ),
            Bug(
                title="postgresql.UUID type doesn't play nicely on SQLite",
                what=(
                    "Tests against SQLite in-memory hit type-handling errors "
                    "on UUID columns."
                ),
                affected=(
                    "All test runs after switching to in-memory SQLite for "
                    "isolation."
                ),
                root_cause=(
                    "Models declared columns with 'from sqlalchemy.dialects."
                    "postgresql import UUID' — Postgres-specific. SQLite's "
                    "fallback behavior here is fragile."
                ),
                fix=(
                    "Switch to SQLAlchemy 2.0's dialect-agnostic Uuid type. "
                    "Compiles to UUID on Postgres (same DDL as before) and "
                    "to CHAR(32) on SQLite. The Phase 1 Alembic migration is "
                    "unchanged because it explicitly declares "
                    "postgresql.UUID; new migrations will use the generic "
                    "type."
                ),
                code=(
                    '# Before:\n'
                    'from sqlalchemy.dialects.postgresql import UUID\n'
                    'id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), '
                    'primary_key=True)\n\n'
                    '# After:\n'
                    'from sqlalchemy import Uuid\n'
                    'id: Mapped[uuid.UUID] = mapped_column(Uuid, '
                    'primary_key=True, default=uuid.uuid4)'
                ),
            ),
            Bug(
                title="Supabase session restore race on cold start",
                what=(
                    "After signing in, force-stopping the app, and "
                    "reopening, the user was sent back to the SignIn screen "
                    "even though the saved session was present in encrypted "
                    "storage."
                ),
                affected=(
                    "Every cold start where the app needed to read the "
                    "session before issuing an authenticated request."
                ),
                root_cause=(
                    "supabase-kt loads persisted sessions from storage "
                    "asynchronously. When StartupViewModel.resolve() runs "
                    "immediately on app launch, "
                    "auth.currentSessionOrNull() returns null because the "
                    "load hasn't completed yet — even though a valid "
                    "session is sitting in EncryptedSharedPreferences and "
                    "will be available a few ms later."
                ),
                fix=(
                    "Call auth.awaitInitialization() before checking the "
                    "session. Do it in two places: the AuthRepository "
                    "wrapper, and the AuthInterceptor (which is on a "
                    "different thread)."
                ),
                code=(
                    '// AuthRepository.kt\n'
                    'suspend fun currentSession(): UserSession? {\n'
                    '    auth.awaitInitialization()\n'
                    '    return auth.currentSessionOrNull()\n'
                    '}\n\n'
                    '// AuthInterceptor.kt — inside runBlocking\n'
                    'val token = runBlocking {\n'
                    '    auth.awaitInitialization()\n'
                    '    auth.currentSessionOrNull()?.accessToken\n'
                    '}'
                ),
            ),
            Bug(
                title="Multi-user isolation broken — local cache leaks",
                what=(
                    "Signing in as a different email on the same device "
                    "still showed the previous user's business on the "
                    "dashboard."
                ),
                affected=(
                    "Any device where a second user signs in after the "
                    "first signs out. Pilot installs where the device "
                    "passes between people. Also: any sign-out flow that's "
                    "interrupted before fully completing."
                ),
                root_cause=(
                    "On sign-out we cleared the Supabase session but left "
                    "the local Room cache (LocalProfile, LocalBusiness, "
                    "selected_business_id metadata) intact. When the next "
                    "user signed in and GET /businesses returned [], our "
                    "BusinessRepository.refreshFromRemote() fell back to "
                    "returning the cached local rows — which still belonged "
                    "to the previous user. The state machine then "
                    "auto-selected the lone cached business and routed to "
                    "Dashboard."
                ),
                fix=(
                    "Two layers. Layer 1: explicitly wipe local data on "
                    "sign-out, before signing out of Supabase. Layer 2: "
                    "defensive check in StartupViewModel.resolve() — if "
                    "the cached profile's id doesn't match the current "
                    "session's user id, wipe local before routing. Layer 2 "
                    "catches the case where Layer 1 was bypassed (badly "
                    "implemented sign-out, app update, etc.)."
                ),
                code=(
                    '// DashboardViewModel.signOut()\n'
                    'businessRepository.clearAllLocal()  // businesses + '
                    'selected_business_id\n'
                    'profileRepository.clearLocal()      // local profile\n'
                    'authRepository.signOut()            // last — Supabase '
                    'session\n\n'
                    '// StartupViewModel.resolve(), after loading session\n'
                    'val cached = profileRepository.getCurrentProfile()\n'
                    'if (cached != null && session.user?.id != null '
                    '&& cached.id != session.user.id) {\n'
                    '    businessRepository.clearAllLocal()\n'
                    '    profileRepository.clearLocal()\n'
                    '}'
                ),
            ),
            Bug(
                title="Friendly OTP error mapping lied about failure cause",
                what=(
                    "When the user entered a wrong code, the UI showed "
                    "'Code expired. Tap Use a different email and try "
                    "again.' even though the code was simply wrong."
                ),
                affected="UX trust on the SignIn screen.",
                root_cause=(
                    "Supabase returns 'Token has expired or is invalid' for "
                    "both expired AND wrong codes. The client's when-branch "
                    "checked for 'expired' first, so any failure containing "
                    "the word 'expired' was misclassified as expiry."
                ),
                fix=(
                    "Collapse to a single accurate message that covers both "
                    "cases. Log the raw exception for debugging instead of "
                    "trying to parse it for user-facing text:"
                ),
                code=(
                    '.onFailure { t ->\n'
                    '    Log.e(TAG, "verifyOtp: failed", t)\n'
                    '    _state.update {\n'
                    '        it.copy(\n'
                    '            loading = false,\n'
                    '            error = "Incorrect code. Check the email '
                    'and try again, " +\n'
                    '                "or tap \'Use a different email\' to '
                    'get a new one.",\n'
                    '        )\n'
                    '    }\n'
                    '}'
                ),
            ),
        ],
    ),
]


# ---------- doc generation ----------


def add_styled_paragraph(doc, text, bold=False, size_pt=11, color=None):
    p = doc.add_paragraph()
    run = p.add_run(text)
    run.bold = bold
    run.font.size = Pt(size_pt)
    if color:
        run.font.color.rgb = color
    return p


def add_label_and_text(doc, label, text):
    p = doc.add_paragraph()
    label_run = p.add_run(f"{label}  ")
    label_run.bold = True
    label_run.font.size = Pt(11)
    body_run = p.add_run(text)
    body_run.font.size = Pt(11)
    return p


def add_code_block(doc, code):
    p = doc.add_paragraph()
    p.paragraph_format.left_indent = Pt(18)
    p.paragraph_format.space_after = Pt(12)
    for line in code.splitlines():
        if p.runs:
            p.add_run().add_break()
        run = p.add_run(line)
        run.font.name = "Consolas"
        run.font.size = Pt(9.5)
        run.font.color.rgb = RGBColor(0x1F, 0x2A, 0x3A)
    return p


def build_document(out_path: str) -> None:
    from docx import Document
    from docx.enum.text import WD_ALIGN_PARAGRAPH

    doc = Document()

    # set base font to Arial-ish
    styles = doc.styles
    normal = styles["Normal"]
    normal.font.name = "Arial"
    normal.font.size = Pt(11)

    # ---- title page ----
    title = doc.add_paragraph()
    title.alignment = WD_ALIGN_PARAGRAPH.LEFT
    title_run = title.add_run("Android Development — Bug Reference")
    title_run.bold = True
    title_run.font.size = Pt(22)

    subtitle = doc.add_paragraph()
    sub_run = subtitle.add_run(
        "Phases 1 & 2 of the Payment Proof Tracker (ProofLedger) build"
    )
    sub_run.italic = True
    sub_run.font.size = Pt(12)
    sub_run.font.color.rgb = RGBColor(0x55, 0x55, 0x55)

    add_styled_paragraph(
        doc,
        "Every bug we hit while scaffolding the FastAPI backend, the "
        "Kotlin + Jetpack Compose Android client, and the Supabase auth "
        "integration. Use this as a checklist when starting the next "
        "Android project — most of these are recurring traps in the "
        "stack rather than mistakes specific to this codebase.",
        size_pt=11,
    )

    add_styled_paragraph(
        doc,
        "Format for each bug: What it was → What it affected → Root cause "
        "→ Fix (with code where relevant).",
        size_pt=10,
        color=RGBColor(0x55, 0x55, 0x55),
    )

    doc.add_paragraph()  # spacer

    # ---- categories ----
    for cat_idx, category in enumerate(CATEGORIES, start=1):
        h = doc.add_heading(level=1)
        h_run = h.add_run(f"{cat_idx}. {category.name}")
        h_run.font.color.rgb = RGBColor(0x1A, 0x1A, 0x1A)

        add_styled_paragraph(
            doc, category.blurb, size_pt=10,
            color=RGBColor(0x55, 0x55, 0x55),
        )

        for bug_idx, bug in enumerate(category.bugs, start=1):
            h2 = doc.add_heading(level=2)
            h2_run = h2.add_run(f"{cat_idx}.{bug_idx}  {bug.title}")
            h2_run.font.color.rgb = RGBColor(0x1A, 0x1A, 0x1A)

            add_label_and_text(doc, "What it was:", bug.what)
            add_label_and_text(doc, "What it affected:", bug.affected)
            add_label_and_text(doc, "Root cause:", bug.root_cause)
            add_label_and_text(doc, "Fix:", bug.fix)

            if bug.code:
                add_code_block(doc, bug.code)

    # ---- footer note ----
    doc.add_paragraph()
    add_styled_paragraph(
        doc,
        "Generated from docs/_generate_bugs_doc.py — edit BUGS there and "
        "re-run to refresh.",
        size_pt=9,
        color=RGBColor(0x88, 0x88, 0x88),
    )

    doc.save(out_path)


def build_markdown(out_path: str) -> None:
    """Render the same CATEGORIES data as Markdown (agent-readable, greppable)."""
    lines: list[str] = [
        "# Android Development — Bug Reference",
        "",
        "_Phases 1 & 2 of the Payment Proof Tracker (ProofLedger) build._",
        "",
        (
            "Every bug we hit while scaffolding the FastAPI backend, the Kotlin + "
            "Jetpack Compose Android client, and the Supabase auth integration. Use "
            "this as a checklist when starting the next Android project — most of "
            "these are recurring traps in the stack rather than mistakes specific to "
            "this codebase."
        ),
        "",
        (
            "Format for each bug: **What it was → What it affected → Root cause → "
            "Fix** (with code where relevant)."
        ),
        "",
    ]
    for cat_idx, category in enumerate(CATEGORIES, start=1):
        lines += [f"## {cat_idx}. {category.name}", "", f"_{category.blurb}_", ""]
        for bug_idx, bug in enumerate(category.bugs, start=1):
            lines += [
                f"### {cat_idx}.{bug_idx}  {bug.title}",
                "",
                f"- **What it was:** {bug.what}",
                f"- **What it affected:** {bug.affected}",
                f"- **Root cause:** {bug.root_cause}",
                f"- **Fix:** {bug.fix}",
                "",
            ]
            if bug.code:
                lines += ["```", bug.code, "```", ""]
    lines += [
        "---",
        "",
        (
            "_Generated from `docs/_generate_bugs_doc.py` — edit `CATEGORIES` there "
            "and re-run to refresh both this file and the .docx._"
        ),
        "",
    ]
    with open(out_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))

    md_out = os.path.join(here, "android-bugs-reference.md")
    build_markdown(md_out)
    print(f"Wrote {md_out}")

    docx_out = os.path.join(here, "android-bugs-reference.docx")
    try:
        build_document(docx_out)
        print(f"Wrote {docx_out}")
    except ImportError:
        print("Skipped .docx (python-docx not installed); markdown written.")
    except PermissionError:
        print(f"Skipped {docx_out} (open/locked — close Word and re-run to refresh it).")
