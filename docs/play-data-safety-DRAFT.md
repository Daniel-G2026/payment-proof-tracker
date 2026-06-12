# Google Play Data Safety — answer mapping (ProofLedger)

How to fill the Play Console **Data safety** form for **ProofLedger**, based on
what the app actually does. Verify each line against the current build before
submitting — Google cross-checks declarations against observed behavior, and a
mismatch can get the app rejected or pulled.

## Global answers
- **Does your app collect or share any of the required user data types?** → **Yes**
- **Is all collected user data encrypted in transit?** → **Yes** (HTTPS; proof images served via short-lived signed URLs).
- **Do you provide a way for users to request that their data be deleted?** →
  **Yes** — email-based deletion, documented on the public account-deletion page
  (gist) linked from the listing.

## Data types — collected / shared / ephemeral / purpose

For every row below: **Shared = No** (see note †) and **Ephemeral = No** (see note ‡).

| Data type (Google category) | Collected | Shared | Ephemeral | Purpose | Required/Optional |
|---|---|---|---|---|---|
| Personal info → **Name** | Yes | No | No | App functionality, Account management | Required |
| Personal info → **Email address** | Yes | No | No | App functionality, Account management | Required |
| Personal info → **User IDs** | Yes | No | No | App functionality, Account management | Required |
| Personal info → **Phone number** (of people you record) | Yes | No | No | App functionality | Optional |
| Financial info → **Other financial info** (amounts, references, balances, schedules) | Yes | No | No | App functionality | Required |
| Photos and videos → **Photos** (proof screenshots) | Yes | No | No | App functionality | Optional |
| Files and docs → **Files and docs** (PDF offer letters) | Yes | No | No | App functionality | Optional |
| App activity → **App interactions** (usage events, PostHog) | Yes | No | No | Analytics | Required |
| App info & performance → **Crash logs** (Sentry) | Yes | No | No | App functionality | Required |
| App info & performance → **Diagnostics** (Sentry) | Yes | No | No | App functionality | Required |
| Device or other IDs → **Device or other IDs** (PostHog/Sentry IDs) | Yes | No | No | Analytics, App functionality | Required |

† **"Shared" is No across the board.** Every recipient — **Supabase, Render,
Cloudflare R2, Anthropic, PostHog, Sentry** — is a **service provider**
processing data on the developer's behalf under contract, not for its own
purposes. Google's Data safety definition of "Sharing" explicitly **excludes**
transfers to service providers, so all data is **collected but not shared**
(including proof images sent to **Anthropic** for AI extraction). Keep this
consistent with the privacy policy, which lists the same set as service providers.

‡ **"Ephemeral" is No across the board.** ProofLedger persists everything
(records + proof in Postgres/R2, account data via Supabase, events in PostHog,
crashes in Sentry). "Ephemeral" would only apply to data used in memory and
never written to storage. Even the 30-day raw-AI-extraction blob is *stored*
(short retention), so it is still not ephemeral.

## Not collected (answer No)
- **Location** (approximate & precise)
- **Financial info →** User payment info, Purchase history, Credit score — no card/bank numbers are stored
- **Personal info →** Address, Race/ethnicity, Political/religious beliefs, Sexual orientation, Other info
- **Contacts** — counterparty names/phones are typed in, never read from the device's contacts
- **Messages, Audio, Calendar, Health & fitness, Web browsing**
- **Advertising ID** — No ads SDK; no `AD_ID` permission in the merged release manifest; PostHog/Sentry do not use the Google Advertising ID

### Why "Address" is **No**, even though an offer letter can contain one
Google's test is whether the app **accesses or uses** a data type — not whether
it could appear inside user-uploaded content. The offer-letter scan extracts
only **buyer_name, total, currency, and the installment schedule** (amounts +
dates); it never reads or stores a postal address. An address printed on an
uploaded letter is **incidental content inside a document** — covered by the
**Photos / Files and docs** declaration — not a collected data type. (Same
principle as a notes app not declaring "Address" just because a user could type
one into a note.)

## Before submitting
- [ ] Analytics (PostHog) + crash reporting (Sentry) are **enabled for v1** — keep the Analytics / Diagnostics / Device-IDs rows. They go no-op only in a build shipped without keys; the production release ships with keys.
- [ ] Account/data **deletion** path is real and reachable (email + the public deletion page).
- [ ] Every "Yes" here is reflected in the **privacy policy** (and vice-versa).
- [ ] Re-confirm after any future change that adds a new SDK or data flow.
