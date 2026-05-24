import uuid
from datetime import datetime

from sqlalchemy import BigInteger, DateTime, ForeignKey, Integer, String, Uuid, func
from sqlalchemy.orm import Mapped, mapped_column

from app.db.base import Base


class Screenshot(Base):
    __tablename__ = "screenshots"

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True)
    business_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("businesses.id"), nullable=False, index=True
    )
    transaction_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("transactions.id"), nullable=False, index=True
    )
    uploaded_by: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("profiles.id"), nullable=False, index=True
    )
    mime_type: Mapped[str] = mapped_column(String, nullable=False)
    uploaded_file_size: Mapped[int] = mapped_column(BigInteger, nullable=False)
    original_file_size: Mapped[int] = mapped_column(BigInteger, nullable=False)
    width: Mapped[int | None] = mapped_column(Integer, nullable=True)
    height: Mapped[int | None] = mapped_column(Integer, nullable=True)
    storage_key: Mapped[str] = mapped_column(String, nullable=False, unique=True)
    storage_status: Mapped[str] = mapped_column(String, nullable=False, default="stored")
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        server_default=func.now(),
        onupdate=func.now(),
        nullable=False,
    )
    deleted_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
