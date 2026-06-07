# Google Play Data Safety — answer mapping (DRAFT)

How to fill the Play Console **Data safety** form for [APP NAME], based on what
the app actually does. Verify each line against the current build before
submitting — Google cross-checks declarations against observed behavior, and a
mismatch can get the app rejected or pulled.

## Global answers
- **Does your app collect or share any of the required user data types?** → **Yes**
- **Is all collected data encrypted in transit?** → **Yes** (HTTPS).
- **Do you provide a way for users to request that their data be deleted?** →
  **Yes** — [confirm the in-app or email deletion path described in the privacy policy].

## Data types — collected / shared / purpose

| Data type | Collected? | Shared? | Purpose | Optional/Required |
|---|---|---|---|---|
| **Name** | Yes | No | App functionality, Account management | Required |
| **Email address** | Yes | No | App functionality, Account management | Required |
| **Phone number** (of counterparties you add) | Yes | No | App functionality | Optional |
| **User payment info / Purchase history** (the money in/out records you create) | Yes | No | App functionality | Required |
| **Other financial info** (amounts, references, balances) | Yes | No | App functionality | Required |
| **Photos** (proof screenshots you attach) | Yes | Yes* | App functionality | Optional |
| **App interactions** (usage events, if PostHog enabled) | Yes | Yes (PostHog) | Analytics | Optional |
| **Crash logs / Diagnostics** (if Sentry enabled) | Yes | Yes (Sentry) | Crash prevention / diagnostics | Optional |

\* **Photos "shared":** proof images are sent to **Anthropic** for AI extraction.
Google defines "shared" as transfer to a third party. Because the image is
processed by a third-party AI service, declare Photos as **shared** for *App
functionality* unless you turn extraction off. (If you classify Anthropic as a
"service provider" processing on your behalf, Google's rules may let you treat it
as processing rather than sharing — confirm against the current Play policy and be
consistent with your privacy policy wording.)

## Things to double-check before submitting
- [ ] If you ship with **PostHog/Sentry disabled** (no keys), drop the Analytics /
      Diagnostics rows — declare only what's actually active in the release build.
- [ ] Account-data **deletion** path is real and reachable (in-app or documented).
- [ ] Every "Yes" here is reflected in the **privacy policy** (and vice-versa).
- [ ] No location, contacts, or advertising IDs are collected — keep those "No".
- [ ] Re-confirm after any future change that adds a new SDK or data flow.
