"""Auth placeholder. Real auth lands in Phase 2."""

from fastapi import HTTPException, status


async def get_current_user() -> None:
    raise HTTPException(
        status_code=status.HTTP_501_NOT_IMPLEMENTED,
        detail="Auth not implemented in Phase 1.",
    )
