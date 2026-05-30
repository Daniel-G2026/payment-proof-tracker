from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    database_url: str
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

    # ------------------------------------------------------------------
    # Phase 12 — observability, hardening, and deployment.
    # ------------------------------------------------------------------

    # Structured logging. JSON in prod (one object per line, parseable by any
    # log aggregator); flip log_json=false for human-readable dev output.
    log_level: str = "INFO"
    log_json: bool = True

    # Error reporting. Sentry only initializes when a DSN is set, so dev/test
    # never phones home. traces_sample_rate stays 0 unless you want APM spans.
    sentry_dsn: str = ""
    sentry_traces_sample_rate: float = 0.0

    # CORS. Comma-separated allowed origins for a future web dashboard
    # (master-plan §4.7 / §12). Empty disables CORS entirely — mobile clients
    # don't need it. Never set this to "*" in production: only the dashboard
    # origin(s) should be allowed.
    cors_allow_origins: str = ""

    # Per-user HTTP rate limiting (Redis token bucket). Closes the gap where an
    # authenticated client loops POSTs to fill object storage (master-plan
    # §12). Fails *open* if Redis is unreachable so a Redis blip never blocks
    # legitimate uploads. Disabled in the test suite via conftest.
    rate_limit_enabled: bool = True
    rate_limit_upload_per_minute: int = 20

    @property
    def cors_origins_list(self) -> list[str]:
        return [origin.strip() for origin in self.cors_allow_origins.split(",") if origin.strip()]


settings = Settings()
