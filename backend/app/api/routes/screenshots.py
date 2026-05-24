from __future__ import annotations

import re
import time
import uuid
from datetime import UTC, datetime
from email.parser import BytesParser
from email.policy import default
from pathlib import Path
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, Request, Response, status
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.api.deps import CurrentUser, get_db
from app.api.routes.transactions import _get_transaction, _require_member
from app.core.config import settings
from app.db.models import Screenshot
from app.schemas.screenshot import ScreenshotOut, SignedScreenshotUrl
from app.services.proof_storage import (
    ProofStorage,
    sign_screenshot_url,
    verify_screenshot_signature,
)

router = APIRouter(tags=["screenshots"])

MAX_UPLOAD_BYTES = 5 * 1024 * 1024
# Allow a little slack for multipart boundary + form field overhead so we don't
# reject a 5 MB image whose multipart envelope adds a few hundred bytes.
MAX_REQUEST_BYTES = MAX_UPLOAD_BYTES + 64 * 1024
DEFAULT_LIST_LIMIT = 50
MAX_LIST_LIMIT = 200

# Allowlist instead of `startswith("image/")` so we never accept image/svg+xml
# (XML with executable <script> when rendered inline by a future web client) or
# any other "image/..." subtype that browsers happen to interpret as markup.
ALLOWED_IMAGE_MIMES = frozenset({"image/jpeg", "image/png", "image/webp"})

# Cap pixel dimensions to a sane range. Postgres INTEGER tops out at 2.1B, but
# any real screenshot is under 100k px on a side. Without this, a client could
# send width=10_000_000_000 and crash commit with an OverflowError 500.
MAX_PIXEL_DIMENSION = 100_000

DbSession = Annotated[Session, Depends(get_db)]


def _validate_image_payload(content: bytes, declared_mime: str) -> None:
    """Reject non-image bytes and disallowed image subtypes.

    The multipart Content-Type header is client-controlled, so anything that
    relies on it alone (e.g. `startswith("image/")`) trusts the attacker. We
    do both: allowlist the mime AND sniff the magic bytes so a `.exe` labeled
    `image/jpeg` is refused at ingest time rather than sitting in the proof
    store as a phishing payload waiting on a signed URL.
    """
    if declared_mime not in ALLOWED_IMAGE_MIMES:
        raise HTTPException(
            status_code=415,
            detail="Proof must be a JPEG, PNG, or WebP image.",
        )
    if declared_mime == "image/jpeg":
        if not content.startswith(b"\xff\xd8\xff"):
            raise HTTPException(status_code=415, detail="Proof bytes are not a valid JPEG.")
    elif declared_mime == "image/png":
        if not content.startswith(b"\x89PNG\r\n\x1a\n"):
            raise HTTPException(status_code=415, detail="Proof bytes are not a valid PNG.")
    elif declared_mime == "image/webp":
        if len(content) < 12 or content[:4] != b"RIFF" or content[8:12] != b"WEBP":
            raise HTTPException(status_code=415, detail="Proof bytes are not a valid WebP.")


async def _read_capped_body(request: Request) -> bytes:
    # Reject early using Content-Length when present so we never buffer a
    # 100 MB POST before discovering it's too big.
    declared = request.headers.get("content-length")
    if declared is not None:
        try:
            if int(declared) > MAX_REQUEST_BYTES:
                raise HTTPException(
                    status_code=413, detail="Proof image is larger than 5 MB."
                )
        except ValueError:
            pass
    chunks: list[bytes] = []
    total = 0
    async for chunk in request.stream():
        total += len(chunk)
        if total > MAX_REQUEST_BYTES:
            raise HTTPException(status_code=413, detail="Proof image is larger than 5 MB.")
        chunks.append(chunk)
    return b"".join(chunks)


def _parse_multipart(content_type: str, body: bytes) -> tuple[dict[str, str], bytes, str]:
    match = re.search(r"boundary=(?P<boundary>[^;]+)", content_type)
    if match is None:
        raise HTTPException(status_code=415, detail="Expected multipart/form-data upload.")
    message = (
        f"Content-Type: {content_type}\r\nMIME-Version: 1.0\r\n\r\n".encode() + body
    )
    parsed = BytesParser(policy=default).parsebytes(message)
    fields: dict[str, str] = {}
    file_bytes: bytes | None = None
    file_mime = "application/octet-stream"
    for part in parsed.iter_parts():
        name = part.get_param("name", header="content-disposition")
        if not name:
            continue
        payload = part.get_payload(decode=True) or b""
        if name == "file":
            file_bytes = payload
            file_mime = part.get_content_type()
        else:
            fields[name] = payload.decode(errors="ignore")
    if file_bytes is None:
        raise HTTPException(status_code=422, detail="Proof image is required.")
    return fields, file_bytes, file_mime


def _get_screenshot(db: Session, screenshot_id: uuid.UUID) -> Screenshot:
    screenshot = db.get(Screenshot, screenshot_id)
    if screenshot is None or screenshot.deleted_at is not None:
        raise HTTPException(status_code=404, detail="Screenshot not found.")
    return screenshot


def _parse_uuid_field(fields: dict[str, str], name: str) -> uuid.UUID:
    try:
        return uuid.UUID(fields[name])
    except (KeyError, ValueError) as exc:
        raise HTTPException(status_code=422, detail=f"{name} must be a valid UUID.") from exc


def _parse_optional_int_field(fields: dict[str, str], name: str) -> int | None:
    value = fields.get(name)
    if value is None or value == "":
        return None
    try:
        return int(value)
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=f"{name} must be a number.") from exc


def _parse_pixel_dimension(fields: dict[str, str], name: str) -> int | None:
    value = _parse_optional_int_field(fields, name)
    if value is None:
        return None
    if value < 0 or value > MAX_PIXEL_DIMENSION:
        raise HTTPException(
            status_code=422,
            detail=f"{name} must be between 0 and {MAX_PIXEL_DIMENSION}.",
        )
    return value


@router.post(
    "/businesses/{business_id}/screenshots",
    response_model=ScreenshotOut,
    status_code=status.HTTP_200_OK,
)
async def upload_screenshot(
    business_id: uuid.UUID,
    request: Request,
    current_user: CurrentUser,
    db: DbSession,
) -> ScreenshotOut:
    _require_member(db, current_user.id, business_id)
    content_type = request.headers.get("content-type", "")
    body = await _read_capped_body(request)
    fields, file_bytes, file_mime = _parse_multipart(content_type, body)
    if len(file_bytes) > MAX_UPLOAD_BYTES:
        raise HTTPException(status_code=413, detail="Proof image is larger than 5 MB.")
    _validate_image_payload(file_bytes, file_mime)

    transaction_id = _parse_uuid_field(fields, "transaction_id")
    transaction = _get_transaction(db, business_id, transaction_id)
    screenshot_id = (
        _parse_uuid_field(fields, "id")
        if fields.get("id")
        else uuid.uuid4()
    )
    existing = db.get(Screenshot, screenshot_id)
    if existing is not None:
        if existing.business_id != business_id or existing.deleted_at is not None:
            raise HTTPException(status_code=404, detail="Screenshot not found.")
        return ScreenshotOut.model_validate(existing)

    storage = ProofStorage()
    storage_key = storage.save(
        business_id=business_id,
        screenshot_id=screenshot_id,
        content=file_bytes,
        mime_type=file_mime,
    )
    screenshot = Screenshot(
        id=screenshot_id,
        business_id=business_id,
        transaction_id=transaction.id,
        uploaded_by=current_user.id,
        mime_type=file_mime,
        uploaded_file_size=len(file_bytes),
        original_file_size=_parse_optional_int_field(fields, "original_file_size")
        or len(file_bytes),
        width=_parse_pixel_dimension(fields, "width"),
        height=_parse_pixel_dimension(fields, "height"),
        storage_key=storage_key,
        storage_status="stored",
    )
    db.add(screenshot)
    try:
        db.commit()
    except IntegrityError:
        # Concurrent insert raced us. The storage key is deterministic for a
        # given screenshot_id, so the winning row already references the same
        # file we just wrote (we either matched or overwrote its bytes).
        # Don't delete it — the winner still needs it.
        db.rollback()
        winner = db.get(Screenshot, screenshot_id)
        if winner is None or winner.business_id != business_id or winner.deleted_at is not None:
            raise HTTPException(status_code=409, detail="Screenshot conflict.") from None
        return ScreenshotOut.model_validate(winner)
    except Exception:
        # Real failure with no surviving row. The bytes on disk are an
        # orphan; drop them.
        db.rollback()
        storage.delete(storage_key)
        raise
    db.refresh(screenshot)
    return ScreenshotOut.model_validate(screenshot)


@router.get("/businesses/{business_id}/screenshots", response_model=list[ScreenshotOut])
def list_screenshots(
    business_id: uuid.UUID,
    current_user: CurrentUser,
    db: DbSession,
    limit: int = Query(default=DEFAULT_LIST_LIMIT, ge=1, le=MAX_LIST_LIMIT),
    offset: int = Query(default=0, ge=0),
) -> list[ScreenshotOut]:
    _require_member(db, current_user.id, business_id)
    stmt = (
        select(Screenshot)
        .where(
            Screenshot.business_id == business_id,
            Screenshot.deleted_at.is_(None),
        )
        .order_by(Screenshot.created_at.desc(), Screenshot.id)
        .limit(limit)
        .offset(offset)
    )
    return [ScreenshotOut.model_validate(row) for row in db.execute(stmt).scalars().all()]


@router.get("/screenshots/{screenshot_id}", response_model=ScreenshotOut)
def get_screenshot(
    screenshot_id: uuid.UUID,
    current_user: CurrentUser,
    db: DbSession,
) -> ScreenshotOut:
    screenshot = _get_screenshot(db, screenshot_id)
    _require_member(db, current_user.id, screenshot.business_id)
    return ScreenshotOut.model_validate(screenshot)


@router.delete("/screenshots/{screenshot_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_screenshot(
    screenshot_id: uuid.UUID,
    current_user: CurrentUser,
    db: DbSession,
) -> None:
    screenshot = _get_screenshot(db, screenshot_id)
    _require_member(db, current_user.id, screenshot.business_id)
    screenshot.deleted_at = datetime.now(tz=UTC)
    db.commit()


@router.get("/screenshots/{screenshot_id}/signed-url", response_model=SignedScreenshotUrl)
def get_screenshot_signed_url(
    screenshot_id: uuid.UUID,
    request: Request,
    current_user: CurrentUser,
    db: DbSession,
) -> SignedScreenshotUrl:
    screenshot = _get_screenshot(db, screenshot_id)
    _require_member(db, current_user.id, screenshot.business_id)
    storage_url = ProofStorage().signed_object_url(
        screenshot.storage_key,
        settings.proof_signed_url_expiry_seconds,
    )
    expires_at_epoch = int(time.time()) + settings.proof_signed_url_expiry_seconds
    if storage_url is not None:
        return SignedScreenshotUrl(
            url=storage_url,
            expires_at=datetime.fromtimestamp(expires_at_epoch, tz=UTC),
        )
    signature = sign_screenshot_url(screenshot_id, expires_at_epoch)
    base_url = (
        settings.proof_storage_public_base_url.strip().rstrip("/")
        or str(request.base_url).rstrip("/")
    )
    return SignedScreenshotUrl(
        url=f"{base_url}/screenshots/{screenshot_id}/content?expires={expires_at_epoch}&signature={signature}",
        expires_at=datetime.fromtimestamp(expires_at_epoch, tz=UTC),
    )


@router.get("/screenshots/{screenshot_id}/content")
def get_screenshot_content(
    screenshot_id: uuid.UUID,
    db: DbSession,
    expires: int = Query(...),
    signature: str = Query(...),
) -> Response:
    verify_screenshot_signature(screenshot_id, expires, signature)
    screenshot = _get_screenshot(db, screenshot_id)
    path: Path = ProofStorage().local_path(screenshot.storage_key)
    if not path.exists():
        raise HTTPException(status_code=404, detail="Proof file not found.")
    return Response(content=path.read_bytes(), media_type=screenshot.mime_type)
