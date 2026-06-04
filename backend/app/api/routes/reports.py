from __future__ import annotations

import uuid
from datetime import date
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, Response, status
from sqlalchemy import case, func, select
from sqlalchemy.orm import Session

from app.api.deps import CurrentUser, get_db
from app.db.models import Business, BusinessMember, Screenshot, Transaction
from app.services.audit import add_audit_event
from app.services.report_pdf import (
    ReportRow,
    build_report_insight,
    build_report_pdf,
    previous_period_range,
)

router = APIRouter(prefix="/businesses/{business_id}", tags=["reports"])


def _require_member(db: Session, profile_id: uuid.UUID, business_id: uuid.UUID) -> Business:
    business = db.get(Business, business_id)
    if business is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Business not found.")
    membership = db.execute(
        select(BusinessMember).where(
            BusinessMember.business_id == business_id,
            BusinessMember.user_id == profile_id,
        )
    ).scalar_one_or_none()
    if membership is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Business not found.")
    return business


@router.get("/export.pdf")
def export_pdf(
    business_id: uuid.UUID,
    current_user: CurrentUser,
    start_date: Annotated[date, Query(description="Inclusive ISO start date.")],
    end_date: Annotated[date, Query(description="Inclusive ISO end date.")],
    db: Annotated[Session, Depends(get_db)],
) -> Response:
    """Generate a PDF statement of records in [start_date, end_date] (inclusive).

    The Android client resolves a whole week/month to explicit dates before
    calling, so there's no period= shortcut. PDF is online-only on the client;
    CSV/summary are computed offline on-device, so there is intentionally no
    backend /summary or /export.csv here (deferred — web-dashboard only).
    """
    business = _require_member(db, current_user.id, business_id)
    if end_date < start_date:
        raise HTTPException(
            status_code=422,
            detail="end_date must not be before start_date.",
        )

    rows_stmt = (
        select(Transaction)
        .where(
            Transaction.business_id == business_id,
            Transaction.deleted_at.is_(None),
            Transaction.transaction_date >= start_date,
            Transaction.transaction_date <= end_date,
        )
        .order_by(Transaction.transaction_date.asc(), Transaction.created_at.asc())
    )
    transactions = list(db.execute(rows_stmt).scalars().all())
    previous_start_date, previous_end_date = previous_period_range(start_date, end_date)
    previous_transactions = list(
        db.execute(
            select(Transaction)
            .where(
                Transaction.business_id == business_id,
                Transaction.deleted_at.is_(None),
                Transaction.transaction_date >= previous_start_date,
                Transaction.transaction_date <= previous_end_date,
            )
            .order_by(Transaction.transaction_date.asc(), Transaction.created_at.asc())
        )
        .scalars()
        .all()
    )

    # Reuse the counterparties /summary aggregation pattern for the totals so
    # the summary block matches what the client computes locally.
    totals_stmt = select(
        func.coalesce(
            func.sum(
                case((Transaction.direction == "money_in", Transaction.amount_minor), else_=0)
            ),
            0,
        ),
        func.coalesce(
            func.sum(
                case((Transaction.direction == "money_out", Transaction.amount_minor), else_=0)
            ),
            0,
        ),
    ).where(
        Transaction.business_id == business_id,
        Transaction.deleted_at.is_(None),
        Transaction.transaction_date >= start_date,
        Transaction.transaction_date <= end_date,
    )
    money_in, money_out = db.execute(totals_stmt).one()

    rows = [
        ReportRow(
            id=str(t.id),
            transaction_date=t.transaction_date,
            direction=t.direction,
            counterparty=t.counterparty_name or "",
            category=t.category or "",
            amount_minor=t.amount_minor,
            reference=t.reference_number or "",
        )
        for t in transactions
    ]
    previous_rows = [
        ReportRow(
            id=str(t.id),
            transaction_date=t.transaction_date,
            direction=t.direction,
            counterparty=t.counterparty_name or "",
            category=t.category or "",
            amount_minor=t.amount_minor,
            reference=t.reference_number or "",
        )
        for t in previous_transactions
    ]

    transaction_ids = [t.id for t in transactions]
    proof_transaction_ids = set()
    if transaction_ids:
        proof_transaction_ids = {
            str(transaction_id)
            for transaction_id in db.execute(
                select(Screenshot.transaction_id)
                .where(
                    Screenshot.business_id == business_id,
                    Screenshot.deleted_at.is_(None),
                    Screenshot.transaction_id.is_not(None),
                    Screenshot.transaction_id.in_(transaction_ids),
                )
                .distinct()
            )
            .scalars()
            .all()
        }
    insight = build_report_insight(
        rows=rows,
        previous_rows=previous_rows,
        start_date=start_date,
        end_date=end_date,
        proof_transaction_ids=proof_transaction_ids,
    )

    pdf_bytes = build_report_pdf(
        business_name=business.name,
        start_date=start_date,
        end_date=end_date,
        money_in_minor=int(money_in or 0),
        money_out_minor=int(money_out or 0),
        rows=rows,
        insight=insight,
    )

    add_audit_event(
        db,
        business_id=business_id,
        actor_id=current_user.id,
        entity_type="export",
        entity_id=None,
        action="export",
        status="success",
        new_values={
            "format": "pdf",
            "start_date": start_date.isoformat(),
            "end_date": end_date.isoformat(),
            "row_count": len(rows),
        },
    )
    db.commit()

    filename = f"proofledger-{start_date.isoformat()}-to-{end_date.isoformat()}.pdf"
    return Response(
        content=pdf_bytes,
        media_type="application/pdf",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )
