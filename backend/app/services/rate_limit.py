"""Per-user HTTP rate limiting via a Redis fixed-window counter (Phase 12).

The Phase 5 5 MB upload cap blocks single-request abuse, but an authenticated
client can still loop POSTs to fill object storage (master-plan §12). This
adds a per-user-per-route cap on top.

Design mirrors the extraction enqueue limiter in `services/extraction.py`:
INCR + EXPIRE on a windowed key, and it **fails open** — if Redis is
unreachable we allow the request rather than block legitimate traffic on a
Redis blip. The window is fixed (`floor(now / window)`), which is good enough
to stop a runaway client without sliding-window bookkeeping.
"""

from __future__ import annotations

import logging
import time

from fastapi import Depends, HTTPException, Request, status

from app.api.deps import get_current_user
from app.core.config import settings
from app.db.models import Profile

log = logging.getLogger(__name__)


async def check_rate_limit(redis, *, key: str, limit: int, window: int) -> bool:
    """Return True if the caller is at/under `limit` for this window.

    Fails open (returns True) on any Redis error.
    """
    try:
        pipe = redis.pipeline()
        pipe.incr(key)
        pipe.expire(key, window)
        count, _ = await pipe.execute()
    except Exception:
        log.exception("Rate-limit Redis call failed; allowing request")
        return True
    return count <= limit


class RateLimiter:
    """A FastAPI dependency enforcing `limit` requests per `window` seconds.

    Keyed on the authenticated user id and `scope`, so each route gets its own
    budget. No-ops when rate limiting is disabled or no Redis client is wired
    onto `app.state.rate_limit_redis`.
    """

    def __init__(self, *, limit: int, window: int, scope: str) -> None:
        self.limit = limit
        self.window = window
        self.scope = scope

    async def __call__(
        self,
        request: Request,
        current_user: Profile = Depends(get_current_user),
    ) -> None:
        if not settings.rate_limit_enabled:
            return
        redis = getattr(request.app.state, "rate_limit_redis", None)
        if redis is None:
            return
        bucket = int(time.time()) // self.window
        key = f"http:rl:{self.scope}:{current_user.id}:{bucket}"
        allowed = await check_rate_limit(
            redis, key=key, limit=self.limit, window=self.window
        )
        if not allowed:
            raise HTTPException(
                status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                detail="Too many requests. Please slow down and try again shortly.",
                headers={"Retry-After": str(self.window)},
            )


upload_rate_limiter = RateLimiter(
    limit=settings.rate_limit_upload_per_minute,
    window=60,
    scope="upload",
)
