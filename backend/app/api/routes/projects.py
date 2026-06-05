from __future__ import annotations

import uuid
from datetime import UTC, datetime

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.api.deps import CurrentUser, get_db
from app.db.models import Business, BusinessMember, Project
from app.schemas.project import ProjectCreate, ProjectOut, ProjectUpdate

router = APIRouter(
    prefix="/businesses/{business_id}/projects",
    tags=["projects"],
)

# Audit events are intentionally skipped for projects in Cut 1, following the
# category precedent: they are structural labels (no money, no proof). The
# audit table stays reserved for transactions/counterparties. Revisit if
# projects ever carry money directly.


def _require_member(db: Session, profile_id: uuid.UUID, business_id: uuid.UUID) -> None:
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


def _get_project(db: Session, business_id: uuid.UUID, project_id: uuid.UUID) -> Project:
    project = db.get(Project, project_id)
    if project is None or project.business_id != business_id or project.deleted_at is not None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Project not found.")
    return project


@router.post("", response_model=ProjectOut, status_code=status.HTTP_200_OK)
def create_project(
    business_id: uuid.UUID,
    payload: ProjectCreate,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
) -> ProjectOut:
    _require_member(db, current_user.id, business_id)

    existing = db.get(Project, payload.id)
    if existing is not None:
        if existing.business_id != business_id or existing.deleted_at is not None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND, detail="Project not found."
            )
        return ProjectOut.model_validate(existing)

    project = Project(
        id=payload.id,
        business_id=business_id,
        name=payload.name.strip(),
        note=payload.note.strip() if payload.note else None,
    )
    db.add(project)
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        winner = db.get(Project, payload.id)
        if winner is None or winner.business_id != business_id or winner.deleted_at is not None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND, detail="Project not found."
            ) from None
        return ProjectOut.model_validate(winner)
    db.refresh(project)
    return ProjectOut.model_validate(project)


@router.get("", response_model=list[ProjectOut])
def list_projects(
    business_id: uuid.UUID,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
) -> list[ProjectOut]:
    _require_member(db, current_user.id, business_id)
    stmt = (
        select(Project)
        .where(Project.business_id == business_id, Project.deleted_at.is_(None))
        .order_by(Project.created_at.asc())
    )
    return [ProjectOut.model_validate(row) for row in db.execute(stmt).scalars().all()]


@router.get("/{project_id}", response_model=ProjectOut)
def get_project(
    business_id: uuid.UUID,
    project_id: uuid.UUID,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
) -> ProjectOut:
    _require_member(db, current_user.id, business_id)
    return ProjectOut.model_validate(_get_project(db, business_id, project_id))


@router.patch("/{project_id}", response_model=ProjectOut)
def update_project(
    business_id: uuid.UUID,
    project_id: uuid.UUID,
    payload: ProjectUpdate,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
) -> ProjectOut:
    _require_member(db, current_user.id, business_id)
    project = _get_project(db, business_id, project_id)
    data = payload.model_dump(exclude_unset=True)
    expected_updated_at = data.pop("expected_updated_at", None)
    if expected_updated_at is not None and project.updated_at != expected_updated_at:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="This record was updated elsewhere. Reload and try again.",
        )
    if "name" in data:
        project.name = data["name"].strip()
    if "note" in data:
        project.note = data["note"].strip() if data["note"] else None
    db.commit()
    db.refresh(project)
    return ProjectOut.model_validate(project)


@router.delete("/{project_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_project(
    business_id: uuid.UUID,
    project_id: uuid.UUID,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
) -> None:
    _require_member(db, current_user.id, business_id)
    project = _get_project(db, business_id, project_id)
    project.deleted_at = datetime.now(UTC)
    db.commit()
