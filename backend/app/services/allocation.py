"""Pure waterfall allocation engine (Phase 13).

The make-or-break piece. This is the backend mirror of the Android
``AllocationEngine.kt``; **both must reproduce the worked examples in
``docs/phase-13-allocation-contract.md`` exactly, to the minor unit.** If a rule
changes, change the contract doc first, then both engines + their tests
together.

Nothing here is persisted. Fills, states, arrears and credit are recomputed on
every call from the active schedule's installments and the unit's payments — so
back-dated / corrected payments and re-poured renegotiations self-correct.

All amounts are integer minor units. No floating point, no rounding, no
division. See the contract doc for the algorithm and the §3 state machine.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from enum import StrEnum


class InstallmentState(StrEnum):
    UNPAID = "unpaid"
    PARTIAL = "partial"
    FILLED = "filled"
    FILLED_LATE = "filled_late"
    FILLED_AHEAD = "filled_ahead"


@dataclass(frozen=True)
class Installment:
    """One line of the active schedule version. ``id`` is the stable identity an
    earmark may target (an earmark whose target id is not in the active version
    no longer binds — contract §7.1)."""

    id: str
    sequence: int
    amount_minor: int
    due_date: date


@dataclass(frozen=True)
class Payment:
    """A money-in transaction linked to the unit. ``earmarked_installment_id`` is
    the only stored allocation intent; ``None`` means pure waterfall."""

    id: str
    amount_minor: int
    date: date
    earmarked_installment_id: str | None = None


@dataclass(frozen=True)
class InstallmentFill:
    sequence: int
    installment_id: str
    due_date: date
    scheduled_minor: int
    filled_minor: int
    shortfall_minor: int
    on_time_coverage_minor: int
    state: InstallmentState


@dataclass(frozen=True)
class AllocationResult:
    fills: list[InstallmentFill]
    credit_minor: int
    arrears_minor: int


def allocate(
    installments: list[Installment],
    payments: list[Payment],
    as_of: date,
) -> AllocationResult:
    """Pour ``payments`` into the active schedule's ``installments`` as of
    ``as_of``. See the contract doc for the precise algorithm."""

    # §1.1 deterministic sort.
    insts = sorted(installments, key=lambda i: (i.due_date, i.sequence))
    pays = sorted(payments, key=lambda p: (p.date, p.id))
    n = len(insts)

    filled = [0] * n
    # Per-installment contributions as (amount, payment_date) for the
    # order-independent on-time-coverage rule (§3).
    contribs: list[list[tuple[int, date]]] = [[] for _ in range(n)]
    index_by_id = {inst.id: idx for idx, inst in enumerate(insts)}
    credit = 0

    def pour_forward(start_idx: int, amount: int, pdate: date) -> None:
        """Fill from ``start_idx`` forward across still-unfilled installments,
        oldest-first; leftover after the last line becomes credit."""
        nonlocal credit
        remaining = amount
        idx = start_idx
        while remaining > 0 and idx < n:
            room = insts[idx].amount_minor - filled[idx]
            if room > 0:
                applied = min(room, remaining)
                filled[idx] += applied
                contribs[idx].append((applied, pdate))
                remaining -= applied
            idx += 1
        if remaining > 0:
            credit += remaining

    # Pass 1 — earmarks. A valid earmark fills its target first, overflow
    # cascades forward. An earmark whose target is not in the active version
    # falls through to the waterfall (historical intent only).
    waterfall: list[Payment] = []
    for p in pays:
        target_idx = (
            index_by_id.get(p.earmarked_installment_id)
            if p.earmarked_installment_id is not None
            else None
        )
        if target_idx is None:
            waterfall.append(p)
            continue
        pour_forward(target_idx, p.amount_minor, p.date)

    # Pass 2 — waterfall. Pour each remaining payment oldest-unfilled-first.
    for p in waterfall:
        pour_forward(0, p.amount_minor, p.date)

    fills: list[InstallmentFill] = []
    arrears = 0
    for idx, inst in enumerate(insts):
        scheduled = inst.amount_minor
        amount_filled = filled[idx]
        on_time = sum(amt for amt, d in contribs[idx] if d <= inst.due_date)
        shortfall = max(0, scheduled - amount_filled)

        if amount_filled == 0:
            state = InstallmentState.UNPAID
        elif amount_filled < scheduled:
            state = InstallmentState.PARTIAL
        elif inst.due_date > as_of:
            state = InstallmentState.FILLED_AHEAD
        elif on_time >= scheduled:
            state = InstallmentState.FILLED
        else:
            state = InstallmentState.FILLED_LATE

        if inst.due_date <= as_of:
            arrears += shortfall

        fills.append(
            InstallmentFill(
                sequence=inst.sequence,
                installment_id=inst.id,
                due_date=inst.due_date,
                scheduled_minor=scheduled,
                filled_minor=amount_filled,
                shortfall_minor=shortfall,
                on_time_coverage_minor=on_time,
                state=state,
            )
        )

    return AllocationResult(fills=fills, credit_minor=credit, arrears_minor=arrears)
