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


settings = Settings()
