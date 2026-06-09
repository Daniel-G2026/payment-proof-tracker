# Phase 13 — Offer-Letter AI Extraction Engineering Contract (Cut 6)

> **Status:** normative. This is the reference Cut 6's code and tests assert
> against. Cut 6 is the **last** Phase 13 cut and the only one that uses AI.
> It is **additive**: the manual schedule editor (Cut 2) is unchanged and is
> always available. The AI only ever produces a **draft** the user must review
> and confirm — it never auto-creates a schedule. If a rule here changes, change
> this document **first**, then the code and tests together.

This document fixes, before any code:

1. The capture → draft → review → confirm flow and where it lives.
2. The `screenshots.document_type` discriminator.
3. The `schedule_drafts` table (shape + status machine).
4. The AI tool schema (`record_offer_letter`) and confidence handling.
5. The draft → schedule-create field mapping (number-exact).
6. The reconciliation, low-confidence, and failure rules.
7. Worked examples A–D (the test fixtures).

It reuses, unchanged: the Phase 8 orphan-screenshot upload + arq worker + Claude
Haiku forced-tool-use + poll machinery (`app/worker/vision.py`,
`app/services/extraction.py`, `ServerDraftPoller`), the Phase 8 per-user rate
limit, the `raw_json` retention cron, and the Cut 2 `create_schedule_version`
endpoint + `AllocationEngine`. Cut 6 adds a **second** document type alongside
payment proofs, not a parallel pipeline.

---

## 0. Vocabulary

- **Offer letter** — a document a developer issues to a buyer stating the unit,
  the total price, and the installment plan. Accepted as a photo/scan
  (JPEG/PNG/WebP) **or a PDF** — offer letters are commonly held as PDFs. Cut 6
  extracts the **plan**, not legal text.
- **Schedule draft** — the server-side AI extraction result for one offer-letter
  screenshot: a proposed `(buyer_name?, total_minor?, currency?, installments[])`
  plus per-field confidence. Mirrors `transaction_drafts`.
- **Confirm** — the user-reviewed draft becomes a real **versioned schedule**
  via the existing `POST .../units/{unit_id}/schedule-versions` (Cut 2). The
  draft itself never writes schedule rows.

### 0.1 Money & dates

Same as the allocation contract: amounts are **integer minor units** (kobo for
NGN); dates are ISO-8601 `YYYY-MM-DD`. No floats anywhere.

---

## 1. Flow & entry point

Capture lives on the **unit's schedule screen** (where manual entry already is):
`Projects → project → unit → schedule`.

```
[Scan offer letter]
  → camera/picker → orphan screenshot upload (document_type=offer_letter)
  → worker extracts → poll GET /screenshots/{id}/schedule-draft (5s, ≤60s)
  → status=ready → REVIEW screen (pre-filled, low-confidence fields blank/flagged)
  → user edits freely → [Confirm schedule]
  → POST .../schedule-versions (source=offer_letter, offer_letter_screenshot_id)
  → versioned schedule created (re-pour + one-active enforced by Cut 2 path)
```

**The unit already has a buyer** (set when the unit was created). Cut 6 does
**not** change the unit's buyer. The extracted `buyer_name` is shown only to let
the user confirm the letter matches the unit's buyer; a mismatch is a soft
warning, never a block and never an edit to the counterparty.

Manual entry is unchanged and reachable without ever scanning.

---

## 2. `screenshots.document_type`

New nullable-with-default column on `screenshots`:

- `document_type TEXT NOT NULL DEFAULT 'payment_proof'`
- Allowed: `payment_proof` | `offer_letter`.

The upload route accepts an optional `document_type` (defaults to
`payment_proof`, so every existing caller is unaffected). The worker dispatches
on it: `payment_proof` → existing `record_extraction`; `offer_letter` →
`record_offer_letter` (this contract). Backfill: existing rows are
`payment_proof` (the column default covers them; no data migration needed).

---

## 3. `schedule_drafts` table

One draft per offer-letter screenshot (`UNIQUE(screenshot_id)`); re-extraction
upserts in place. Mirrors `transaction_drafts`.

| column | type | notes |
|---|---|---|
| `id` | uuid PK | |
| `screenshot_id` | uuid FK→screenshots, **unique**, ON DELETE CASCADE | |
| `business_id` | uuid FK→businesses, indexed, ON DELETE CASCADE | |
| `status` | text | `queued`→`processing`→`ready`\|`failed`\|`rate_limited` |
| `buyer_name` | text null | cleaned, post-confidence-floor |
| `total_minor` | bigint null | cleaned |
| `currency_code` | varchar(8) null | cleaned, ISO-4217 upper |
| `installments` | JSON null | `[{sequence:int, amount_minor:int, due_date:"YYYY-MM-DD"}]`, post-floor |
| `confidence` | JSON null | per-field self-reported 0–1 (incl. `installments` key) |
| `raw_json` | JSON null | full tool input; purged by the existing retention cron |
| `error_code` | text null | `vision_error`\|`parse_error`\|`timeout`\|`unreadable` |
| `error_message` | text null | |
| `model_version` | text null | |
| `attempts` | int | |
| `created_at`/`updated_at`/`confirmed_at` | timestamptz | |

Status/error string constants are importable (single source), as with
`transaction_draft.py`.

---

## 4. AI tool schema — `record_offer_letter`

Forced tool-use (`tool_choice = {type: tool, name: record_offer_letter}`),
prompt-cached system prompt, same `CONFIDENCE_FLOOR = 0.5`. Tool input:

```jsonc
{
  "buyer_name":    string | null,   // the buyer named on the letter
  "total_minor":   integer | null,  // total price, minor units (no symbol)
  "currency_code": string | null,   // ISO 4217 if shown
  "installments": [                 // the plan, in stated order
    { "sequence": integer,          // 0-based, as ordered on the letter
      "amount_minor": integer,      // minor units
      "due_date": string }          // "YYYY-MM-DD"; null/relative → omit the row
  ] | null,
  "confidence": {                   // 0.0–1.0 per field; "installments" is one score for the whole array
    "buyer_name": number, "total_minor": number,
    "currency_code": number, "installments": number
  },
  "unreadable": boolean             // true if not an offer letter / no plan at all
}
```

Required: `confidence`, `unreadable`. The prompt instructs: deposit/initial
payment is `sequence: 0`; amounts in minor units; only emit installment rows
whose `due_date` is an absolute date; if the plan is described in prose the model
should still enumerate concrete dated rows or return `installments: null`.

---

## 5. Normalization & confidence (number-exact)

The normalizer (extend `vision.py`) produces a cleaned draft:

1. **Scalar fields** (`buyer_name`, `total_minor`, `currency_code`): dropped to
   `null` if their confidence `< 0.5` (Phase 8 rule), plus the existing
   type/length cleaning (`total_minor` non-negative integer ≤ 10¹⁵;
   `currency_code` upper, ≤ 8; `buyer_name` ≤ 255).
2. **Installments array**: kept only if **all** hold:
   - `confidence["installments"] >= 0.5`,
   - the array is non-empty after dropping malformed rows (a row is malformed if
     `amount_minor` is not a positive int ≤ 10¹⁵, or `due_date` is not a valid
     ISO date),
   - count ≤ `MAX_INSTALLMENTS` (600).
   Otherwise `installments → null` (the user enters rows manually). Rows are
   re-sequenced `0..n-1` by `(due_date, original sequence)` before storage.
3. **`unreadable: true`** → wipe all fields, `status=failed`,
   `error_code=unreadable` (terminal; no retry), exactly like Phase 8.
4. **Reconciliation** is **advisory, never auto-corrected**: if both
   `total_minor` and a kept `installments` array are present and
   `sum(amount_minor) != total_minor` (tolerance **0**), the draft is still
   `ready`; the review UI shows a "the rows don't add up to the total" warning.
   The engine never edits amounts to force a match.

---

## 6. Endpoints

- **Upload**: existing `POST /businesses/{bid}/screenshots` gains optional
  `document_type`. `offer_letter` uploads auto-enqueue the offer-letter job
  (gated by the same `extraction_enabled` + rate limit as Phase 8).
  `offer_letter` uploads additionally accept `application/pdf` (validated by the
  `%PDF-` magic bytes); the payment-proof path stays image-only. PDFs ride the
  same orphan-upload + storage + poll machinery as images; the vision client
  sends a PDF as an Anthropic `document` content block (each page OCR'd +
  rendered server-side) and an image as an `image` block. All active models,
  including the Haiku 4.5 extractor, accept PDFs (≤100 pages at 200K context;
  the existing 5 MB upload cap still applies).
- **Poll**: `GET /screenshots/{screenshot_id}/schedule-draft` →
  `ScheduleDraftOut`. `404` = not yet (client treats as "keep polling");
  `status=ready` returns cleaned fields; `failed`/`rate_limited` are terminal.
- **Confirm**: there is **no new confirm endpoint**. The client builds a
  `ScheduleVersionCreate` from the (reviewed) draft and calls the existing
  `POST .../units/{unit_id}/schedule-versions` with `source="offer_letter"` and
  `offer_letter_screenshot_id = <the screenshot>`. The draft's `confirmed_at` is
  stamped client-side intent only; server-side we may set it when the schedule
  referencing that screenshot is created (best-effort, non-blocking).

---

## 7. Draft → `ScheduleVersionCreate` mapping

| draft field | becomes | rule |
|---|---|---|
| `installments[]` | `ScheduleVersionCreate.installments[]` | each row gets a client UUID; `sequence` re-numbered `0..n-1` by `(due_date, sequence)`; `amount_minor`, `due_date` carried verbatim |
| — | `source` | `"offer_letter"` |
| screenshot id | `offer_letter_screenshot_id` | the scanned screenshot |
| `total_minor` | (not persisted) | advisory only — drives the §5.4 reconciliation warning |
| `buyer_name` | (not persisted) | advisory only — drives the §1 buyer-match warning |

Confirm reuses Cut 2 wholesale: version numbering, deactivating the prior active
version, the partial-unique one-active guard, and the `AllocationEngine`
re-pour. Cut 6 adds **no** allocation behaviour.

---

## 8. Failure & offline UX (locked)

Visible, never silent (Phase 8.1 product decision):

- `failed`/`unreadable`/`timeout` → banner: **"Couldn't read the offer letter —
  enter the schedule yourself."** The manual editor is right there.
- `rate_limited` → banner: **"AI is busy — try again shortly, or enter it
  manually."**
- Offline at scan → upload fails → no draft → fall through to manual entry
  (no crash, no spinner-forever; mirror the Cut-screen local-first rule).
- A `ready` draft with `installments: null` → review screen opens with an empty
  editor + a note that the rows couldn't be read; scalar fields that *were* read
  still pre-fill.

---

## 9. Worked examples (test fixtures)

Confidence shown only where it changes the outcome; assume ≥0.9 otherwise.

**A — clean plan, reconciles.** Letter: buyer "Ada Obi", total ₦12,000,000,
deposit ₦2,000,000 on 2026-07-01 then ₦1,000,000 monthly ×10 (2026-08-01 …
2027-05-01).
→ draft `ready`: `buyer_name="Ada Obi"`, `total_minor=1200000000`,
`currency="NGN"`, `installments` = 11 rows (seq 0..10), `sum = 1200000000`,
reconciles (no warning). Confirm → an 11-line `offer_letter` schedule version.

**B — total present, rows don't add up.** total ₦12,000,000 but rows sum to
₦11,000,000.
→ draft `ready` with both kept; review shows the "doesn't add up" warning;
engine does **not** alter any amount. User fixes a row or confirms as-is.

**C — not an offer letter.** Photo of a car.
→ `unreadable: true` → `status=failed`, `error_code=unreadable`, all fields
null. Banner shown; manual entry available.

**D — rows readable, buyer name not.** Plan clear; buyer name smudged
(`confidence.buyer_name = 0.3`).
→ `buyer_name → null` (below floor); `installments` kept. Review pre-fills the
rows; buyer field is blank with the §1 match prompt against the unit's buyer.

---

## 10. Build order (this cut)

1. This contract (done).
2. Backend: `screenshots.document_type` + `schedule_drafts` (Alembic **0021**,
   head is `0020`); `record_offer_letter` tool + normalizer in `vision.py`;
   worker dispatch on `document_type`; poll endpoint + `ScheduleDraftOut`;
   tests asserting examples A–D + the rate-limit/retention reuse.
3. Android: scan entry on the unit schedule screen; orphan upload with
   `document_type`; `ScheduleDraftPoller` (mirror `ServerDraftPoller`); review
   screen (reuses the Cut 2 editor, pre-filled + flags) → confirm via the
   existing schedule-create repo; tests.
4. One branch/PR off `main`; manual path untouched; AI is a no-op without
   `ANTHROPIC_API_KEY` (same gating as Phase 8).
