"""Generic per-user / per-route Redis token-bucket rate limiter.

Generalized from the extraction limiter that used to live inline in
`app/services/extraction.py`. Two Redis counters per key (per-minute and
per-hour), each INCR'd with a matching TTL — good enough to stop a runaway
client without sliding-window precision.

Best-effort and **fail-open**: a missing pool or any Redis error lets the
request through rather than blocking a legitimate user on an infra blip.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Any

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class RateLimit:
    """Caps for one bucket. A value of 0 disables that window."""

    per_minute: int
    per_hour: int


async def check_rate_limit(pool: Any | None, *, key: str, limit: RateLimit) -> bool:
    """Return True if `key` is under both caps (allow), False if over (deny).

    Increments both counters even when denying, so a client that keeps
    hammering doesn't get a fresh allowance at the next window boundary.
    `pool` is any object exposing the arq/redis async ``pipeline()`` API;
    ``None`` → allow (limiter not wired / Redis absent). Redis errors → allow.
    """
    if pool is None:
        return True
    minute_key = f"rl:{key}:m"
    hour_key = f"rl:{key}:h"
    try:
        pipe = pool.pipeline()
        pipe.incr(minute_key)
        pipe.expire(minute_key, 60)
        pipe.incr(hour_key)
        pipe.expire(hour_key, 3600)
        minute_count, _, hour_count, _ = await pipe.execute()
    except Exception:
        # Redis hiccup: fail open. Never block a legitimate request on infra.
        log.exception("Rate-limit Redis call failed for %s; allowing request", key)
        return True

    if limit.per_minute and minute_count > limit.per_minute:
        return False
    if limit.per_hour and hour_count > limit.per_hour:
        return False
    return True
