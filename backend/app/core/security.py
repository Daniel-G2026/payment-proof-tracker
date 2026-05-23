"""Auth re-exports. The actual implementation lives in app.core.auth."""

from app.core.auth import AuthError, verify_supabase_jwt

__all__ = ["AuthError", "verify_supabase_jwt"]
