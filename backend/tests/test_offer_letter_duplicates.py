"""Phase 13 — advisory duplicate detection for scanned offer letters.

Covers POST /businesses/{business_id}/schedule-drafts/duplicate-candidates:
cross-referencing a scanned letter (buyer + installment plan) and the proof
image's bytes against schedules already entered in the business. Mirrors the
Phase 9 transaction duplicate-candidates tests. Schedules/units/counterparties
are inserted directly (no route round-trips) to keep the fixtures small.
"""

from __future__ import annotations

import re
import uuid
from datetime import date, datetime
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app.api.deps import get_current_user, get_db
from app.core.config import settings
from app.db import session as session_module
from app.db.base import Base
from app.db.models import (
    Counterparty,
    Installment,
    Profile,
    Project,
    ScheduleVersion,
    Screenshot,
    Unit,
)
from app.db.models.screenshot import DOCUMENT_TYPE_OFFER_LETTER
from app.main import app

engine = create_engine(
    "sqlite:///:memory:",
    connect_args={"check_same_thread": False},
    poolclass=StaticPool,
)
TestingSessionLocal = sessionmaker(bind=engine, autoflush=False, autocommit=False)

# A clean deposit + two monthly installments; the canonical "Ada Obi" plan.
PLAN_A: list[tuple[int, date]] = [
    (200000000, date(2026, 7, 1)),
    (100000000, date(2026, 8, 1)),
    (100000000, date(2026, 9, 1)),
]
# Same dates, different amounts — a genuinely different schedule.
PLAN_B: list[tuple[int, date]] = [
    (300000000, date(2026, 7, 1)),
    (100000000, date(2026, 8, 1)),
    (100000000, date(2026, 9, 1)),
]


def _normalize(name: str) -> str:
    return re.sub(r"\s+", " ", name.strip()).lower()


def _plan_payload(plan: list[tuple[int, date]]) -> list[dict]:
    return [{"amount_minor": amount, "due_date": due.isoformat()} for amount, due in plan]


@pytest.fixture(autouse=True)
def setup_db(tmp_path: Path):
    settings.environment = "development"
    Base.metadata.create_all(bind=engine)
    original_session_local = session_module.SessionLocal
    session_module.SessionLocal = TestingSessionLocal
    yield
    session_module.SessionLocal = original_session_local
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
    def _build(user_id: uuid.UUID, email: str) -> TestClient:
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


def _create_business(client: TestClient) -> uuid.UUID:
    business_id = uuid.uuid4()
    resp = client.post("/businesses", json={"id": str(business_id), "name": "Estate Co"})
    assert resp.status_code == 200, resp.text
    return business_id


def _insert_project(business_id: uuid.UUID) -> uuid.UUID:
    project_id = uuid.uuid4()
    db = TestingSessionLocal()
    db.add(Project(id=project_id, business_id=business_id, name="Maple Court"))
    db.commit()
    db.close()
    return project_id


def _insert_counterparty(business_id: uuid.UUID, name: str) -> uuid.UUID:
    cp_id = uuid.uuid4()
    db = TestingSessionLocal()
    db.add(
        Counterparty(
            id=cp_id,
            business_id=business_id,
            name=name,
            normalized_name=_normalize(name),
            type="customer",
        )
    )
    db.commit()
    db.close()
    return cp_id


def _insert_unit(
    business_id: uuid.UUID,
    project_id: uuid.UUID,
    label: str,
    buyer_counterparty_id: uuid.UUID | None = None,
) -> uuid.UUID:
    unit_id = uuid.uuid4()
    db = TestingSessionLocal()
    db.add(
        Unit(
            id=unit_id,
            business_id=business_id,
            project_id=project_id,
            label=label,
            buyer_counterparty_id=buyer_counterparty_id,
        )
    )
    db.commit()
    db.close()
    return unit_id


def _insert_screenshot(
    business_id: uuid.UUID,
    user_id: uuid.UUID,
    image_sha256: str | None,
    *,
    deleted: bool = False,
) -> uuid.UUID:
    screenshot_id = uuid.uuid4()
    db = TestingSessionLocal()
    db.add(
        Screenshot(
            id=screenshot_id,
            business_id=business_id,
            transaction_id=None,
            uploaded_by=user_id,
            mime_type="image/jpeg",
            uploaded_file_size=10,
            original_file_size=10,
            storage_key=f"{business_id}/{screenshot_id}.jpg",
            storage_status="stored",
            document_type=DOCUMENT_TYPE_OFFER_LETTER,
            image_sha256=image_sha256,
            deleted_at=datetime(2026, 1, 1) if deleted else None,
        )
    )
    db.commit()
    db.close()
    return screenshot_id


def _insert_schedule(
    business_id: uuid.UUID,
    unit_id: uuid.UUID,
    plan: list[tuple[int, date]],
    *,
    offer_letter_screenshot_id: uuid.UUID | None = None,
    version_no: int = 1,
    deleted: bool = False,
) -> uuid.UUID:
    version_id = uuid.uuid4()
    db = TestingSessionLocal()
    db.add(
        ScheduleVersion(
            id=version_id,
            business_id=business_id,
            unit_id=unit_id,
            version_no=version_no,
            source="offer_letter",
            is_active=not deleted,
            offer_letter_screenshot_id=offer_letter_screenshot_id,
            deleted_at=datetime(2026, 1, 1) if deleted else None,
        )
    )
    for sequence, (amount, due) in enumerate(plan):
        db.add(
            Installment(
                id=uuid.uuid4(),
                business_id=business_id,
                schedule_version_id=version_id,
                sequence=sequence,
                amount_minor=amount,
                due_date=due,
            )
        )
    db.commit()
    db.close()
    return version_id


def _post(
    client: TestClient,
    business_id: uuid.UUID,
    *,
    buyer_name: str | None = None,
    plan: list[tuple[int, date]] | None = None,
    screenshot_id: uuid.UUID | None = None,
    exclude_unit_id: uuid.UUID | None = None,
):
    body: dict = {"installments": _plan_payload(plan or [])}
    if buyer_name is not None:
        body["buyer_name"] = buyer_name
    if screenshot_id is not None:
        body["screenshot_id"] = str(screenshot_id)
    if exclude_unit_id is not None:
        body["exclude_unit_id"] = str(exclude_unit_id)
    return client.post(
        f"/businesses/{business_id}/schedule-drafts/duplicate-candidates", json=body
    )


# --------------------------------------------------------------------------


def test_empty_when_no_schedules(client_for):
    client = client_for(uuid.uuid4(), "a@x.io")
    business_id = _create_business(client)
    resp = _post(client, business_id, buyer_name="Ada Obi", plan=PLAN_A)
    assert resp.status_code == 200, resp.text
    assert resp.json() == []


def test_same_buyer_and_schedule_on_other_unit(client_for):
    client = client_for(uuid.uuid4(), "a@x.io")
    business_id = _create_business(client)
    project_id = _insert_project(business_id)
    buyer = _insert_counterparty(business_id, "Ada Obi")
    other_unit = _insert_unit(business_id, project_id, "House 4", buyer)
    _insert_schedule(business_id, other_unit, PLAN_A)
    current_unit = _insert_unit(business_id, project_id, "House 9")

    resp = _post(
        client,
        business_id,
        buyer_name="Ada Obi",
        plan=PLAN_A,
        exclude_unit_id=current_unit,
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert len(body) == 1
    candidate = body[0]
    assert candidate["reasons"] == ["same_buyer_and_schedule"]
    assert candidate["score"] == 75
    assert candidate["unit_id"] == str(other_unit)
    assert candidate["unit_label"] == "House 4"
    assert candidate["buyer_name"] == "Ada Obi"
    assert candidate["installment_count"] == 3
    assert candidate["total_minor"] == 400000000


def test_same_schedule_different_buyer_does_not_match(client_for):
    client = client_for(uuid.uuid4(), "a@x.io")
    business_id = _create_business(client)
    project_id = _insert_project(business_id)
    buyer = _insert_counterparty(business_id, "Bola Ade")
    unit = _insert_unit(business_id, project_id, "House 4", buyer)
    _insert_schedule(business_id, unit, PLAN_A)

    resp = _post(client, business_id, buyer_name="Ada Obi", plan=PLAN_A)
    assert resp.status_code == 200, resp.text
    assert resp.json() == []


def test_same_buyer_different_schedule_does_not_match(client_for):
    client = client_for(uuid.uuid4(), "a@x.io")
    business_id = _create_business(client)
    project_id = _insert_project(business_id)
    buyer = _insert_counterparty(business_id, "Ada Obi")
    unit = _insert_unit(business_id, project_id, "House 4", buyer)
    _insert_schedule(business_id, unit, PLAN_A)

    # Same buyer, renegotiated to a different plan → not a duplicate.
    resp = _post(client, business_id, buyer_name="Ada Obi", plan=PLAN_B)
    assert resp.status_code == 200, resp.text
    assert resp.json() == []


def test_buyer_name_matched_normalized(client_for):
    client = client_for(uuid.uuid4(), "a@x.io")
    business_id = _create_business(client)
    project_id = _insert_project(business_id)
    buyer = _insert_counterparty(business_id, "  ADA   Obi ")
    unit = _insert_unit(business_id, project_id, "House 4", buyer)
    _insert_schedule(business_id, unit, PLAN_A)

    resp = _post(client, business_id, buyer_name="ada obi", plan=PLAN_A)
    assert resp.status_code == 200, resp.text
    assert len(resp.json()) == 1


def test_installment_plan_match_is_order_independent(client_for):
    client = client_for(uuid.uuid4(), "a@x.io")
    business_id = _create_business(client)
    project_id = _insert_project(business_id)
    buyer = _insert_counterparty(business_id, "Ada Obi")
    unit = _insert_unit(business_id, project_id, "House 4", buyer)
    _insert_schedule(business_id, unit, PLAN_A)

    shuffled = [PLAN_A[2], PLAN_A[0], PLAN_A[1]]
    resp = _post(client, business_id, buyer_name="Ada Obi", plan=shuffled)
    assert resp.status_code == 200, resp.text
    assert len(resp.json()) == 1


def test_exclude_unit_skips_same_unit_buyer_schedule(client_for):
    client = client_for(uuid.uuid4(), "a@x.io")
    business_id = _create_business(client)
    project_id = _insert_project(business_id)
    buyer = _insert_counterparty(business_id, "Ada Obi")
    unit = _insert_unit(business_id, project_id, "House 4", buyer)
    _insert_schedule(business_id, unit, PLAN_A)

    # Re-confirming the same buyer+plan onto the SAME unit (renegotiation) is not
    # flagged when that unit is excluded.
    resp = _post(
        client, business_id, buyer_name="Ada Obi", plan=PLAN_A, exclude_unit_id=unit
    )
    assert resp.status_code == 200, resp.text
    assert resp.json() == []


def test_same_proof_via_image_hash(client_for):
    user_id = uuid.uuid4()
    client = client_for(user_id, "a@x.io")
    business_id = _create_business(client)
    project_id = _insert_project(business_id)
    unit = _insert_unit(business_id, project_id, "House 4")
    linked = _insert_screenshot(business_id, user_id, "hashfeed")
    _insert_schedule(business_id, unit, PLAN_A, offer_letter_screenshot_id=linked)

    # A NEW upload with the SAME bytes (same hash); current unit excluded so only
    # the proof signal can fire.
    incoming = _insert_screenshot(business_id, user_id, "hashfeed")
    resp = _post(
        client,
        business_id,
        buyer_name="Ada Obi",
        plan=PLAN_A,
        screenshot_id=incoming,
        exclude_unit_id=unit,
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert len(body) == 1
    assert body[0]["reasons"] == ["same_proof"]
    assert body[0]["score"] == 100


def test_same_proof_and_buyer_schedule_combine(client_for):
    user_id = uuid.uuid4()
    client = client_for(user_id, "a@x.io")
    business_id = _create_business(client)
    project_id = _insert_project(business_id)
    buyer = _insert_counterparty(business_id, "Ada Obi")
    other_unit = _insert_unit(business_id, project_id, "House 4", buyer)
    linked = _insert_screenshot(business_id, user_id, "samebytes")
    _insert_schedule(business_id, other_unit, PLAN_A, offer_letter_screenshot_id=linked)
    current_unit = _insert_unit(business_id, project_id, "House 9")

    incoming = _insert_screenshot(business_id, user_id, "samebytes")
    resp = _post(
        client,
        business_id,
        buyer_name="Ada Obi",
        plan=PLAN_A,
        screenshot_id=incoming,
        exclude_unit_id=current_unit,
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert len(body) == 1
    assert body[0]["reasons"] == ["same_buyer_and_schedule", "same_proof"]
    assert body[0]["score"] == 175


def test_buyer_name_absent_yields_no_semantic_match(client_for):
    client = client_for(uuid.uuid4(), "a@x.io")
    business_id = _create_business(client)
    project_id = _insert_project(business_id)
    buyer = _insert_counterparty(business_id, "Ada Obi")
    unit = _insert_unit(business_id, project_id, "House 4", buyer)
    _insert_schedule(business_id, unit, PLAN_A)

    # No buyer extracted and no proof → nothing to match on.
    resp = _post(client, business_id, buyer_name=None, plan=PLAN_A)
    assert resp.status_code == 200, resp.text
    assert resp.json() == []


def test_soft_deleted_schedule_excluded(client_for):
    client = client_for(uuid.uuid4(), "a@x.io")
    business_id = _create_business(client)
    project_id = _insert_project(business_id)
    buyer = _insert_counterparty(business_id, "Ada Obi")
    unit = _insert_unit(business_id, project_id, "House 4", buyer)
    _insert_schedule(business_id, unit, PLAN_A, deleted=True)

    resp = _post(client, business_id, buyer_name="Ada Obi", plan=PLAN_A)
    assert resp.status_code == 200, resp.text
    assert resp.json() == []


def test_cross_business_isolation(client_for):
    user_id = uuid.uuid4()
    client = client_for(user_id, "a@x.io")
    business_one = _create_business(client)
    business_two = _create_business(client)
    project_id = _insert_project(business_two)
    buyer = _insert_counterparty(business_two, "Ada Obi")
    unit = _insert_unit(business_two, project_id, "House 4", buyer)
    _insert_schedule(business_two, unit, PLAN_A)

    # The matching schedule lives in business_two; business_one must not see it.
    resp = _post(client, business_one, buyer_name="Ada Obi", plan=PLAN_A)
    assert resp.status_code == 200, resp.text
    assert resp.json() == []


def test_non_member_is_rejected(client_for):
    owner_client = client_for(uuid.uuid4(), "owner@x.io")
    business_id = _create_business(owner_client)
    # A second user who is not a member of the business.
    outsider_client = client_for(uuid.uuid4(), "outsider@x.io")
    resp = _post(outsider_client, business_id, buyer_name="Ada Obi", plan=PLAN_A)
    assert resp.status_code == 404


def test_results_capped_at_five(client_for):
    client = client_for(uuid.uuid4(), "a@x.io")
    business_id = _create_business(client)
    project_id = _insert_project(business_id)
    buyer = _insert_counterparty(business_id, "Ada Obi")
    for i in range(6):
        unit = _insert_unit(business_id, project_id, f"House {i}", buyer)
        _insert_schedule(business_id, unit, PLAN_A)

    resp = _post(client, business_id, buyer_name="Ada Obi", plan=PLAN_A)
    assert resp.status_code == 200, resp.text
    assert len(resp.json()) == 5
