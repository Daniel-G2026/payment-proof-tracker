# Phase 13 — Allocation & Dashboard Engineering Contract (Cut 0)

> **Status:** normative. This is the reference every later Phase 13 cut's tests
> assert against. The allocation engine is implemented **twice** — Android
> `data/balance/AllocationEngine.kt` (Cut 2) and backend
> `app/services/allocation.py` (Cut 2) — and **both** must reproduce the worked
> examples below *exactly*, to the minor unit. If a later design decision changes
> a rule here, change this document **first**, then the two engines and their
> tests together. No engine behaviour is "obvious"; it is whatever this file says.

This document fixes, before any code:

1. The data model the engine operates on (inputs / outputs / units).
2. The allocation algorithm (waterfall, earmark, cascade) — precisely.
3. The per-installment state machine (incl. *filled-late* / *filled-ahead*).
4. The arrears / dashboard counting math (incl. *buyers counted once*).
5. Worked examples A–G with exact expected numbers (the test fixtures).
6. The renegotiation / earmark / late semantics locked for Phase 13.

It does **not** specify schema column types (see `master-plan.md` Phase 13 and
the plan file) or UI. It specifies *behaviour*.

---

## 0. Vocabulary

- **Unit** — a house/plot. Has one buyer (a counterparty) and one **active**
  schedule version. Money-in transactions ("payments") link to a unit.
- **Schedule version** — an ordered list of installments. A unit may have many
  versions (renegotiations); exactly **one is active**. The engine only ever
  reads the **active** version.
- **Installment** — one scheduled line: `(sequence, amount_minor, due_date)`.
  Installments carry **no fill columns** — fills are computed, never stored.
- **Payment** — a money-in transaction linked to the unit:
  `(id, amount_minor, date, earmarked_installment_id?)`.
- **Earmark** — the *only* stored allocation intent: a payment may name a target
  installment. Null ⇒ pure waterfall.

### 0.1 Money & dates

- **All amounts are integer minor units** (kobo / cents), mirroring
  `transaction.amount_minor` (`BigInteger`) and `installment.amount_minor`.
  ₦500,000 = `50000000` minor units. **No floating point. No rounding.** The
  engine never divides; it only adds and subtracts integers.
- Worked examples below are written in **major-unit Naira for readability**
  (e.g. "₦500,000"); the corresponding minor-unit literal is that number ×100.
  Test fixtures use minor units.
- A single unit is **single-currency**; the engine is currency-agnostic and
  operates purely on integers. Mixing currencies within a unit is a data error
  prevented upstream, not handled here.
- **Dates are day-granular** (`date`, not datetime). All date comparisons are
  **inclusive**: "due as of D" means `due_date <= D`. `asOf` is a date.

---

## 1. Engine interface (both platforms)

```
allocate(
    installments: [Installment(sequence, amountMinor, dueDate)],   # active version
    payments:     [Payment(id, amountMinor, date, earmarkedInstallmentId?)],  # this unit
    asOf:         date,
) -> AllocationResult
```

`AllocationResult` is:

- `fills: [InstallmentFill]` — one per installment, in schedule order, each:
  - `sequence`, `dueDate`, `scheduledMinor`
  - `filledMinor` — total minor units allocated to this installment (≤ or ≥
    scheduled; cascade never over-fills a line, so `filledMinor ≤ scheduledMinor`
    always — overflow moves on)
  - `shortfallMinor` = `max(0, scheduledMinor - filledMinor)`
  - `onTimeCoverageMinor` — Σ contributions to this line from payments **dated
    `<= dueDate`** (used for late detection; see §3)
  - `state` ∈ { `UNPAID`, `PARTIAL`, `FILLED`, `FILLED_LATE`, `FILLED_AHEAD` }
- `creditMinor` — money left over after the last installment is fully filled
  (overpayment). Never silently dropped; surfaced, not applied beyond schedule.
- `arrearsMinor` — Σ `shortfallMinor` over installments with `dueDate <= asOf`
  (see §4).

The engine is a **pure function**: same inputs ⇒ same output, no I/O, no clock
read (the caller passes `asOf`), no persistence. Re-run on demand. Back-dated
and corrected payments and re-poured renegotiations therefore self-correct with
no migration of stored state.

### 1.1 Determinism — sort order (normative)

Before allocating, both platforms sort identically:

- **Installments:** ascending by `(dueDate, sequence)`.
- **Payments:** ascending by `(date, id)`. `id` (the stable client/row id) is the
  tiebreak so two same-day payments allocate in a fixed order on both platforms.

`filledMinor ≤ scheduledMinor` for every line because cascade stops filling a
line at its scheduled amount and carries the remainder forward.

---

## 2. Allocation algorithm (precise)

Two passes over the unit's payments. Both passes cascade **forward** (toward
later installments by the §1.1 order); leftover after the last installment
becomes `creditMinor`.

**Pass 1 — earmarks.** For each payment that has an
`earmarkedInstallmentId` **referencing an installment in the active version**,
processed in `(date, id)` order:

1. Apply the payment to its **target** installment, filling up to that line's
   remaining (`scheduled - alreadyFilled`).
2. Any overflow cascades **forward** to subsequent installments
   (those after the target in §1.1 order) that still have room, oldest-first.
3. Anything left after the last installment is added to `creditMinor`.

An earmark whose target installment does **not** belong to the active version
(e.g. it referenced a now-superseded version after a renegotiation) is treated
as **no earmark** — the payment falls through to Pass 2. This is the locked
"earmark across renegotiation = historical intent only" rule (§6).

**Pass 2 — waterfall.** For every remaining payment (no earmark, or an earmark
that did not bind), processed in `(date, id)` order:

1. Pour into the **oldest still-unfilled** installment (lowest `(dueDate,
   sequence)` with remaining room).
2. Cascade overflow forward, oldest-first, across still-unfilled installments.
3. Leftover after the last installment is added to `creditMinor`.

Each unit of money allocated records the **contributing payment's date** against
that installment (for §3 late detection). The split into two passes is **not**
chronological; allocation (which money lands on which line) is fully determined
by the two passes above. The **dates** attached to those contributions are what
drive late/ahead, via the order-independent coverage rule in §3 — so the
two-pass ordering never makes "late" ambiguous.

> **Why earmark-then-waterfall, not strict date order?** An earmark is an
> explicit human instruction ("this money is for installment 3") that must win
> over oldest-first even when earlier installments are unpaid. Pass 1 honours
> that intent; Pass 2 then fills whatever the earmarks left behind, oldest-first.

---

## 3. Per-installment state machine

For an installment with scheduled `S`, total filled `F`, on-time coverage `C`
(Σ contributions dated `<= dueDate`), due date `D`, evaluated at `asOf`:

| Condition | State |
|---|---|
| `F == 0` | `UNPAID` |
| `0 < F < S` | `PARTIAL` (shortfall `S - F`) |
| `F >= S` and `D > asOf` | `FILLED_AHEAD` (fully paid before it is due) |
| `F >= S` and `D <= asOf` and `C >= S` | `FILLED` (covered on time) |
| `F >= S` and `D <= asOf` and `C < S` | `FILLED_LATE` (only completed after due) |

**On-time coverage `C` is order-independent:** `C` = sum of the contribution
amounts to this line whose **payment date `<= dueDate`**. Late is therefore
"the money dated on-or-before the due date did not cover the line," regardless
of the order Pass 1/Pass 2 applied contributions. This is the single source of
truth for *late* — do not compute late from a "completion date" derived from
pass order.

**`FILLED_AHEAD`** means a *future* installment (due after `asOf`) is already
fully covered — the buyer has prepaid. It is reported as fully filled and
contributes **zero** to arrears.

**"Filled late" is late under the ACTIVE schedule only** (§6): `C` and `D` come
from current due dates. A renegotiation that moves a due date later can turn a
`FILLED_LATE` into `FILLED`. The app makes no historical "was late under the old
offer letter" claim.

A `PARTIAL` or `UNPAID` installment that is **not yet due** (`D > asOf`) is a
normal not-yet-due line and contributes **zero** to arrears, even though its
state is `PARTIAL`/`UNPAID`. State and arrears are separate questions.

---

## 4. Arrears & dashboard math

### 4.1 Unit-level arrears as of a date

```
arrears(asOf) = Σ shortfallMinor over installments where dueDate <= asOf
```

Counts unpaid and partial **past-or-now-due** lines. `FILLED_LATE` lines now
have zero shortfall, so they contribute nothing — arrears is the *current* gap,
not a history of lateness.

### 4.2 Dashboard period

The dashboard sweeps the **calendar month containing the dashboard's `asOf`**.
Let `monthStart` / `monthEnd` be the first / last day of that month.

Per unit:

- **past-due arrears** = Σ shortfall over installments with `dueDate < monthStart`.
- **this-month shortfall** = Σ shortfall over installments with
  `monthStart <= dueDate <= monthEnd`.
- **combined total (unit)** = past-due arrears + this-month shortfall
  = Σ shortfall over installments with `dueDate <= monthEnd`
  = **`arrears(monthEnd)`**.

> **Identity (assert this in tests):** a unit's dashboard combined total equals
> `arrears(asOf = monthEnd)`. The "this-month + all past-due" decomposition and
> the single `arrears(monthEnd)` call must agree to the minor unit.

### 4.3 Buyer-level counting — **buyers counted once**

A buyer may hold several units. The dashboard headline counts **buyers**, not
units (locked rule). With the current calendar month fixed:

- **`peopleDueThisMonth`** = number of **distinct buyers** who hold at least one
  unit having an installment with `monthStart <= dueDate <= monthEnd`. A buyer
  with two due units counts **once**.
- **`peopleFulfilledThisMonth`** = of those due-this-month buyers, the count
  whose **total this-month shortfall across all their units is `0`** (every
  installment due this month, across every unit they hold, is fully filled as of
  `asOf`).
- **`combinedOutstandingTotal`** = Σ over units **with an assigned buyer** of the
  unit combined total (§4.2) = Σ `arrears(monthEnd)`. A buyer's multiple units
  all contribute (even though the *people* counts dedupe by buyer). Units with a
  schedule but **no assigned buyer** (`buyerId == null`) are **excluded entirely**
  — they're not a live receivable until a buyer is set. This commonly arises when
  an offer letter is scanned (Cut 6) before the buyer is assigned; such a unit is
  invisible to the home dashboard (not in the people counts, not in the total)
  until its buyer is set.

Per-buyer tap-in detail splits, per the locked decision:

- **`behind`** = that buyer's past-due arrears (Σ over their units of
  installments with `dueDate < monthStart`).
- **`dueThisMonth`** = that buyer's this-month shortfall (Σ over their units of
  installments with `monthStart <= dueDate <= monthEnd`).

A buyer with no installment due in the current month is **not** in
`peopleDueThisMonth`, even if they carry past arrears — but their past arrears
**still** contribute to `combinedOutstandingTotal`. (The headline answers "who
is due this month?"; the total answers "how much is outstanding overall?")

---

## 5. Worked examples (the test fixtures)

Unless noted, examples use canonical schedule **S1** (active version), total
₦2,000,000, four equal installments:

| seq | amount | due_date |
|---|---|---|
| I1 | ₦500,000 | 2026-01-31 |
| I2 | ₦500,000 | 2026-02-28 |
| I3 | ₦500,000 | 2026-03-31 |
| I4 | ₦500,000 | 2026-04-30 |

> All amounts ×100 in minor units in the fixtures (₦500,000 → `50000000`).
> Each example lists inputs, `asOf`, and the **exact** expected per-installment
> state + shortfall, `creditMinor`, and `arrearsMinor`.

### Example A — 300k partial (single under-payment)

Payments: `P1 = ₦300,000 @ 2026-01-20` (no earmark). `asOf = 2026-02-01`.

Allocation: P1 → I1 (300k of 500k).

| line | filled | state | shortfall |
|---|---|---|---|
| I1 | ₦300,000 | `PARTIAL` | ₦200,000 |
| I2 | ₦0 | `UNPAID` | ₦500,000 |
| I3 | ₦0 | `UNPAID` | ₦500,000 |
| I4 | ₦0 | `UNPAID` | ₦500,000 |

`creditMinor = 0`. `arrears(2026-02-01)` = only I1 is due (`<= 02-01`) →
**₦200,000**.

### Example B — 800k cascade (overflow across two lines)

Payments: `P1 = ₦800,000 @ 2026-01-25` (no earmark).

Allocation: I1 filled 500k (contribution dated 01-25 ≤ due 01-31 ⇒ on-time);
overflow 300k → I2.

**B1, `asOf = 2026-02-15`:**

| line | filled | state | shortfall |
|---|---|---|---|
| I1 | ₦500,000 | `FILLED` | ₦0 |
| I2 | ₦300,000 | `PARTIAL` | ₦200,000 |
| I3 | ₦0 | `UNPAID` | ₦500,000 |
| I4 | ₦0 | `UNPAID` | ₦500,000 |

`creditMinor = 0`. I2 is **not yet due** (02-28 > 02-15) ⇒ excluded from arrears.
`arrears(2026-02-15)` = I1 only, filled ⇒ **₦0**.

**B2, `asOf = 2026-03-01`** (same allocation, later date): I2 now due
(`02-28 <= 03-01`), still `PARTIAL`. `arrears(2026-03-01)` = I2 shortfall ⇒
**₦200,000**. (States unchanged except I2 now counts toward arrears.)

### Example C — 1m bulk catch-up (lump fills multiple, late)

Payments: `P1 = ₦300,000 @ 2026-01-20`, then `P2 = ₦1,000,000 @ 2026-03-15`.

Allocation (oldest-first):
- I1: 300k (P1@01-20) + 200k (P2@03-15) = 500k filled. On-time coverage =
  contributions dated ≤ 01-31 = 300k < 500k ⇒ **late**.
- I2: 500k (P2@03-15). On-time coverage (≤ 02-28) = 0 ⇒ **late**.
- I3: 300k (P2@03-15) ⇒ partial.

**C1, `asOf = 2026-03-20`:**

| line | filled | state | shortfall |
|---|---|---|---|
| I1 | ₦500,000 | `FILLED_LATE` | ₦0 |
| I2 | ₦500,000 | `FILLED_LATE` | ₦0 |
| I3 | ₦300,000 | `PARTIAL` | ₦200,000 |
| I4 | ₦0 | `UNPAID` | ₦500,000 |

`creditMinor = 0`. I3 due 03-31 > 03-20 ⇒ not yet due. `arrears(2026-03-20)` =
I1, I2 both filled ⇒ **₦0**.

**C2, `asOf = 2026-04-01`:** I3 now due (`03-31 <= 04-01`), `PARTIAL`.
`arrears(2026-04-01)` = I3 shortfall ⇒ **₦200,000**.

### Example D — back-dated payment self-corrects (un-lates I1)

Start from the bulk only: `P2 = ₦1,000,000 @ 2026-03-15`, `asOf = 2026-03-20`.

Allocation: I1 500k (late, on-time cov 0), I2 500k (late). I3, I4 unpaid.
`arrears = ₦0` (I1,I2 filled; I3,I4 future).

Now the user discovers and enters a **back-dated** payment that actually arrived
earlier: `P0 = ₦600,000 @ 2026-01-15`. Re-run with `{P0, P2}`, `asOf = 2026-03-20`:

Allocation (oldest-first, by date P0 then P2):
- I1: 500k from P0@01-15. On-time coverage (≤ 01-31) = 500k ⇒ **on time**.
- I2: 100k from P0@01-15 + 400k from P2@03-15 = 500k. On-time cov (≤ 02-28) =
  100k < 500k ⇒ **late**.
- I3: 500k from P2@03-15. Due 03-31 > asOf 03-20 ⇒ **ahead**.
- I4: 100k from P2@03-15 ⇒ partial.

| line | filled | state | shortfall |
|---|---|---|---|
| I1 | ₦500,000 | `FILLED` | ₦0 |
| I2 | ₦500,000 | `FILLED_LATE` | ₦0 |
| I3 | ₦500,000 | `FILLED_AHEAD` | ₦0 |
| I4 | ₦100,000 | `PARTIAL` | ₦400,000 |

`creditMinor = 0`. `arrears(2026-03-20)` = I4 due 04-30 > asOf, I1–I3 filled ⇒
**₦0**. **Contract point:** I1 flipped `FILLED_LATE → FILLED` purely because a
back-dated payment was added — nothing stored had to be migrated. Demonstrates
"compute on read."

### Example E — earmark beats oldest-first

Payments: `P1 = ₦500,000 @ 2026-02-10`, **earmarked to I3**. `asOf = 2026-02-15`.

Pass 1 (earmark): P1 fills I3 exactly. No earlier line touched.

| line | filled | state | shortfall |
|---|---|---|---|
| I1 | ₦0 | `UNPAID` | ₦500,000 |
| I2 | ₦0 | `UNPAID` | ₦500,000 |
| I3 | ₦500,000 | `FILLED_AHEAD` | ₦0 |
| I4 | ₦0 | `UNPAID` | ₦500,000 |

`creditMinor = 0`. I3 due 03-31 > asOf 02-15 ⇒ `FILLED_AHEAD`.
`arrears(2026-02-15)` = I1 due 01-31 ≤ asOf, unpaid ⇒ **₦500,000**.

**Contract point — contrast with no earmark:** the *same* ₦500,000 @ 02-10 with
**no** earmark waterfalls into I1 and `arrears` becomes **₦0**. (That payment is
dated 02-10, after I1's 01-31 due date, so I1 is `FILLED_LATE` — late, but no
longer in arrears because it is filled.) The earmark deliberately leaves I1
behind because the human directed the money at I3. The defining contrast is the
**arrears**: ₦0 without the earmark vs ₦500,000 with it.

### Example F — earmark drops on renegotiation (re-pour)

Setup: schedule **v1 = S1**, with `P1 = ₦500,000 @ 2026-02-10` earmarked to
**v1's I3**. Then a renegotiation creates active version **v2** (v1
deactivated), total still ₦2,000,000:

| seq | amount | due_date |
|---|---|---|
| J1 | ₦400,000 | 2026-02-28 |
| J2 | ₦800,000 | 2026-04-30 |
| J3 | ₦800,000 | 2026-06-30 |

Re-pour P1 into the **active** version v2. P1's `earmarkedInstallmentId` points
at v1's I3, which is **not** in v2 ⇒ earmark does not bind ⇒ pure waterfall.
`asOf = 2026-03-01`:

Allocation: J1 400k (P1@02-10, on-time cov ≤ 02-28 = 400k ⇒ on time); overflow
100k → J2.

| line | filled | state | shortfall |
|---|---|---|---|
| J1 | ₦400,000 | `FILLED` | ₦0 |
| J2 | ₦100,000 | `PARTIAL` | ₦700,000 |
| J3 | ₦0 | `UNPAID` | ₦800,000 |

`creditMinor = 0`. J2 due 04-30 > asOf, J3 due 06-30 > asOf ⇒ not due.
`arrears(2026-03-01)` = J1 filled ⇒ **₦0**.

**Contract points:**
1. Under v1 the same payment was `FILLED_AHEAD` on I3 with I1 ₦500,000 behind;
   under active v2 it is `FILLED` on J1 with ₦0 arrears. The engine reads **only
   the active version** and re-derives everything — the earmark became
   historical intent and silently dropped.
2. **Un-late:** had a payment been late under v1's earlier due dates, v2's later
   due dates can make the same payment on-time. "Late" is always vs the active
   plan.

### Example G — overpayment surfaces as credit

Payments: `P1 = ₦2,500,000 @ 2026-01-15` (no earmark). `asOf = 2026-05-01`. The
payment is dated **before every due date** (so each line is covered on time) and
`asOf` is **after the last due date** (so none are ahead) — isolating the credit.

Allocation fills I1..I4 fully (₦2,000,000); ₦500,000 remains after the last line.

| line | filled | state | shortfall |
|---|---|---|---|
| I1 | ₦500,000 | `FILLED` | ₦0 |
| I2 | ₦500,000 | `FILLED` | ₦0 |
| I3 | ₦500,000 | `FILLED` | ₦0 |
| I4 | ₦500,000 | `FILLED` | ₦0 |

(Each line: on-time coverage = 500k — the 01-15 payment predates every due date
— and `D <= asOf`, so `FILLED`, not ahead.)
`creditMinor = ₦500,000` (surfaced, not applied beyond schedule).
`arrears(2026-05-01)` = **₦0**.

---

## 6. Dashboard worked example (buyers counted once)

`asOf = 2026-02-15` ⇒ current month = **February 2026**
(`monthStart = 2026-02-01`, `monthEnd = 2026-02-28`).

Buyers and units:

- **Ada** holds **two** units, both with a February installment:
  - *Plot 12* = S1, with `P1 = ₦300,000 @ 2026-01-20`.
    - past-due (`< 02-01`): I1 due 01-31 shortfall ₦200,000 → behind ₦200,000.
    - this-month (Feb): I2 due 02-28 shortfall ₦500,000 → due-this-month ₦500,000.
    - unit combined = `arrears(02-28)` = ₦700,000.
  - *Plot 13*: single installment ₦400,000 due 2026-02-20, **unpaid**.
    - past-due: ₦0. this-month: ₦400,000. unit combined = ₦400,000.
  - **Ada totals:** behind = ₦200,000; dueThisMonth = ₦900,000;
    combined = ₦1,100,000. Ada has Feb installments ⇒ **due this month**.
    this-month shortfall ≠ 0 ⇒ **not fulfilled**.
- **Bola** holds *Plot 20*: single installment ₦400,000 due 2026-02-10, paid
  `₦400,000 @ 2026-02-05`. this-month shortfall ₦0, past arrears ₦0. Has a Feb
  installment ⇒ **due this month**, and **fulfilled**.
- **Chidi** holds *Plot 30*: installments only in March+ (none in Feb, none
  past-due). Not due this month. Combined = `arrears(02-28)` = ₦0.

Dashboard headline:

| metric | value | why |
|---|---|---|
| `peopleDueThisMonth` | **2** | Ada (counted **once** despite 2 due units) + Bola; Chidi excluded |
| `peopleFulfilledThisMonth` | **1** | Bola only (Ada has Feb shortfall) |
| `combinedOutstandingTotal` | **₦1,100,000** | Ada ₦1,100,000 + Bola ₦0 + Chidi ₦0 |

**Contract points:**
1. Ada counts **once** in `peopleDueThisMonth` though she holds two due units —
   the locked "count buyers, not units" rule. Unit detail (Plot 12 / Plot 13)
   appears underneath on tap.
2. `combinedOutstandingTotal` still **sums both** of Ada's units (₦700,000 +
   ₦400,000) — the *money* total never dedupes by buyer, only the *people* count
   does.
3. Per-buyer tap-in shows Ada split as `behind ₦200,000` / `due this month
   ₦900,000`.

---

## 7. Locked semantic edges (authoritative restatement)

These are the same edges fixed in `master-plan.md` Phase 13 and the plan file;
they are restated here as the engine contract so a later cut cannot regress them
without editing this file.

1. **Earmark across renegotiation = historical intent only.** An
   `earmarkedInstallmentId` that targets an installment not in the **active**
   version does not bind; the payment reverts to pure oldest-first waterfall
   (Example F). Upgrade path if real usage collides often: stable schedule-line
   identity surviving versioning — deliberately *not* built for v1.
2. **"Filled late" = late under the ACTIVE schedule.** Computed from current due
   dates via on-time coverage (§3). A renegotiation that moves a due date later
   can un-late a payment. No "historically late under the old letter" claim.
3. **One active schedule per unit — enforced, not promised.** Backend: partial
   unique index on `unit_id WHERE is_active`. Android: repository + DAO
   enforcement (Room can't express the partial index). Activating a new version
   deactivates the prior one in the **same** transaction. The engine assumes
   exactly one active version and reads only it; "no active version" ⇒ empty
   schedule ⇒ all payments become `creditMinor`, `arrears = 0`.
4. **Dashboard counts buyers, not units** (§4.3, Example 6).
5. **Computed, never stored.** Only the schedule (versions + installments) and
   the payments (+ optional earmark) persist. Fills, states, arrears, credit are
   recomputed on every read. There is **no** fill table to migrate.

---

## 8. What each later cut must assert against this file

- **Cut 2** (engine): `AllocationEngineTest.kt` and `test_phase13_allocation.py`
  reproduce Examples A–G **exactly** (states + shortfalls + `creditMinor` +
  `arrearsMinor`), including the B/C two-`asOf` variants, D's back-date
  self-correction, E's earmark-vs-no-earmark contrast, F's re-pour drop+un-late,
  and G's credit. Both platforms must agree to the minor unit.
- **Cut 3** (link payments): earmark honoring, multi-unit routing, and
  earmark-drops-on-renegotiation tests reuse Examples E and F.
- **Cut 4** (dashboard): aggregation test reuses §6, asserting the
  buyers-counted-once headline, the `combinedOutstandingTotal = Σ arrears(monthEnd)`
  identity, and the per-buyer behind/due split.
