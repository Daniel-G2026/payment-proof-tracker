"""Supabase Auth — JWT verification.

Supports both signing modes Supabase has shipped:

- **HS256** (legacy): symmetric, verified with the project's JWT secret.
- **ES256** (default for new projects): asymmetric, verified with the project's
  public key fetched from the JWKS endpoint
  `<SUPABASE_URL>/auth/v1/.well-known/jwks.json`. The right key is selected by
  the JWT header's `kid` claim.

PyJWT's PyJWKClient handles fetching, parsing, and caching the JWKS for us.
"""

from __future__ import annotations

from functools import lru_cache

import jwt
from fastapi import HTTPException, status
from jwt import PyJWKClient

from app.core.config import settings


class AuthError(HTTPException):
    def __init__(self, detail: str = "Invalid or missing credentials") -> None:
        super().__init__(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail=detail,
            headers={"WWW-Authenticate": "Bearer"},
        )


@lru_cache(maxsize=1)
def _jwks_client() -> PyJWKClient:
    """JWKS client for ES256 token verification. Cached for the process lifetime."""
    if not settings.supabase_url:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="SUPABASE_URL is not configured.",
        )
    jwks_uri = f"{settings.supabase_url.rstrip('/')}/auth/v1/.well-known/jwks.json"
    return PyJWKClient(jwks_uri)


def verify_supabase_jwt(token: str) -> dict:
    """Decode and validate a Supabase access token (HS256 or ES256).

    Required claims: `sub` (Supabase user id), `aud=authenticated`, `exp`.
    Raises AuthError (401) on any failure.
    """
    try:
        header = jwt.get_unverified_header(token)
    except jwt.InvalidTokenError as exc:
        raise AuthError("Malformed token.") from exc

    alg = header.get("alg")
    try:
        if alg == "HS256":
            if not settings.supabase_jwt_secret:
                raise HTTPException(
                    status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                    detail="SUPABASE_JWT_SECRET is not configured.",
                )
            claims = jwt.decode(
                token,
                settings.supabase_jwt_secret,
                algorithms=["HS256"],
                audience="authenticated",
                options={"require": ["exp", "sub"]},
            )
        elif alg == "ES256":
            signing_key = _jwks_client().get_signing_key_from_jwt(token).key
            claims = jwt.decode(
                token,
                signing_key,
                algorithms=["ES256"],
                audience="authenticated",
                options={"require": ["exp", "sub"]},
            )
        else:
            raise AuthError(f"Unsupported JWT alg: {alg!r}.")
    except jwt.ExpiredSignatureError as exc:
        raise AuthError("Token expired.") from exc
    except jwt.InvalidAudienceError as exc:
        raise AuthError("Invalid token audience.") from exc
    except jwt.InvalidTokenError as exc:
        raise AuthError(f"Invalid token: {exc}") from exc

    if not claims.get("sub"):
        raise AuthError("Token missing subject.")

    return claims
