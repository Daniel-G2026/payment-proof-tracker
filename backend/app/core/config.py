from pydantic import field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    database_url: str

    @field_validator("database_url")
    @classmethod
    def _ensure_psycopg_driver(cls, v: str) -> str:
        """Managed hosts (Render, Heroku, Supabase) hand out connection strings
        as ``postgres://`` or ``postgresql://``, but our synchronous SQLAlchemy
        engine needs the psycopg3 driver. Normalise so any provider's URL works
        out of the box; an explicit ``+driver`` (e.g. the local
        ``postgresql+psycopg://``) is left untouched."""
        if v.startswith("postgres://"):
            v = "postgresql://" + v[len("postgres://"):]
        if v.startswith("postgresql://"):
            v = "postgresql+psycopg://" + v[len("postgresql://"):]
        return v
    redis_url: str = "redis://localhost:6379/0"
    environment: str = "development"

    # Supabase Auth (Phase 2). Backend verifies JWTs symmetrically using the project JWT secret.
    supabase_url: str = ""
    supabase_jwt_secret: str = ""
    supabase_anon_key: str = ""

    proof_storage_backend: str = "local"
    proof_storage_local_dir: str = "storage/proofs"
    proof_storage_public_base_url: str = ""
    proof_storage_s3_endpoint_url: str = ""
    proof_storage_s3_bucket: str = ""
    proof_storage_s3_region: str = ""
    proof_storage_s3_access_key: str = ""
    proof_storage_s3_secret_key: str = ""
    proof_signed_url_secret: str = ""
    proof_signed_url_expiry_seconds: int = 900

    # Phase 8: Server-side OCR/AI extraction. Off-by-default would be safer in
    # prod (no surprise Anthropic spend), but every dev/staging env we deploy
    # to wants this on, so the default is true and prod can flip it to false
    # via env if needed.
    extraction_enabled: bool = True
    anthropic_api_key: str = ""
    extraction_model: str = "claude-haiku-4-5-20251001"
    # Per-user rate limits on enqueueing extraction jobs. Upload itself is
    # never blocked — we just skip enqueueing the draft job when over limit.
    extraction_rate_per_hour: int = 30
    extraction_rate_per_minute: int = 5
    # How long to keep raw_json on the transaction_drafts row before the daily
    # purge nulls it out. The cleaned per-field values are kept forever; this
    # is only about the full model response blob.
    extraction_raw_retention_days: int = 30

    # Phase 12 — per-user rate limit on the screenshot upload route. Reuses the
    # Redis token-bucket; fail-open when Redis is unreachable. 0 disables a
    # given window.
    upload_rate_per_minute: int = 20
    upload_rate_per_hour: int = 200

    # Phase 12 — CORS. Comma-separated allowed origins; empty = no cross-origin
    # (the mobile app sends no Origin header, so default-empty is safe). The
    # future web dashboard's origin goes here. Never "*".
    cors_allowed_origins: str = ""

    # Phase 12 — observability. Empty Sentry DSN = disabled (no-op), so debug /
    # CI builds need no keys. traces_sample_rate is modest to bound cost.
    sentry_dsn: str = ""
    sentry_traces_sample_rate: float = 0.1
    log_level: str = "INFO"


settings = Settings()
