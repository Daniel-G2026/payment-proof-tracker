"""Vision client wrapper + extraction normalizer.

Calls Claude Haiku 4.5 with the screenshot bytes and a forced tool-use schema
so the response is guaranteed-shape JSON. The system prompt is marked as
prompt-cached: first call in a 5-minute window writes the cache, every call
within the window reads it at ~10% the cost.

The normalizer turns the raw tool input into a `VisionExtraction` dataclass,
dropping fields whose self-reported confidence falls below CONFIDENCE_FLOOR
so we never pre-fill a form with a guess.
"""

from __future__ import annotations

import base64
import logging
from dataclasses import dataclass, field
from datetime import date
from typing import Any, Protocol

from app.core.config import settings

log = logging.getLogger(__name__)

# Anything below this gets dropped before we write the draft row. The vision
# model is told that 1.0 = directly visible, ~0.5 = inferred-but-uncertain,
# <0.5 = "I'm guessing." A wrong pre-fill is more painful than a blank field.
CONFIDENCE_FLOOR = 0.5

EXTRACTION_TOOL_NAME = "record_extraction"

EXTRACTION_TOOL_SCHEMA: dict[str, Any] = {
    "name": EXTRACTION_TOOL_NAME,
    "description": (
        "Record the structured fields you extracted from the payment proof "
        "screenshot. Use null for any field you cannot determine. Set "
        "`unreadable` to true if the image is not a payment proof at all."
    ),
    "input_schema": {
        "type": "object",
        "properties": {
            "amount_minor": {
                "type": ["integer", "null"],
                "description": (
                    "Transaction amount in the smallest currency unit (kobo "
                    "for NGN, cents for USD, etc.). Example: ₦8,500.00 → "
                    "850000. Do not include the currency symbol."
                ),
            },
            "currency_code": {
                "type": ["string", "null"],
                "description": "ISO 4217 currency code if visible (e.g. 'NGN').",
            },
            "transaction_date": {
                "type": ["string", "null"],
                "description": (
                    "ISO-8601 date (YYYY-MM-DD). If only a relative date like "
                    "'Today' or 'Yesterday' is shown, return null."
                ),
            },
            "reference_number": {
                "type": ["string", "null"],
                "description": "Transaction reference / RRN / session ID if shown.",
            },
            "bank_name": {
                "type": ["string", "null"],
                "description": "Name of the bank or payment app, if identifiable.",
            },
            "counterparty_name": {
                "type": ["string", "null"],
                "description": (
                    "Name of the OTHER party in the transaction (the recipient "
                    "for outgoing transfers, the sender for incoming). Do not "
                    "return the account holder's own name."
                ),
            },
            "confidence": {
                "type": "object",
                "description": (
                    "Self-reported confidence per field, 0.0 to 1.0. Keys "
                    "match the field names above. 1.0 = directly visible, "
                    "0.7 = inferred, <0.5 = guess."
                ),
                "properties": {
                    "amount_minor": {"type": "number"},
                    "currency_code": {"type": "number"},
                    "transaction_date": {"type": "number"},
                    "reference_number": {"type": "number"},
                    "bank_name": {"type": "number"},
                    "counterparty_name": {"type": "number"},
                },
            },
            "unreadable": {
                "type": "boolean",
                "description": (
                    "True if the image is not a payment proof (random photo, "
                    "screenshot of unrelated content, blank, etc.)."
                ),
            },
        },
        "required": ["confidence", "unreadable"],
    },
}

# Padded past 1024 tokens so it qualifies for Anthropic prompt caching. Most
# of the budget goes to extraction rules and few-shot patterns — those are
# the parts most likely to drift if we trim. Keep this stable; every edit
# invalidates the cache.
SYSTEM_PROMPT = """You are an extraction engine for payment proof screenshots from small businesses, primarily in Nigeria and other African markets.

Your job: given exactly one image of a bank transfer confirmation, mobile-money receipt, payment app success screen, or e-receipt, extract the structured payment fields. Return your answer via the `record_extraction` tool — never as free text.

# Field rules

**amount_minor** — the transaction amount, in the smallest unit of the currency. For NGN that means kobo (multiply naira by 100). For USD, cents. ₦8,500.00 → 850000. ₦78,000,000 → 7800000000. $42.50 → 4250. Do NOT include the currency symbol. If the amount is displayed without commas or decimals (common on Nigerian bank apps for large sums, e.g. "₦78000000"), treat it as a whole-naira figure and still multiply by 100. If you only see a balance, fee, or charge — not the actual transaction amount — return null.

**currency_code** — ISO 4217 code (NGN, USD, GBP, KES, GHS, ZAR…). Infer from the currency symbol (₦ = NGN, $ = USD, £ = GBP, KSh = KES, ₵ = GHS, R = ZAR) or explicit text. If unclear, return null rather than guessing.

**transaction_date** — ISO-8601 date YYYY-MM-DD. Common Nigerian receipt formats: DD/MM/YYYY, DD-MM-YYYY, "9/1/2025" (which is September 1, 2025, NOT January 9 — Nigerian receipts use DD/MM in some apps and MM/DD in others, but bank-issued screenshots tend toward DD/MM; when ambiguous, prefer whichever interpretation lands in the recent past, since users rarely upload future-dated proofs). If only a relative date is shown ("Today", "Just now", "2 hours ago"), return null — we will not guess at absolute dates.

**reference_number** — the transaction reference, RRN, session ID, transfer ID, or UTR. Different apps label this differently: "Reference", "Reference Number", "Transaction Reference", "Session ID", "RRN", "Trace", "Confirmation Number". The value itself is usually 6-32 alphanumeric characters, sometimes with hyphens or slashes. Strip surrounding whitespace; preserve original casing.

**bank_name** — the bank or payment app that produced the screenshot. Common Nigerian names: GTBank, Access, UBA, Zenith, First Bank, Fidelity, FCMB, Stanbic IBTC, Sterling, Union Bank, Wema, Polaris, Heritage, Keystone, Ecobank. Payment apps: Opay, Kuda, PalmPay, Moniepoint, Carbon, VBank, ALAT. Use the exact spelling shown on the screenshot. Return null if the source app is not identifiable.

**counterparty_name** — the OTHER party. For outgoing transfers ("Money Out"), this is the recipient. For incoming ("Money In"), this is the sender. NEVER return the account-holder's own name. If the screenshot shows both names without indicating direction, return null — we would rather have the user type it than pick wrong.

# Confidence

Self-report confidence per field, 0.0–1.0:
- 1.0: directly visible and unambiguous
- 0.7: inferred from context (e.g. currency from a known bank's locale)
- 0.5: borderline — would be a coin-flip
- <0.5: guess

Set confidence to 0.0 for any field you returned as null. The downstream system drops any field below 0.5, so be honest — overconfidence costs the user a wrong pre-fill they have to spot and correct.

# Unreadable

Set `unreadable: true` and null every field if the image is NOT a payment proof: random photo, blurry to the point of illegibility, screenshot of unrelated content, all-black, etc. Do not invent fields to be helpful.

# Examples

Input: Bank app screen, header "GTBank", "Transfer Successful", "Amount ₦8,500.00", "To Jane Doe", "Reference TRF240524ABC", "24/05/2026".
Output: amount_minor=850000, currency_code="NGN", transaction_date="2026-05-24", reference_number="TRF240524ABC", bank_name="GTBank", counterparty_name="Jane Doe", confidence={all 0.95+}, unreadable=false.

Input: Bank app screen showing "Transaction Details", "Amount", "₦78000000Credit" (no commas, no decimal, glyph squished), "Reference 252440083011687", "9/1/2025 5:20:11 PM", description "NFT ONOSHOKEMEH PAULINA / PURCHASE OF 2 BED FLAT A".
Output: amount_minor=7800000000, currency_code="NGN", transaction_date="2025-09-01" (DD/MM in this app), reference_number="252440083011687", bank_name=null (no bank header visible), counterparty_name="ONOSHOKEMEH PAULINA" (from description; counterparty), confidence={amount_minor: 0.92, transaction_date: 0.75 (date format ambiguity), reference_number: 0.99, counterparty_name: 0.7, bank_name: 0.0, currency_code: 0.95}, unreadable=false.

Input: Opay receipt, "OPay", "Transfer Successful", "Amount: ₦8,500.00", "To: Jane Doe", "Date: 24/05/2026 11:42", "Reference: OPAY24052600045", "Charge: ₦0.00".
Output: amount_minor=850000, currency_code="NGN", transaction_date="2026-05-24", reference_number="OPAY24052600045", bank_name="Opay", counterparty_name="Jane Doe", confidence={all 0.95+}, unreadable=false. (Charge is a separate line; ignore.)

Input: Photo of a cat.
Output: all fields null, confidence={all 0.0}, unreadable=true.

Input: Bank app showing "Available Balance: ₦125,432.10" and nothing else.
Output: all transaction fields null (a balance is not a transaction amount), unreadable=false, confidence={all 0.0–0.3}.

Always call the `record_extraction` tool. Never respond with free text.
"""


@dataclass
class VisionExtraction:
    """Cleaned extraction result, post-confidence filter.

    Fields below CONFIDENCE_FLOOR have already been set to None by the
    normalizer. `raw_json` keeps the original tool input for debugging /
    retention-window storage. `unreadable=True` is a terminal signal —
    the worker writes status=failed with error_code='unreadable' rather
    than retrying.
    """

    amount_minor: int | None = None
    currency_code: str | None = None
    transaction_date: date | None = None
    reference_number: str | None = None
    bank_name: str | None = None
    counterparty_name: str | None = None
    confidence: dict[str, float] = field(default_factory=dict)
    unreadable: bool = False
    raw_json: dict[str, Any] = field(default_factory=dict)
    model_version: str = ""


class VisionClient(Protocol):
    """Abstraction so tests can inject a fake client without touching Anthropic."""

    async def extract(self, image_bytes: bytes, mime_type: str) -> VisionExtraction: ...

    async def extract_offer_letter(
        self, image_bytes: bytes, mime_type: str
    ) -> OfferLetterExtraction: ...


class AnthropicVisionClient:
    """Real client. Calls Anthropic with prompt caching and forced tool use."""

    def __init__(
        self,
        *,
        api_key: str | None = None,
        model: str | None = None,
    ) -> None:
        from anthropic import AsyncAnthropic

        key = api_key or settings.anthropic_api_key
        if not key:
            raise RuntimeError(
                "ANTHROPIC_API_KEY is not configured but extraction_enabled=True."
            )
        self._client = AsyncAnthropic(api_key=key)
        self._model = model or settings.extraction_model

    async def extract(self, image_bytes: bytes, mime_type: str) -> VisionExtraction:
        image_b64 = base64.standard_b64encode(image_bytes).decode("ascii")
        response = await self._client.messages.create(
            model=self._model,
            max_tokens=1024,
            system=[
                {
                    "type": "text",
                    "text": SYSTEM_PROMPT,
                    "cache_control": {"type": "ephemeral"},
                }
            ],
            tools=[EXTRACTION_TOOL_SCHEMA],
            tool_choice={"type": "tool", "name": EXTRACTION_TOOL_NAME},
            messages=[
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "image",
                            "source": {
                                "type": "base64",
                                "media_type": mime_type,
                                "data": image_b64,
                            },
                        },
                        {
                            "type": "text",
                            "text": "Extract the payment fields from this screenshot.",
                        },
                    ],
                }
            ],
        )
        tool_input = _find_tool_input(response)
        if tool_input is None:
            raise VisionParseError("Model did not call the record_extraction tool")
        usage = getattr(response, "usage", None)
        if usage is not None:
            log.info(
                "anthropic usage cache_creation=%s cache_read=%s in=%s out=%s",
                getattr(usage, "cache_creation_input_tokens", None),
                getattr(usage, "cache_read_input_tokens", None),
                getattr(usage, "input_tokens", None),
                getattr(usage, "output_tokens", None),
            )
        return normalize(tool_input, model_version=self._model)

    async def extract_offer_letter(
        self, image_bytes: bytes, mime_type: str
    ) -> OfferLetterExtraction:
        image_b64 = base64.standard_b64encode(image_bytes).decode("ascii")
        response = await self._client.messages.create(
            model=self._model,
            max_tokens=2048,  # installment arrays are larger than payment fields
            system=[
                {
                    "type": "text",
                    "text": OFFER_LETTER_SYSTEM_PROMPT,
                    "cache_control": {"type": "ephemeral"},
                }
            ],
            tools=[OFFER_LETTER_TOOL_SCHEMA],
            tool_choice={"type": "tool", "name": OFFER_LETTER_TOOL_NAME},
            messages=[
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "image",
                            "source": {
                                "type": "base64",
                                "media_type": mime_type,
                                "data": image_b64,
                            },
                        },
                        {
                            "type": "text",
                            "text": "Extract the installment schedule from this offer letter.",
                        },
                    ],
                }
            ],
        )
        tool_input = _find_offer_letter_tool_input(response)
        if tool_input is None:
            raise VisionParseError("Model did not call the record_offer_letter tool")
        return normalize_offer_letter(tool_input, model_version=self._model)


def _find_tool_input(response: Any) -> dict[str, Any] | None:
    for block in response.content:
        if getattr(block, "type", None) == "tool_use" and getattr(block, "name", "") == EXTRACTION_TOOL_NAME:
            return block.input  # type: ignore[no-any-return]
    return None


class VisionParseError(Exception):
    """Model returned something we couldn't normalize. Retryable."""


def normalize(tool_input: dict[str, Any], *, model_version: str = "") -> VisionExtraction:
    """Turn raw tool input into a VisionExtraction.

    Drops any field whose self-reported confidence falls below CONFIDENCE_FLOOR
    (sets the value to None and zeros the confidence entry). Preserves the
    full raw input for debugging.
    """
    confidence_raw = tool_input.get("confidence") or {}
    if not isinstance(confidence_raw, dict):
        confidence_raw = {}
    confidence: dict[str, float] = {}
    for key, value in confidence_raw.items():
        try:
            confidence[str(key)] = float(value)
        except (TypeError, ValueError):
            continue

    result = VisionExtraction(
        amount_minor=_clean_int(tool_input.get("amount_minor")),
        currency_code=_clean_str(tool_input.get("currency_code"), upper=True, maxlen=8),
        transaction_date=_clean_date(tool_input.get("transaction_date")),
        reference_number=_clean_str(tool_input.get("reference_number"), maxlen=128),
        bank_name=_clean_str(tool_input.get("bank_name"), maxlen=128),
        counterparty_name=_clean_str(tool_input.get("counterparty_name"), maxlen=255),
        confidence=confidence,
        unreadable=bool(tool_input.get("unreadable", False)),
        raw_json=tool_input,
        model_version=model_version,
    )

    if result.unreadable:
        # Wipe everything; we're going to fail this draft with error_code=unreadable
        # so the cleaned fields shouldn't survive even if the model returned values.
        result.amount_minor = None
        result.currency_code = None
        result.transaction_date = None
        result.reference_number = None
        result.bank_name = None
        result.counterparty_name = None
        return result

    # Confidence filter — drop any field below the floor.
    for field_name in (
        "amount_minor",
        "currency_code",
        "transaction_date",
        "reference_number",
        "bank_name",
        "counterparty_name",
    ):
        score = confidence.get(field_name, 0.0)
        if score < CONFIDENCE_FLOOR:
            setattr(result, field_name, None)
    return result


def _clean_str(value: Any, *, upper: bool = False, maxlen: int = 255) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    if not text:
        return None
    if upper:
        text = text.upper()
    return text[:maxlen]


def _clean_int(value: Any) -> int | None:
    if value is None:
        return None
    try:
        result = int(value)
    except (TypeError, ValueError):
        return None
    if result < 0:
        return None
    # Guard against absurd outputs that would overflow downstream consumers.
    if result > 10**15:
        return None
    return result


def _clean_date(value: Any) -> date | None:
    if value is None:
        return None
    if isinstance(value, date):
        return value
    text = str(value).strip()
    if not text:
        return None
    try:
        return date.fromisoformat(text[:10])
    except ValueError:
        return None


# =========================================================================
# Offer-letter extraction (Phase 13 Cut 6)
# =========================================================================

# Bounds mirror app/schemas/schedule.py so a draft can never produce a schedule
# the create endpoint would reject.
MAX_INSTALLMENT_AMOUNT_MINOR = 10**15
MAX_INSTALLMENTS = 600

OFFER_LETTER_TOOL_NAME = "record_offer_letter"

OFFER_LETTER_TOOL_SCHEMA: dict[str, Any] = {
    "name": OFFER_LETTER_TOOL_NAME,
    "description": (
        "Record the installment payment plan you extracted from the offer "
        "letter / allocation letter image. Use null for any field you cannot "
        "determine. Set `unreadable` to true if the image is not an offer "
        "letter with a payment plan at all."
    ),
    "input_schema": {
        "type": "object",
        "properties": {
            "buyer_name": {
                "type": ["string", "null"],
                "description": "Name of the buyer / allottee the letter is addressed to.",
            },
            "total_minor": {
                "type": ["integer", "null"],
                "description": (
                    "Total price / consideration in the smallest currency unit "
                    "(kobo for NGN). ₦12,000,000 → 1200000000. No currency symbol."
                ),
            },
            "currency_code": {
                "type": ["string", "null"],
                "description": "ISO 4217 currency code if visible (e.g. 'NGN').",
            },
            "installments": {
                "type": ["array", "null"],
                "description": (
                    "The payment schedule, one entry per scheduled installment, "
                    "in the order stated. Include the deposit/initial payment as "
                    "sequence 0. Only include rows that have a concrete due date."
                ),
                "items": {
                    "type": "object",
                    "properties": {
                        "sequence": {
                            "type": "integer",
                            "description": "0-based order as listed (deposit = 0).",
                        },
                        "amount_minor": {
                            "type": "integer",
                            "description": "Installment amount in minor units (no symbol).",
                        },
                        "due_date": {
                            "type": "string",
                            "description": "ISO-8601 date YYYY-MM-DD this installment is due.",
                        },
                    },
                    "required": ["amount_minor", "due_date"],
                },
            },
            "confidence": {
                "type": "object",
                "description": (
                    "Self-reported confidence 0.0-1.0. `installments` is ONE "
                    "score for the whole array. 1.0 = directly visible, 0.7 = "
                    "inferred, <0.5 = guess."
                ),
                "properties": {
                    "buyer_name": {"type": "number"},
                    "total_minor": {"type": "number"},
                    "currency_code": {"type": "number"},
                    "installments": {"type": "number"},
                },
            },
            "unreadable": {
                "type": "boolean",
                "description": (
                    "True if the image is NOT an offer letter with a payment "
                    "plan (random photo, unrelated document, illegible, etc.)."
                ),
            },
        },
        "required": ["confidence", "unreadable"],
    },
}

# Padded past 1024 tokens so it qualifies for Anthropic prompt caching.
OFFER_LETTER_SYSTEM_PROMPT = """You are an extraction engine for property offer letters / allocation letters from small real-estate and construction businesses, primarily in Nigeria and other African markets.

Your job: given one image of an offer letter, allocation letter, or payment-plan schedule for a house/plot, extract the installment payment plan. Return your answer via the `record_offer_letter` tool — never as free text.

# Field rules

**buyer_name** — the buyer / allottee / purchaser the letter is addressed to. Usually after "Dear", "Name of Allottee", "Purchaser", or similar. Return null if not clearly stated. Do NOT return the developer/company name.

**total_minor** — the total price / total consideration / purchase price, in the smallest unit of the currency (kobo for NGN: multiply naira by 100). ₦12,000,000 → 1200000000. If the letter shows large sums without commas (e.g. "₦12000000"), treat as whole naira and still multiply by 100. Return null if no explicit total is stated. Do NOT sum the installments yourself to invent a total.

**currency_code** — ISO 4217 (NGN, USD, GBP, GHS, KES…). Infer from the symbol (₦ = NGN, $ = USD, £ = GBP) or explicit text. Null if unclear.

**installments** — the scheduled payments, in order. Each row has `sequence` (0-based; the deposit/initial/commitment payment is sequence 0), `amount_minor` (minor units), and `due_date` (ISO-8601 YYYY-MM-DD). Rules:
- Include the deposit/initial deposit/commitment fee as sequence 0 if it has a stated date.
- ONLY include a row if it has a concrete, absolute due date. If a row is described only relatively ("on allocation", "monthly thereafter") with no resolvable date, omit that row rather than guessing a date.
- If the plan is "₦X monthly for N months starting <date>", enumerate the N concrete dated rows.
- Amounts are per-installment, not cumulative.
- If you cannot read the schedule rows with reasonable confidence, return `installments: null` rather than a partial guess — the user will type them.

# Confidence

Self-report confidence per field, 0.0–1.0. `installments` is a SINGLE score for the whole array (how sure you are the rows + dates are right). Set 0.0 for any field returned as null. The downstream system drops any field below 0.5 and drops the whole installments array if its score is below 0.5 — so be honest; a wrong pre-filled schedule is worse than a blank one the user fills in.

# Unreadable

Set `unreadable: true` and null every field if the image is NOT an offer letter with a payment plan: a random photo, a payment receipt (that's a different document), an illegible scan, unrelated text. Do not invent a plan to be helpful.

# Examples

Input: Offer letter "Dear Ada Obi", "Total Price: ₦12,000,000", "Payment Plan: Initial Deposit ₦2,000,000 on 01/07/2026; then ₦1,000,000 monthly for 10 months (01/08/2026 to 01/05/2027)".
Output: buyer_name="Ada Obi", total_minor=1200000000, currency_code="NGN", installments=[{sequence:0, amount_minor:200000000, due_date:"2026-07-01"}, {sequence:1, amount_minor:100000000, due_date:"2026-08-01"}, … {sequence:10, amount_minor:100000000, due_date:"2027-05-01"}], confidence={all 0.9+}, unreadable=false.

Input: Letter with a clear plan but the buyer's name is smudged/illegible.
Output: buyer_name=null, installments=[…rows…], confidence={buyer_name:0.2, installments:0.9, …}, unreadable=false.

Input: A photo of a car.
Output: all fields null, installments=null, confidence={all 0.0}, unreadable=true.

Input: A bank transfer receipt (not an offer letter).
Output: all fields null, unreadable=true (a receipt is not an offer letter).

Always call the `record_offer_letter` tool. Never respond with free text.
"""


@dataclass
class OfferLetterExtraction:
    """Cleaned offer-letter extraction, post-confidence filter.

    Scalar fields below CONFIDENCE_FLOOR are set to None; `installments` is set
    to None unless its array-level confidence clears the floor AND at least one
    well-formed row survives cleaning. `unreadable=True` is terminal.
    """

    buyer_name: str | None = None
    total_minor: int | None = None
    currency_code: str | None = None
    installments: list[dict[str, Any]] | None = None
    confidence: dict[str, float] = field(default_factory=dict)
    unreadable: bool = False
    raw_json: dict[str, Any] = field(default_factory=dict)
    model_version: str = ""


def _find_offer_letter_tool_input(response: Any) -> dict[str, Any] | None:
    for block in response.content:
        if (
            getattr(block, "type", None) == "tool_use"
            and getattr(block, "name", "") == OFFER_LETTER_TOOL_NAME
        ):
            return block.input  # type: ignore[no-any-return]
    return None


def _clean_installments(value: Any) -> list[dict[str, Any]] | None:
    """Drop malformed rows, then re-sequence 0..n-1 by (due_date, sequence).

    Returns None if nothing well-formed survives or the count exceeds the cap.
    Malformed = amount not a positive int ≤ 10^15, or due_date not a valid ISO
    date. See offer-letter contract §5.2.
    """
    if not isinstance(value, list):
        return None
    cleaned: list[tuple[int, int, date]] = []
    for raw in value:
        if not isinstance(raw, dict):
            continue
        amount = _clean_int(raw.get("amount_minor"))
        due = _clean_date(raw.get("due_date"))
        if amount is None or amount <= 0 or amount > MAX_INSTALLMENT_AMOUNT_MINOR or due is None:
            continue
        try:
            seq = int(raw.get("sequence", 0))
        except (TypeError, ValueError):
            seq = 0
        cleaned.append((seq, amount, due))
    if not cleaned or len(cleaned) > MAX_INSTALLMENTS:
        return None
    cleaned.sort(key=lambda row: (row[2], row[0]))
    return [
        {"sequence": index, "amount_minor": amount, "due_date": due.isoformat()}
        for index, (_seq, amount, due) in enumerate(cleaned)
    ]


def normalize_offer_letter(
    tool_input: dict[str, Any], *, model_version: str = ""
) -> OfferLetterExtraction:
    """Turn raw offer-letter tool input into an OfferLetterExtraction.

    Drops scalar fields below CONFIDENCE_FLOOR; drops the installments array if
    its array-level confidence is below the floor or no well-formed row
    survives. Preserves the full raw input for debugging/retention.
    """
    confidence_raw = tool_input.get("confidence") or {}
    if not isinstance(confidence_raw, dict):
        confidence_raw = {}
    confidence: dict[str, float] = {}
    for key, value in confidence_raw.items():
        try:
            confidence[str(key)] = float(value)
        except (TypeError, ValueError):
            continue

    result = OfferLetterExtraction(
        buyer_name=_clean_str(tool_input.get("buyer_name"), maxlen=255),
        total_minor=_clean_int(tool_input.get("total_minor")),
        currency_code=_clean_str(tool_input.get("currency_code"), upper=True, maxlen=8),
        installments=_clean_installments(tool_input.get("installments")),
        confidence=confidence,
        unreadable=bool(tool_input.get("unreadable", False)),
        raw_json=tool_input,
        model_version=model_version,
    )

    if result.unreadable:
        result.buyer_name = None
        result.total_minor = None
        result.currency_code = None
        result.installments = None
        return result

    for field_name in ("buyer_name", "total_minor", "currency_code"):
        if confidence.get(field_name, 0.0) < CONFIDENCE_FLOOR:
            setattr(result, field_name, None)
    if confidence.get("installments", 0.0) < CONFIDENCE_FLOOR:
        result.installments = None
    return result
