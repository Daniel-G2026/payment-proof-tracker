from __future__ import annotations

import uuid
from datetime import UTC, datetime

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app.api.deps import get_current_user, get_db
from app.db.base import Base
from app.db.models import AuditEvent, Profile, Screenshot
from app.main import app
from app.services.report_pdf import ReportRow, build_report_html, build_report_insight

engine = create_engine(
    "sqlite:///:memory:",
    connect_args={"check_same_thread": False},
    poolclass=StaticPool,
)
TestingSessionLocal = sessionmaker(bind=engine, autoflush=False, autocommit=False)


@pytest.fixture(autouse=True)
def setup_db():
    Base.metadata.create_all(bind=engine)
    yield
    Base.metadata.drop_all(bind=engine)


def _override_db():
    db = TestingSessionLocal()
    try:
        yield db
    finally:
        db.close()


def _make_user(user_id: uuid.UUID, email: str) -> None:
    db = TestingSessionLocal()
    db.add(Profile(id=user_id, email=email))
    db.commit()
    db.close()


@pytest.fixture
def client_for():
    def _build(user_id: uuid.UUID, email: str):
        _make_user(user_id, email)

        def _override_user():
            db = TestingSessionLocal()
            try:
                yield db.get(Profile, user_id)
            finally:
                db.close()

        app.dependency_overrides[get_db] = _override_db
        app.dependency_overrides[get_current_user] = _override_user
        return TestClient(app)

    yield _build
    app.dependency_overrides.clear()


def _create_business(client: TestClient, name: str = "Ada Stores") -> uuid.UUID:
    business_id = uuid.uuid4()
    resp = client.post("/businesses", json={"id": str(business_id), "name": name})
    assert resp.status_code == 200, resp.text
    return business_id


def _create_transaction(client: TestClient, business_id: uuid.UUID, **overrides) -> uuid.UUID:
    transaction_id = uuid.uuid4()
    payload = {
        "id": str(transaction_id),
        "amount_minor": 125000,
        "currency": "NGN",
        "direction": "money_in",
        "transaction_date": "2026-06-10",
        "counterparty_name": "Adewale Supplies",
        "reference_number": "REF-001",
    }
    payload.update(overrides)
    resp = client.post(f"/businesses/{business_id}/transactions", json=payload)
    assert resp.status_code == 200, resp.text
    return uuid.UUID(resp.json()["id"])


def _attach_proof(business_id: uuid.UUID, transaction_id: uuid.UUID, user_id: uuid.UUID) -> None:
    db = TestingSessionLocal()
    try:
        screenshot_id = uuid.uuid4()
        db.add(
            Screenshot(
                id=screenshot_id,
                business_id=business_id,
                transaction_id=transaction_id,
                uploaded_by=user_id,
                mime_type="image/jpeg",
                uploaded_file_size=100,
                original_file_size=100,
                width=100,
                height=200,
                storage_key=f"{business_id}/{screenshot_id}.jpg",
                storage_status="stored",
                image_sha256=None,
                created_at=datetime.now(UTC),
                updated_at=datetime.now(UTC),
                deleted_at=None,
            )
        )
        db.commit()
    finally:
        db.close()


def test_build_report_insight_computes_owner_friendly_sections():
    rows = [
        ReportRow(
            id="in-1",
            transaction_date=datetime(2026, 5, 12, tzinfo=UTC).date(),
            direction="money_in",
            counterparty="Mama Ngozi",
            category="Client payment",
            amount_minor=42000000,
            reference="IN-1",
        ),
        ReportRow(
            id="out-1",
            transaction_date=datetime(2026, 5, 3, tzinfo=UTC).date(),
            direction="money_out",
            counterparty="Lagos Supplies",
            category="Stock / goods",
            amount_minor=30000000,
            reference="OUT-1",
        ),
        ReportRow(
            id="out-2",
            transaction_date=datetime(2026, 5, 5, tzinfo=UTC).date(),
            direction="money_out",
            counterparty="Driver",
            category="Transport",
            amount_minor=9000000,
            reference="OUT-2",
        ),
    ]
    previous_rows = [
        ReportRow(
            id="old-1",
            transaction_date=datetime(2026, 4, 4, tzinfo=UTC).date(),
            direction="money_in",
            counterparty="Old Client",
            category="Client payment",
            amount_minor=20000000,
            reference="OLD-1",
        )
    ]

    insight = build_report_insight(
        rows=rows,
        previous_rows=previous_rows,
        start_date=datetime(2026, 5, 1, tzinfo=UTC).date(),
        end_date=datetime(2026, 5, 31, tzinfo=UTC).date(),
        proof_transaction_ids={"in-1", "out-2"},
    )

    assert insight.prior_period is not None
    assert insight.prior_period.label == "April 2026"
    assert insight.prior_period.amount_minor == -17000000
    assert insight.money_out_categories[0].label == "Stock / goods"
    assert insight.money_out_categories[0].percent == 77
    assert insight.paid_me_most is not None
    assert insight.paid_me_most.label == "Mama Ngozi"
    assert insight.i_paid_most is not None
    assert insight.i_paid_most.label == "Lagos Supplies"
    assert insight.biggest_records[0].id == "in-1"
    assert insight.proof.records_with_proof == 2
    assert insight.proof.percent_with_proof == 67


def test_build_report_html_contains_canva_style_sections():
    rows = [
        ReportRow(
            id="in-1",
            transaction_date=datetime(2026, 5, 12, tzinfo=UTC).date(),
            direction="money_in",
            counterparty="Mama Ngozi",
            category="Client payment",
            amount_minor=42000000,
            reference="IN-1",
        ),
        ReportRow(
            id="out-1",
            transaction_date=datetime(2026, 5, 3, tzinfo=UTC).date(),
            direction="money_out",
            counterparty="Lagos Supplies",
            category="Stock / goods",
            amount_minor=30000000,
            reference="OUT-1",
        ),
    ]
    insight = build_report_insight(
        rows=rows,
        previous_rows=[],
        start_date=datetime(2026, 5, 1, tzinfo=UTC).date(),
        end_date=datetime(2026, 5, 31, tzinfo=UTC).date(),
        proof_transaction_ids={"in-1"},
    )

    html = build_report_html(
        business_name="Adaeze Stores",
        start_date=datetime(2026, 5, 1, tzinfo=UTC).date(),
        end_date=datetime(2026, 5, 31, tzinfo=UTC).date(),
        money_in_minor=42000000,
        money_out_minor=30000000,
        rows=rows,
        insight=insight,
        generated_at=datetime(2026, 6, 3, 15, 29, tzinfo=UTC),
    )

    assert "Business Statement" in html
    assert "Net this period" in html
    assert "Money out by category" in html
    assert "Each line shows how much you paid out in that category." in html
    assert "100% of money out" in html
    assert "Top people" in html
    assert "Biggest records" in html
    assert "All records (evidence)" in html
    assert "Adaeze Stores" in html
    assert "Stock / goods" in html
    assert "1 with attached proof (50%)" in html
    assert "font-family: \"Aptos\"" in html
    assert "--pos: #0f3d2e" in html
    assert "font-size: 38px" in html
    assert 'class="sheet summary-sheet"' in html
    assert 'class="sheet evidence-sheet page-break"' in html
    assert "+ NGN" in html


def test_build_report_html_limits_summary_rows_to_fit_first_page():
    rows = [
        ReportRow(
            id=f"out-{index}",
            transaction_date=datetime(2026, 5, index + 1, tzinfo=UTC).date(),
            direction="money_out",
            counterparty=f"Supplier {index}",
            category=f"Category {index}",
            amount_minor=(index + 1) * 100000,
            reference=f"OUT-{index}",
        )
        for index in range(5)
    ]
    insight = build_report_insight(
        rows=rows,
        previous_rows=[],
        start_date=datetime(2026, 5, 1, tzinfo=UTC).date(),
        end_date=datetime(2026, 5, 31, tzinfo=UTC).date(),
        proof_transaction_ids=set(),
    )

    html = build_report_html(
        business_name="Adaeze Stores",
        start_date=datetime(2026, 5, 1, tzinfo=UTC).date(),
        end_date=datetime(2026, 5, 31, tzinfo=UTC).date(),
        money_in_minor=0,
        money_out_minor=sum(row.amount_minor for row in rows),
        rows=rows,
        insight=insight,
        generated_at=datetime(2026, 6, 3, 15, 29, tzinfo=UTC),
    )

    summary_html = html.split('<section class="sheet evidence-sheet page-break">')[0]

    assert "Top 3" in summary_html
    assert "Category 0" not in summary_html
    assert "Supplier 0" not in summary_html
    assert "Supplier 4" in summary_html


def test_build_report_html_styles_negative_net_as_black():
    rows = [
        ReportRow(
            id="out-1",
            transaction_date=datetime(2026, 5, 3, tzinfo=UTC).date(),
            direction="money_out",
            counterparty="Lagos Supplies",
            category="Stock / goods",
            amount_minor=30000000,
            reference="OUT-1",
        )
    ]
    insight = build_report_insight(
        rows=rows,
        previous_rows=[],
        start_date=datetime(2026, 5, 1, tzinfo=UTC).date(),
        end_date=datetime(2026, 5, 31, tzinfo=UTC).date(),
        proof_transaction_ids=set(),
    )

    html = build_report_html(
        business_name="Adaeze Stores",
        start_date=datetime(2026, 5, 1, tzinfo=UTC).date(),
        end_date=datetime(2026, 5, 31, tzinfo=UTC).date(),
        money_in_minor=0,
        money_out_minor=30000000,
        rows=rows,
        insight=insight,
        generated_at=datetime(2026, 6, 3, 15, 29, tzinfo=UTC),
    )

    assert 'class="net net-negative"' in html
    assert ".hero .net-negative { color: var(--ink); }" in html
    assert "- NGN" in html


def test_export_pdf_returns_pdf(client_for):
    client = client_for(uuid.uuid4(), "ada@example.com")
    business_id = _create_business(client)
    _create_transaction(client, business_id, direction="money_in", amount_minor=125000)
    _create_transaction(
        client,
        business_id,
        direction="money_out",
        amount_minor=40000,
        transaction_date="2026-06-15",
    )

    resp = client.get(
        f"/businesses/{business_id}/export.pdf",
        params={"start_date": "2026-06-01", "end_date": "2026-06-30"},
    )

    assert resp.status_code == 200, resp.text
    assert resp.headers["content-type"] == "application/pdf"
    assert "attachment" in resp.headers["content-disposition"]
    assert ".pdf" in resp.headers["content-disposition"]
    body = resp.content
    assert len(body) > 0
    assert body.startswith(b"%PDF")


def test_export_pdf_counts_attached_proof(client_for):
    user_id = uuid.uuid4()
    client = client_for(user_id, "ada@example.com")
    business_id = _create_business(client)
    first_id = _create_transaction(client, business_id, direction="money_in", amount_minor=125000)
    _create_transaction(
        client,
        business_id,
        direction="money_out",
        amount_minor=40000,
        transaction_date="2026-06-15",
    )
    _attach_proof(business_id, first_id, user_id)

    resp = client.get(
        f"/businesses/{business_id}/export.pdf",
        params={"start_date": "2026-06-01", "end_date": "2026-06-30"},
    )

    assert resp.status_code == 200, resp.text
    assert resp.content.startswith(b"%PDF")


def test_export_pdf_excludes_soft_deleted_and_out_of_range(client_for):
    client = client_for(uuid.uuid4(), "ada@example.com")
    business_id = _create_business(client)
    # Out-of-range record should not block a valid (empty) export.
    _create_transaction(client, business_id, transaction_date="2026-05-31")

    resp = client.get(
        f"/businesses/{business_id}/export.pdf",
        params={"start_date": "2026-06-01", "end_date": "2026-06-30"},
    )
    assert resp.status_code == 200
    assert resp.content.startswith(b"%PDF")


def test_export_pdf_non_member_gets_404(client_for):
    alice = client_for(uuid.uuid4(), "alice@example.com")
    business_id = _create_business(alice)

    bob = client_for(uuid.uuid4(), "bob@example.com")
    resp = bob.get(
        f"/businesses/{business_id}/export.pdf",
        params={"start_date": "2026-06-01", "end_date": "2026-06-30"},
    )
    assert resp.status_code == 404


def test_export_pdf_writes_audit_event(client_for):
    user_id = uuid.uuid4()
    client = client_for(user_id, "ada@example.com")
    business_id = _create_business(client)
    _create_transaction(client, business_id)

    resp = client.get(
        f"/businesses/{business_id}/export.pdf",
        params={"start_date": "2026-06-01", "end_date": "2026-06-30"},
    )
    assert resp.status_code == 200

    db = TestingSessionLocal()
    try:
        events = (
            db.execute(
                select(AuditEvent).where(
                    AuditEvent.business_id == business_id,
                    AuditEvent.entity_type == "export",
                )
            )
            .scalars()
            .all()
        )
    finally:
        db.close()

    assert len(events) == 1
    event = events[0]
    assert event.action == "export"
    assert event.status == "success"
    assert event.entity_id is None
    assert event.actor_id == user_id
    assert event.new_values == {
        "format": "pdf",
        "start_date": "2026-06-01",
        "end_date": "2026-06-30",
        "row_count": 1,
    }


def test_export_pdf_rejects_inverted_range(client_for):
    client = client_for(uuid.uuid4(), "ada@example.com")
    business_id = _create_business(client)

    resp = client.get(
        f"/businesses/{business_id}/export.pdf",
        params={"start_date": "2026-06-30", "end_date": "2026-06-01"},
    )
    assert resp.status_code == 422
