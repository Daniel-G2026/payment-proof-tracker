# Privacy Policy — ProofLedger

**Last updated: 11 June 2026**

ProofLedger ("the app", "we", "us") is provided by **Daniel Anazodo**, based in
Canada. This policy explains what the app collects, why, and the choices you
have. Questions or requests: **proofledgerapp@yahoo.com**.

## What ProofLedger is for

ProofLedger helps small businesses record money in and money out, keep the proof
(payment screenshots), track who paid and who they paid, and follow installment
balances on projects. It is **not** a bank, payment processor, or accounting
service, and it does **not** move money.

## Information we collect

We collect only what's needed to run the app:

- **Account information** — your name and email address, handled through our
  authentication provider (Supabase) when you sign in.
- **Business details** — your business name and currency.
- **Financial records you enter** — amounts, dates, whether money came in or
  went out, reference numbers, categories, and notes.
- **People you record** — names, and optionally phone numbers and notes, for the
  people who pay you or whom you pay.
- **Projects and installment data** — project and house/unit names, assigned
  buyers, and the installment schedules you set up.
- **Proof images** — the payment screenshots you choose to attach. These may
  themselves contain financial or personal details, depending on what you upload.
- **Limited diagnostic data** — we collect anonymous crash logs (through Sentry)
  and product usage events such as which screens and actions you use (through
  PostHog) to fix bugs and improve the app. This data is not used to identify you
  and is not sold.

We do **not** collect your contacts, precise location, or advertising
identifiers, and we do **not** sell your data.

## How your information is stored

Your records are saved **on your device** (offline-first) and, when you're
online, **backed up to our servers** so you can restore them on a new device.
Application data is held in a managed PostgreSQL database, and proof images are
stored in object storage (Cloudflare R2). Data in transit is encrypted over
HTTPS, and proof images are served through short-lived, signed links.

## Automated processing of proof images (AI)

To save you typing, when you attach a payment screenshot we may send that image
to our AI provider (**Anthropic**) to suggest a draft of the amount, date, and
other fields. **You always confirm or edit drafts before a record is saved.**
The raw AI response is retained for up to **30 days** and then automatically
deleted; the cleaned fields you keep remain part of your record.

## Service providers we share data with

We share data only with the service providers that make the app work, each under
their own terms and privacy policies:

- **Supabase** — sign-in / account authentication.
- **Render** — server and database hosting.
- **Cloudflare R2** — storage of proof images.
- **Anthropic** — AI extraction of attached screenshots (see above).
- **PostHog** — product analytics (anonymous usage events).
- **Sentry** — crash reporting (diagnostic crash logs).

We do not share your data with advertisers or data brokers.

## Data retention

We keep your records for as long as your account is active. Raw AI extraction
data is deleted after about 30 days. When you delete a record or your account,
we delete the associated data — including proof images — within 30 days.

## Your rights

You can access, correct, export, or delete your data at any time. To request
**account and data deletion**, email **proofledgerapp@yahoo.com** and we will
action it within 30 days. Depending on where you live, you may have additional
rights under Nigeria's Data Protection Regulation (NDPR), Canada's PIPEDA, or
other applicable laws.

## Security

We use reasonable measures including encrypted transport (HTTPS), access
controls, and time-limited signed URLs for proof images. No system is perfectly
secure, but we take reasonable steps to protect your information.

## Children

ProofLedger is intended for business use by adults and is not directed at
children under 18.

## Changes to this policy

We may update this policy; we'll post the new date above and, for significant
changes, notify you in the app.

## Contact

Daniel Anazodo — **proofledgerapp@yahoo.com**
