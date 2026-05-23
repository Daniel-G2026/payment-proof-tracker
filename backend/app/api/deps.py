"""Shared FastAPI dependencies. Expands in later phases."""

from app.db.session import get_db

__all__ = ["get_db"]
