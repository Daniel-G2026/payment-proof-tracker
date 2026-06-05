"""ISO-4217 currency allowlist + a reusable Pydantic validator.

Kept deliberately small: NGN (home market) plus the major and African/regional
codes a real user might actually pick. An unknown or malformed code is rejected
with 422 rather than silently stored — this closes the hole where a value like
``{"currency": "<script>"}`` would sit in the DB until a future web dashboard
renders it.
"""

from __future__ import annotations

ALLOWED_CURRENCIES: frozenset[str] = frozenset(
    {
        # Home + African / regional
        "NGN", "GHS", "KES", "ZAR", "TZS", "UGX", "RWF", "XOF", "XAF", "EGP", "MAD",
        # Major global
        "USD", "EUR", "GBP", "CAD", "AUD", "CHF", "JPY", "CNY", "INR", "AED", "SAR",
    }
)


def validate_currency(value: str | None) -> str | None:
    """Normalize to upper-case and assert membership. ``None`` passes through
    (used by PATCH schemas where currency is optional)."""
    if value is None:
        return None
    code = value.strip().upper()
    if code not in ALLOWED_CURRENCIES:
        raise ValueError(f"Unsupported currency '{value}'. Use a supported ISO-4217 code.")
    return code
