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


settings = Settings()
