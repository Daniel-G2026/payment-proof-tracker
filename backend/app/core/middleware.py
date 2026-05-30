"""ASGI middleware: security headers + structured request logging (Phase 12)."""

from __future__ import annotations

import logging
import time
import uuid

from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import Response

from app.core.config import settings

log = logging.getLogger("app.request")


class SecurityHeadersMiddleware(BaseHTTPMiddleware):
    """Add defensive response headers on every response.

    `nosniff` matters most for the signed proof-content route, which streams
    user-supplied image bytes — it stops a browser from re-interpreting them
    as markup. HSTS is only emitted outside development so a local HTTP dev
    server doesn't get pinned to https by the browser.
    """

    async def dispatch(self, request: Request, call_next) -> Response:
        response = await call_next(request)
        response.headers.setdefault("X-Content-Type-Options", "nosniff")
        response.headers.setdefault("X-Frame-Options", "DENY")
        response.headers.setdefault("Referrer-Policy", "no-referrer")
        if settings.environment != "development":
            response.headers.setdefault(
                "Strict-Transport-Security", "max-age=63072000; includeSubDomains"
            )
        return response


class RequestLogMiddleware(BaseHTTPMiddleware):
    """Emit one structured log line per request and echo an X-Request-ID.

    A caller-supplied `X-Request-ID` is preserved so a request can be traced
    across the mobile client, proxy, and backend; otherwise we mint one.
    """

    async def dispatch(self, request: Request, call_next) -> Response:
        request_id = request.headers.get("x-request-id") or uuid.uuid4().hex
        start = time.perf_counter()
        try:
            response = await call_next(request)
        except Exception:
            duration_ms = round((time.perf_counter() - start) * 1000, 2)
            log.exception(
                "request failed",
                extra={
                    "request_id": request_id,
                    "method": request.method,
                    "path": request.url.path,
                    "duration_ms": duration_ms,
                },
            )
            raise
        duration_ms = round((time.perf_counter() - start) * 1000, 2)
        response.headers["X-Request-ID"] = request_id
        log.info(
            "request",
            extra={
                "request_id": request_id,
                "method": request.method,
                "path": request.url.path,
                "status_code": response.status_code,
                "duration_ms": duration_ms,
            },
        )
        return response
