from __future__ import annotations

import hashlib
import hmac
import os
import re
import time
import uuid
from pathlib import Path

from fastapi import HTTPException, status

from app.core.config import settings

_DEV_FALLBACK_SECRET = "proofledger-dev-proof-url-secret"
_SAFE_STORAGE_KEY = re.compile(r"^[A-Za-z0-9_\-./]+$")


def _validate_storage_key(storage_key: str) -> None:
    # storage_key is server-built from UUIDs today, but treat it as untrusted
    # since it round-trips through the DB. Refuse anything that could escape
    # the proofs root, regardless of OS path semantics.
    if not storage_key or ".." in storage_key.split("/") or "\\" in storage_key:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Proof not found."
        )
    if not _SAFE_STORAGE_KEY.match(storage_key):
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Proof not found."
        )


class ProofStorage:
    def save(
        self,
        *,
        business_id: uuid.UUID,
        screenshot_id: uuid.UUID,
        content: bytes,
        mime_type: str,
    ) -> str:
        if settings.proof_storage_backend == "s3":
            key = f"{business_id}/{screenshot_id}.jpg"
            self._s3_client().put_object(
                Bucket=settings.proof_storage_s3_bucket,
                Key=key,
                Body=content,
                ContentType=mime_type,
            )
            return key
        if settings.proof_storage_backend != "local":
            raise HTTPException(status_code=500, detail="Proof storage backend is not configured.")
        root = Path(settings.proof_storage_local_dir)
        directory = root / str(business_id)
        directory.mkdir(parents=True, exist_ok=True)
        key = f"{business_id}/{screenshot_id}.jpg"
        path = root / key
        path.write_bytes(content)
        return key.replace("\\", "/")

    def read_bytes(self, storage_key: str) -> bytes:
        """Fetch raw image bytes for a screenshot, regardless of backend.

        Used by the extraction worker to hand the image to the vision model.
        Local backend reads from disk; S3 backend does a GetObject. Raises 404
        via the path-traversal guard if the storage_key is malformed.
        """
        if settings.proof_storage_backend == "s3":
            response = self._s3_client().get_object(
                Bucket=settings.proof_storage_s3_bucket,
                Key=storage_key,
            )
            return response["Body"].read()
        return self.local_path(storage_key).read_bytes()

    def delete(self, storage_key: str) -> None:
        if settings.proof_storage_backend == "s3":
            try:
                self._s3_client().delete_object(
                    Bucket=settings.proof_storage_s3_bucket, Key=storage_key
                )
            except Exception:
                # Best-effort cleanup; don't mask the original error.
                pass
            return
        try:
            path = self.local_path(storage_key)
        except HTTPException:
            return
        try:
            path.unlink(missing_ok=True)
        except OSError:
            pass

    def signed_object_url(self, storage_key: str, expires_in_seconds: int) -> str | None:
        if settings.proof_storage_backend != "s3":
            return None
        return self._s3_client().generate_presigned_url(
            "get_object",
            Params={"Bucket": settings.proof_storage_s3_bucket, "Key": storage_key},
            ExpiresIn=expires_in_seconds,
        )

    def local_path(self, storage_key: str) -> Path:
        _validate_storage_key(storage_key)
        path = Path(settings.proof_storage_local_dir) / storage_key
        root = Path(settings.proof_storage_local_dir).resolve()
        resolved = path.resolve()
        try:
            common = os.path.commonpath([str(root), str(resolved)])
        except ValueError:
            # Different drives on Windows.
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND, detail="Proof not found."
            ) from None
        if common != str(root):
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND, detail="Proof not found."
            )
        return resolved

    def _s3_client(self):
        if not settings.proof_storage_s3_bucket:
            raise HTTPException(status_code=500, detail="Proof storage bucket is not configured.")
        try:
            import boto3
            from botocore.config import Config
        except ImportError as exc:
            raise HTTPException(
                status_code=500,
                detail="S3 proof storage dependency is missing.",
            ) from exc
        return boto3.client(
            "s3",
            endpoint_url=settings.proof_storage_s3_endpoint_url or None,
            region_name=settings.proof_storage_s3_region or None,
            aws_access_key_id=settings.proof_storage_s3_access_key or None,
            aws_secret_access_key=settings.proof_storage_s3_secret_key or None,
            # Force path-style addressing + SigV4 so S3-compatible providers work,
            # not just AWS. Supabase Storage requires path-style (its bucket host
            # isn't a virtual subdomain); Cloudflare R2 and AWS support it too, so
            # this is the safe universal choice.
            config=Config(
                signature_version="s3v4",
                s3={"addressing_style": "path"},
            ),
        )


def signed_url_secret() -> str:
    secret = settings.proof_signed_url_secret or settings.supabase_jwt_secret
    if secret:
        return secret
    # No real secret configured. Only the development environment is allowed to
    # fall back to a known string — anything else means production has been
    # deployed with forgeable proof URLs.
    if settings.environment != "development":
        raise HTTPException(
            status_code=500,
            detail=(
                "Proof URL signing secret is not configured. Set "
                "PROOF_SIGNED_URL_SECRET or SUPABASE_JWT_SECRET."
            ),
        )
    return _DEV_FALLBACK_SECRET


def sign_screenshot_url(screenshot_id: uuid.UUID, expires_at_epoch: int) -> str:
    message = f"{screenshot_id}:{expires_at_epoch}".encode()
    return hmac.new(signed_url_secret().encode(), message, hashlib.sha256).hexdigest()


def verify_screenshot_signature(
    screenshot_id: uuid.UUID,
    expires_at_epoch: int,
    signature: str,
) -> None:
    if expires_at_epoch < int(time.time()):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Proof link has expired.")
    expected = sign_screenshot_url(screenshot_id, expires_at_epoch)
    if not hmac.compare_digest(expected, signature):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Invalid proof link.")
