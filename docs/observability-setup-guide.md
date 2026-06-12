# Turning On Sentry and PostHog — A Beginner's Guide

This guide is for the person who owns and ships the ProofLedger app (the
"developer" — that's you). It assumes you have **never** built an Android app
before and have **never** used a crash-reporting or analytics tool. Every
technical word is explained the first time it shows up. Take it slowly; there is
nothing here you can break.

By the end you will be able to:

1. Understand what these two optional tools do.
2. Get a key (a secret code) from each one.
3. Put those keys in the right place and rebuild the app.
4. Know exactly what the app measures — and, just as importantly, what it does
   **not** measure.
5. Log in and actually look at the data.

---

## 1. What are these tools, and why would I want them?

The app ships with **two optional helpers** baked in. "Optional" is the key
word: **the app works perfectly without either of them.** Nothing about money
in, money out, proof images, syncing, or any feature your users touch depends on
them. They exist only for *you*, the developer, and **no user ever sees them,
hears about them, or is slowed down by them.** If you do nothing, both helpers
stay completely switched off and the app behaves exactly as it does today.

**Sentry** is a **crash-reporting** tool. A "crash" is when the app hits an
unexpected error and closes itself. Think of Sentry as a smoke alarm wired into
the app: the moment something breaks on a user's phone, Sentry quietly sends you
a note describing *what* broke and *where*, so you can find and fix it — instead
of waiting for an upset user to email you (or worse, silently uninstall). You
read those notes on Sentry's website.

**PostHog** is a **product-analytics** tool. "Analytics" just means *counting
how the app is actually used*. Think of PostHog as a tally counter at the door
of a shop: it doesn't know *who* walked in or what's in their wallet, it just
clicks "+1" each time something happens — the app was opened, a report was
exported, and so on. Over time those tallies tell you which features people
really use and which they ignore, so you can decide what to build or fix next.

Both are free to start (see [Section 7](#7-what-does-this-cost)), and both can be
turned on independently — you can enable one, the other, both, or neither.

---

## 2. Getting your Sentry key (called a "DSN")

First, a definition. A **DSN** ("Data Source Name") is the one secret string
Sentry gives you. Think of it as **an address-plus-password**: it tells the app
*where* to send crash reports and proves the reports are really coming from your
project. It looks something like
`https://abc123@o456.ingest.sentry.io/789`. Treat it like a password — don't
post it publicly.

Steps:

1. Open a web browser and go to **https://sentry.io**.
2. Click **Sign up** and create a free account (email + password, or sign in
   with Google/GitHub). Sentry has a **free tier** that is plenty for a new
   app — you do not need to enter a credit card to start.
3. After signing up, Sentry asks you to **create a project**. A "project" is
   just a labeled bucket where this app's crashes will collect. Give it a name
   like `proofledger`.
4. When it asks **which platform**, choose **Android**. (A "platform" here just
   means "what kind of app is this?" — ProofLedger is an Android phone app.)
5. Sentry now shows you a setup page. Somewhere on it — usually in the code
   snippet or on the project's **Settings → Client Keys (DSN)** page — is the
   **DSN** string. **Copy it.** That single line is the only thing you need from
   Sentry. You can ignore the rest of the setup instructions on that page; the
   app already has the Sentry code built in and just needs the DSN.

Keep that copied DSN handy for [Section 4](#4-where-to-put-the-keys-and-the-rebuild-step).

---

## 3. Getting your PostHog key (the "Project API Key")

Steps:

1. Open a browser and go to **https://posthog.com**.
2. Click **Get started** / **Sign up** and create a free account. PostHog has a
   generous **free tier** (see [Section 7](#7-what-does-this-cost)); no credit
   card needed to begin.
3. During sign-up, PostHog asks you to pick a **cloud region**: **US** or
   **EU**. This is just *which set of PostHog's servers* will store your tallies.
   Pick whichever you prefer (EU is common if you want data kept in Europe).
   **Make a note of which one you picked** — it decides one of the values you'll
   enter in the next section:
   - If you chose **US**, the "host" is **`https://us.i.posthog.com`**.
     (This is the app's default, so you can leave it out entirely.)
   - If you chose **EU**, the "host" is **`https://eu.i.posthog.com`**.
     (You'll need to add this line yourself.)

   A "host" is simply the web address of the PostHog servers your app talks to.
   Choosing the wrong one means your data quietly goes nowhere, so this is worth
   getting right.
4. Once you're in, PostHog creates a **project** for you (same idea as Sentry's
   project — a labeled bucket for this app's data).
5. Find the **Project API Key**. It usually lives under **Settings → Project**
   (look for "Project API Key"). It's a string that starts with `phc_...`.
   **Copy it.** That's the only value PostHog needs from you. (This key is safe
   to ship inside a mobile app — it can only *send* events in, not read your
   data back out.)

Keep the copied key — and your US/EU choice — handy for the next section.

---

## 4. Where to put the keys, and the rebuild step

### The private settings file: `local.properties`

Inside the project there is a file at **`android/local.properties`**. Think of
it as the app's **private notebook**: it lives **only on this one computer** and
is deliberately **never uploaded, shared, or committed to git** (it's listed in
`.gitignore`, the file that tells git "ignore these"). That privacy is exactly
*why* secret keys belong here and nowhere else — putting them in normal,
shared code would leak them to anyone who sees the project.

If the file doesn't exist yet, create a plain text file at that exact path. It
already holds other settings on a working machine (such as `API_BASE_URL`); you
are just **adding a few lines to the bottom**. Don't delete what's already there.

### The exact lines to add

Add these lines, replacing the parts in parentheses with the real values you
copied:

```properties
SENTRY_DSN=(the DSN you copied from Sentry)
POSTHOG_API_KEY=(the Project API Key you copied from PostHog)
POSTHOG_HOST=https://eu.i.posthog.com
```

Notes on each line:

- **`SENTRY_DSN`** — paste your Sentry DSN here. Leave this line out (or blank)
  if you don't want crash reporting; Sentry then stays fully off.
- **`POSTHOG_API_KEY`** — paste your PostHog Project API Key here. Leave it out
  (or blank) to keep analytics fully off.
- **`POSTHOG_HOST`** — **only needed if you chose the EU region** in
  [Section 3](#3-getting-your-posthog-key-the-project-api-key). If you chose
  **US**, simply **delete this line** — the app already defaults to the US
  address. Put it in only for EU.

Don't put quotes around the values, and don't add spaces around the `=` sign —
just `NAME=value`.

> **Safety check:** if you leave a key out or blank, that tool simply does
> nothing — the app neither sends data nor errors. There's no way to half-break
> it.

### You must rebuild the app — and re-ship it — for keys to take effect

Here's the part that surprises first-timers: **adding the keys to the file is
not enough on its own.** An Android app is "compiled" — its settings are baked
in when the app is *built* (assembled into the installable file). So the app has
to be **rebuilt** *after* you save the keys, so the new build can read them.

And there's a second layer: the version of the app your **real users** have on
their phones is whatever you last uploaded to the **Play Store** (Google's app
store). So the keys only start working for real users once you:

1. Save the keys in `local.properties`, then
2. **Rebuild** the app's upload file, then
3. **Upload** that newly built version to the Play Store and let users update.

Until that freshly built version reaches phones, **nothing is tracked** — your
current live app predates the keys and keeps reporting nothing.

This guide is **not** a build tutorial — you don't need to learn the build
system here. The point to remember is simply: it's the **same "build the release
bundle" step** you already use to produce the app's uploadable file for the Play
Store (the file with an `.aab` extension, called an **AAB** — "Android App
Bundle", just the package format the Play Store accepts). Whoever builds your
release does that step as usual; the keys ride along automatically because
they're now in the file the build reads.

---

## 5. Exactly what this app measures

This is the most important section for trust. Here's precisely what the app
records — no more, no less — based on the actual code.

### The events PostHog receives

The app sends PostHog a short, fixed list of **events**. An "event" is just a
named note that says *"this kind of thing just happened"* — like a tally mark
with a label. Here is every event the app sends, with the plain-language action
that triggers it:

| Event name (what PostHog shows) | What the user did to trigger it |
| --- | --- |
| `app_opened` | Opened the app. |
| `business_created` | Set up a new business/account inside the app. |
| `transaction_saved` | Saved a record of money in or money out. |
| `screenshot_uploaded` | Attached a payment screenshot (proof) that uploaded successfully. |
| `draft_confirmed` | Saved a record while keeping at least one field the AI had auto-suggested from a screenshot. |
| `export_created` | Exported a report (as a CSV spreadsheet or a PDF). |

A few events carry a tiny, **non-personal** label so you can group them:

- `transaction_saved` includes `direction` (the word `money_in` or `money_out`)
  and `has_proof` (simply `true` or `false` — whether a proof image was
  attached). It does **not** include the amount, who paid, or any note.
- `export_created` includes `format` (the word `csv` or `pdf`).

That's the complete list. No other actions in the app are tracked.

### Is anything captured automatically?

The app's PostHog setup only turns the tool on with your key and region — it
does **not** enable **session recording** (also called "session replay" — a
feature that records a video-like replay of a user's screen). **Session
recording is OFF.** No screen recordings are ever made.

PostHog's Android tool does, by default, also note a few generic lifecycle
moments on its own (for example, the app being installed/updated, opened, or
moved to the background, and basic screen-view counts). These are **anonymous
counts of "the app was used,"** never the *content* of any screen. If you ever
want even those turned off, that's a one-line change in the app's code — ask
your developer. The named events in the table above are the meaningful ones the
app intentionally sends.

### What is NOT collected — the reassuring part

Neither Sentry nor PostHog ever receives any of your users' actual business
data. Specifically, **none of the following ever leaves the phone to these
tools**:

- ❌ No **payment amounts** (no naira figures, no totals).
- ❌ No **people's names** — not who paid, not who was paid.
- ❌ No **phone numbers**, emails, or contact details.
- ❌ No **proof images** / screenshots.
- ❌ No **financial records**, notes, references, or category text.

What PostHog actually gets is only **the fact that an action happened** (the
event name, like `export_created`) plus an **anonymous random ID** — a
meaningless string of characters that lets PostHog tell "the same phone did two
things" apart from "two different phones," without ever knowing whose phone it
is. It is not your user's name, number, or account.

What Sentry actually gets, only when the app crashes, is the **technical crash
details**: which line of code failed and the error message, plus basic device
facts like the **phone model**, the **Android version**, and the **app
version**. This is the information needed to reproduce and fix the bug — not the
user's data.

This is exactly the behavior described in the app's **privacy policy**
(`docs/privacy-policy.md`) and **Play Data Safety declaration**
(`docs/play-data-safety-DRAFT.md`). If you change *what* is tracked, update
those two documents to match — keeping them honest is part of shipping
responsibly.

---

## 6. How and where to see the data

### Sentry — finding crashes

1. Go to **https://sentry.io** and log in.
2. Open your **project** (the `proofledger` bucket you made).
3. Click **Issues** in the left-hand menu. This is your list of crashes. Each
   "issue" is one kind of error; click one to see the details (which code
   failed, on which phone model, how many users hit it).
4. **Recommended:** turn on **email alerts** so Sentry emails you when a new
   crash appears, instead of you having to check the site. Look under
   **Settings → Alerts** (or the project's **Alerts** tab) and create a simple
   "notify me on a new issue" rule. Now you'll hear about breakage the day it
   starts.

If the Issues list is empty, that's good news — it usually means nothing has
crashed (or no one has hit the new build yet).

### PostHog — seeing how the app is used

1. Go to **https://posthog.com** and log in (or **https://eu.posthog.com** if
   you chose the EU region).
2. Open your **project**.
3. Look for the **Activity** or **live events** feed (often under
   **Activity → Explore**, or a section labeled "Live events"). This is a
   real-time stream of the events as they arrive — you'll see `app_opened`,
   `export_created`, and the others tick in. Watching this feed is the easiest
   way to confirm everything is wired up: open the app on a test phone and watch
   `app_opened` appear.
4. When you're ready for more than a live feed, PostHog lets you build an
   **insight** — a simple saved chart, like "how many `export_created` events
   happened each day." Start with the **Insights** or **Product analytics**
   section and pick a basic "trends" chart.
5. A slightly fancier view is a **funnel** — a chart showing how many people got
   from one step to the next. For example: of everyone who triggered
   `app_opened`, how many went on to `export_created`? That tells you how many
   visitors actually reach the "export a report" payoff. Don't worry about
   mastering this on day one; the live feed and a single trends chart are plenty
   to start.

Keep it light at first. The goal isn't dashboards-for-dashboards' sake — it's
just to learn which parts of the app people really use.

---

## 7. What does this cost?

Both tools have **free tiers that are comfortably enough for a new app's
volume** — a small number of users generates a small number of crashes and
events, well within free limits. You can run for a long time without paying
anything.

**One caveat as you grow:** both tools price by *how much they receive* (Sentry
by number of errors, PostHog by number of events). If your user base grows a
lot, keep a casual eye on your usage in each tool's billing/usage page so a busy
month doesn't quietly push you past the free limit. For a brand-new app, this is
nothing to worry about yet — just something to glance at occasionally later.

---

## Quick recap

- These tools are **optional** and **developer-only**; the app works perfectly
  without them and users never see them.
- **Sentry** = crash reports (needs a `SENTRY_DSN`).
- **PostHog** = usage tallies (needs a `POSTHOG_API_KEY`, plus `POSTHOG_HOST`
  only if you chose EU).
- Put the keys in **`android/local.properties`** (private, never shared), then
  **rebuild and re-upload** the app — keys do nothing until a fresh build
  reaches users.
- Only **action names + an anonymous ID** (PostHog) and **technical crash
  details** (Sentry) are ever sent. **No amounts, names, numbers, images, or
  records** — matching the privacy policy and Data Safety declaration.
