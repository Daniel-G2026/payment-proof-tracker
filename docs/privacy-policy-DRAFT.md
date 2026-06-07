# Privacy Policy — DRAFT

> **This is a draft, not legal advice.** Fill in every `[BRACKETED]` placeholder,
> have it reviewed (ideally by someone familiar with Nigeria's NDPR and, if you
> have non-Nigerian users, GDPR), then **host it at a public URL** — Google Play
> requires a reachable privacy-policy link, and you must paste the same URL into
> the Play Console listing. Keep this draft in sync with what the app actually does.

---

**Privacy Policy for [APP NAME]**

**Last updated:** [DATE]

[APP NAME] ("the app", "we", "us") is provided by [DEVELOPER / BUSINESS LEGAL NAME],
[ADDRESS / CITY, COUNTRY]. This policy explains what we collect, why, and your
choices. Contact us at **[CONTACT EMAIL]**.

## What the app is for
[APP NAME] helps small businesses record money in and money out, keep the proof
(payment screenshots), track who paid and who they paid, and follow installment
balances on projects. It is not a bank, payment processor, or accounting service.

## Information we collect
We collect only what's needed to run the app:

- **Account information** — your name and email address, handled through our
  authentication provider (Supabase) when you sign in.
- **Business details** — your business name and currency.
- **Financial records you enter** — amounts, dates, whether money came in or went
  out, reference numbers, categories, and any notes.
- **People you record** — names, and optionally phone numbers and notes, for the
  people who pay you or whom you pay ("counterparties" / buyers).
- **Projects and installment data** — project and house/unit names, assigned
  buyers, and the installment schedules you set up.
- **Proof images** — the payment screenshots you choose to attach. These may
  themselves contain financial or personal details, depending on what you upload.
- **Diagnostic and usage data** — if enabled: crash reports (via Sentry) and
  anonymous product-usage events (via PostHog), to fix bugs and improve the app.

We do **not** collect your contacts, location, or advertising identifiers, and we
do **not** sell your data.

## How your information is stored
Your records are saved **on your device** (offline-first) and, when you're online,
**backed up to our servers** (a database plus secure file storage for proof
images) so you can restore them on a new device. Data in transit is encrypted
over HTTPS.

## Automated processing of proof images (AI)
To save you typing, when you attach a payment screenshot we may send that image to
our AI provider (**Anthropic / Claude**) to suggest a draft of the amount, date,
and other fields. **You always confirm or edit drafts before a record is saved.**
The raw AI response is retained for up to **30 days** and then automatically
deleted; the cleaned fields you keep remain part of your record.

## Third parties we share data with
We share data only with the service providers that make the app work, under their
own terms:

- **Supabase** — sign-in / account authentication.
- **[HOSTING PROVIDER]** — server and database hosting.
- **[OBJECT STORAGE PROVIDER]** — storage of proof images.
- **Anthropic** — AI extraction of attached screenshots (see above).
- **Sentry / PostHog** — crash and usage diagnostics, if enabled.

We do not share your data with advertisers or data brokers.

## Data retention
We keep your records for as long as your account is active. Raw AI extraction data
is deleted after ~30 days. When you delete a record or your account, we delete the
associated data, including proof images, within [RETENTION WINDOW, e.g. 30 days].

## Your rights
You can access, correct, export, or delete your data. To request **account and
data deletion**, [DESCRIBE: e.g. use the in-app option / email us at [CONTACT
EMAIL]] and we will action it within [TIMEFRAME]. Depending on where you live, you
may have additional rights under the Nigeria Data Protection Regulation (NDPR) or
the GDPR.

## Security
We use industry-standard measures (encrypted transport, access controls, signed
time-limited URLs for proof images). No system is perfectly secure, but we take
reasonable steps to protect your information.

## Children
[APP NAME] is intended for business use by adults and is not directed at children
under [AGE].

## Changes to this policy
We may update this policy; we'll post the new date above and, for significant
changes, notify you in the app.

## Contact
Questions or requests: **[CONTACT EMAIL]**, [DEVELOPER / BUSINESS LEGAL NAME].
