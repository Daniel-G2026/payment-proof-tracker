"""Phase 13 Cut 2 — the allocation engine asserts the Cut 0 worked examples.

Every example here mirrors ``docs/phase-13-allocation-contract.md`` §5–§6 to the
minor unit. The Android ``AllocationEngineTest.kt`` asserts the identical
numbers. If one side changes, change the contract doc first, then both.

Amounts are integer minor units: ₦500,000 == 50_000_000.
"""

from __future__ import annotations

from datetime import date

from app.services.allocation import (
    AllocationResult,
    Installment,
    InstallmentState,
    Payment,
    allocate,
)

# Canonical schedule S1: total ₦2,000,000, four equal ₦500,000 installments.
NAIRA = 100
I1_DUE = date(2026, 1, 31)
I2_DUE = date(2026, 2, 28)
I3_DUE = date(2026, 3, 31)
I4_DUE = date(2026, 4, 30)


def s1() -> list[Installment]:
    return [
        Installment(id="i1", sequence=1, amount_minor=500_000 * NAIRA, due_date=I1_DUE),
        Installment(id="i2", sequence=2, amount_minor=500_000 * NAIRA, due_date=I2_DUE),
        Installment(id="i3", sequence=3, amount_minor=500_000 * NAIRA, due_date=I3_DUE),
        Installment(id="i4", sequence=4, amount_minor=500_000 * NAIRA, due_date=I4_DUE),
    ]


def _by_seq(result: AllocationResult) -> dict[int, object]:
    return {fill.sequence: fill for fill in result.fills}


def _assert(fill, state: InstallmentState, filled: int, shortfall: int) -> None:
    assert fill.state == state, f"seq {fill.sequence}: {fill.state} != {state}"
    assert fill.filled_minor == filled * NAIRA, f"seq {fill.sequence} filled"
    assert fill.shortfall_minor == shortfall * NAIRA, f"seq {fill.sequence} shortfall"


# --------------------------------------------------------------------------- #
# Example A — 300k partial
# --------------------------------------------------------------------------- #
def test_example_a_partial_under_payment():
    payments = [Payment(id="p1", amount_minor=300_000 * NAIRA, date=date(2026, 1, 20))]
    result = allocate(s1(), payments, as_of=date(2026, 2, 1))
    f = _by_seq(result)
    _assert(f[1], InstallmentState.PARTIAL, filled=300_000, shortfall=200_000)
    _assert(f[2], InstallmentState.UNPAID, filled=0, shortfall=500_000)
    _assert(f[3], InstallmentState.UNPAID, filled=0, shortfall=500_000)
    _assert(f[4], InstallmentState.UNPAID, filled=0, shortfall=500_000)
    assert result.credit_minor == 0
    assert result.arrears_minor == 200_000 * NAIRA


# --------------------------------------------------------------------------- #
# Example B — 800k cascade (two as_of variants)
# --------------------------------------------------------------------------- #
def test_example_b1_cascade_before_i2_due():
    payments = [Payment(id="p1", amount_minor=800_000 * NAIRA, date=date(2026, 1, 25))]
    result = allocate(s1(), payments, as_of=date(2026, 2, 15))
    f = _by_seq(result)
    _assert(f[1], InstallmentState.FILLED, filled=500_000, shortfall=0)
    _assert(f[2], InstallmentState.PARTIAL, filled=300_000, shortfall=200_000)
    _assert(f[3], InstallmentState.UNPAID, filled=0, shortfall=500_000)
    _assert(f[4], InstallmentState.UNPAID, filled=0, shortfall=500_000)
    assert result.credit_minor == 0
    # I2 not yet due -> excluded from arrears.
    assert result.arrears_minor == 0


def test_example_b2_cascade_after_i2_due():
    payments = [Payment(id="p1", amount_minor=800_000 * NAIRA, date=date(2026, 1, 25))]
    result = allocate(s1(), payments, as_of=date(2026, 3, 1))
    f = _by_seq(result)
    _assert(f[2], InstallmentState.PARTIAL, filled=300_000, shortfall=200_000)
    assert result.arrears_minor == 200_000 * NAIRA


# --------------------------------------------------------------------------- #
# Example C — 1m bulk catch-up, late (two as_of variants)
# --------------------------------------------------------------------------- #
def test_example_c1_bulk_late_before_i3_due():
    payments = [
        Payment(id="p1", amount_minor=300_000 * NAIRA, date=date(2026, 1, 20)),
        Payment(id="p2", amount_minor=1_000_000 * NAIRA, date=date(2026, 3, 15)),
    ]
    result = allocate(s1(), payments, as_of=date(2026, 3, 20))
    f = _by_seq(result)
    _assert(f[1], InstallmentState.FILLED_LATE, filled=500_000, shortfall=0)
    _assert(f[2], InstallmentState.FILLED_LATE, filled=500_000, shortfall=0)
    _assert(f[3], InstallmentState.PARTIAL, filled=300_000, shortfall=200_000)
    _assert(f[4], InstallmentState.UNPAID, filled=0, shortfall=500_000)
    assert result.credit_minor == 0
    assert result.arrears_minor == 0  # I3 due 03-31 > as_of


def test_example_c2_bulk_late_after_i3_due():
    payments = [
        Payment(id="p1", amount_minor=300_000 * NAIRA, date=date(2026, 1, 20)),
        Payment(id="p2", amount_minor=1_000_000 * NAIRA, date=date(2026, 3, 15)),
    ]
    result = allocate(s1(), payments, as_of=date(2026, 4, 1))
    f = _by_seq(result)
    _assert(f[3], InstallmentState.PARTIAL, filled=300_000, shortfall=200_000)
    assert result.arrears_minor == 200_000 * NAIRA


# --------------------------------------------------------------------------- #
# Example D — back-dated payment self-corrects (un-lates I1)
# --------------------------------------------------------------------------- #
def test_example_d_backdated_self_corrects():
    # Before the back-date: bulk only.
    bulk_only = allocate(
        s1(),
        [Payment(id="p2", amount_minor=1_000_000 * NAIRA, date=date(2026, 3, 15))],
        as_of=date(2026, 3, 20),
    )
    before = _by_seq(bulk_only)
    _assert(before[1], InstallmentState.FILLED_LATE, filled=500_000, shortfall=0)
    _assert(before[2], InstallmentState.FILLED_LATE, filled=500_000, shortfall=0)

    # Add a back-dated payment that actually arrived on time.
    payments = [
        Payment(id="p0", amount_minor=600_000 * NAIRA, date=date(2026, 1, 15)),
        Payment(id="p2", amount_minor=1_000_000 * NAIRA, date=date(2026, 3, 15)),
    ]
    result = allocate(s1(), payments, as_of=date(2026, 3, 20))
    f = _by_seq(result)
    _assert(f[1], InstallmentState.FILLED, filled=500_000, shortfall=0)  # un-lated
    _assert(f[2], InstallmentState.FILLED_LATE, filled=500_000, shortfall=0)
    _assert(f[3], InstallmentState.FILLED_AHEAD, filled=500_000, shortfall=0)
    _assert(f[4], InstallmentState.PARTIAL, filled=100_000, shortfall=400_000)
    assert result.credit_minor == 0
    assert result.arrears_minor == 0


# --------------------------------------------------------------------------- #
# Example E — earmark beats oldest-first
# --------------------------------------------------------------------------- #
def test_example_e_earmark_beats_oldest_first():
    payments = [
        Payment(
            id="p1",
            amount_minor=500_000 * NAIRA,
            date=date(2026, 2, 10),
            earmarked_installment_id="i3",
        )
    ]
    result = allocate(s1(), payments, as_of=date(2026, 2, 15))
    f = _by_seq(result)
    _assert(f[1], InstallmentState.UNPAID, filled=0, shortfall=500_000)
    _assert(f[2], InstallmentState.UNPAID, filled=0, shortfall=500_000)
    _assert(f[3], InstallmentState.FILLED_AHEAD, filled=500_000, shortfall=0)
    _assert(f[4], InstallmentState.UNPAID, filled=0, shortfall=500_000)
    # I1 is behind despite the buyer having paid — the earmark directed it to I3.
    assert result.arrears_minor == 500_000 * NAIRA


def test_example_e_contrast_no_earmark_clears_arrears():
    payments = [Payment(id="p1", amount_minor=500_000 * NAIRA, date=date(2026, 2, 10))]
    result = allocate(s1(), payments, as_of=date(2026, 2, 15))
    f = _by_seq(result)
    # The 02-10 payment lands on I1 (due 01-31), so it is late under the rule —
    # but arrears is 0 because the line is now filled. That arrears contrast
    # (0 vs the earmark case's 500k) is the point.
    _assert(f[1], InstallmentState.FILLED_LATE, filled=500_000, shortfall=0)
    assert result.arrears_minor == 0


# --------------------------------------------------------------------------- #
# Example F — earmark drops on renegotiation (re-pour into active v2)
# --------------------------------------------------------------------------- #
def v2() -> list[Installment]:
    return [
        Installment(id="j1", sequence=1, amount_minor=400_000 * NAIRA, due_date=date(2026, 2, 28)),
        Installment(id="j2", sequence=2, amount_minor=800_000 * NAIRA, due_date=date(2026, 4, 30)),
        Installment(id="j3", sequence=3, amount_minor=800_000 * NAIRA, due_date=date(2026, 6, 30)),
    ]


def test_example_f_earmark_drops_on_renegotiation():
    # P1 earmarked v1's I3, which is not in active v2 -> earmark no longer binds.
    payments = [
        Payment(
            id="p1",
            amount_minor=500_000 * NAIRA,
            date=date(2026, 2, 10),
            earmarked_installment_id="i3",
        )
    ]
    result = allocate(v2(), payments, as_of=date(2026, 3, 1))
    f = _by_seq(result)
    _assert(f[1], InstallmentState.FILLED, filled=400_000, shortfall=0)
    _assert(f[2], InstallmentState.PARTIAL, filled=100_000, shortfall=700_000)
    _assert(f[3], InstallmentState.UNPAID, filled=0, shortfall=800_000)
    assert result.credit_minor == 0
    assert result.arrears_minor == 0  # J1 filled; J2/J3 not yet due


# --------------------------------------------------------------------------- #
# Example G — overpayment surfaces as credit
# --------------------------------------------------------------------------- #
def test_example_g_overpayment_credit():
    # Paid 2026-01-15 (before every due date) so each line is on-time FILLED;
    # as_of after the last due date so none are FILLED_AHEAD. Surfaces credit.
    payments = [Payment(id="p1", amount_minor=2_500_000 * NAIRA, date=date(2026, 1, 15))]
    result = allocate(s1(), payments, as_of=date(2026, 5, 1))
    f = _by_seq(result)
    for seq in (1, 2, 3, 4):
        _assert(f[seq], InstallmentState.FILLED, filled=500_000, shortfall=0)
    assert result.credit_minor == 500_000 * NAIRA
    assert result.arrears_minor == 0


# --------------------------------------------------------------------------- #
# Edge — no active schedule: all money becomes credit, arrears 0
# --------------------------------------------------------------------------- #
def test_no_schedule_all_credit():
    payments = [Payment(id="p1", amount_minor=300_000 * NAIRA, date=date(2026, 1, 20))]
    result = allocate([], payments, as_of=date(2026, 2, 1))
    assert result.fills == []
    assert result.credit_minor == 300_000 * NAIRA
    assert result.arrears_minor == 0
