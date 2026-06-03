from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, date, datetime
from io import BytesIO

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.platypus import (
    Paragraph,
    SimpleDocTemplate,
    Spacer,
    Table,
    TableStyle,
)

# ReportLab's base-14 fonts (Helvetica) don't carry the ₦ glyph, so the symbol
# renders as a tofu box. "NGN " is unambiguous and always prints — and matches
# the plain-language ethos better than a broken glyph would.
_CURRENCY_PREFIX = "NGN "

_DIRECTION_LABELS = {
    "money_in": "Money in",
    "money_out": "Money out",
}


@dataclass(frozen=True)
class ReportRow:
    """One transaction line as the PDF renders it. Decoupled from the ORM so
    the renderer stays trivially unit-testable and Compose/SQLAlchemy-free."""

    transaction_date: date
    direction: str
    counterparty: str
    category: str
    amount_minor: int
    reference: str


def _major_amount(amount_minor: int) -> str:
    """Render integer minor units as a grouped major-unit string, e.g.
    125000 -> "NGN 1,250.00". Amounts are stored as kobo (1/100 naira)."""
    sign = "-" if amount_minor < 0 else ""
    whole, fraction = divmod(abs(amount_minor), 100)
    return f"{sign}{_CURRENCY_PREFIX}{whole:,}.{fraction:02d}"


def _direction_label(direction: str) -> str:
    return _DIRECTION_LABELS.get(direction, "Unknown")


def build_report_pdf(
    *,
    business_name: str,
    start_date: date,
    end_date: date,
    money_in_minor: int,
    money_out_minor: int,
    rows: list[ReportRow],
    generated_at: datetime | None = None,
) -> bytes:
    """Render a one-or-more page PDF statement and return the raw bytes.

    Plain, non-accounting language throughout (Money in / Money out / Net), so
    a small-business owner can read it without an accountant. Pure function:
    no DB, no network, no global state — the route does the data loading.
    """
    generated_at = generated_at or datetime.now(UTC)
    net_minor = money_in_minor - money_out_minor

    buffer = BytesIO()
    doc = SimpleDocTemplate(
        buffer,
        pagesize=A4,
        leftMargin=18 * mm,
        rightMargin=18 * mm,
        topMargin=18 * mm,
        bottomMargin=18 * mm,
        title=f"{business_name} report",
    )

    styles = getSampleStyleSheet()
    title_style = ParagraphStyle(
        "ReportTitle", parent=styles["Title"], fontSize=18, spaceAfter=2
    )
    meta_style = ParagraphStyle(
        "ReportMeta", parent=styles["Normal"], fontSize=9, textColor=colors.grey
    )
    section_style = ParagraphStyle(
        "ReportSection", parent=styles["Heading2"], fontSize=12, spaceBefore=6, spaceAfter=4
    )
    summary_style = ParagraphStyle("ReportSummary", parent=styles["Normal"], fontSize=11)
    cell_style = ParagraphStyle("ReportCell", parent=styles["Normal"], fontSize=8, leading=10)
    header_cell_style = ParagraphStyle(
        "ReportHeaderCell",
        parent=cell_style,
        fontName="Helvetica-Bold",
        textColor=colors.white,
    )

    flow: list[object] = []
    flow.append(Paragraph(business_name or "Report", title_style))
    flow.append(
        Paragraph(
            f"Records from {start_date.isoformat()} to {end_date.isoformat()}",
            meta_style,
        )
    )
    flow.append(
        Paragraph(
            f"Generated {generated_at.strftime('%Y-%m-%d %H:%M UTC')}",
            meta_style,
        )
    )
    flow.append(Spacer(1, 8 * mm))

    flow.append(Paragraph("Summary", section_style))
    flow.append(Paragraph(f"Money in: {_major_amount(money_in_minor)}", summary_style))
    flow.append(Paragraph(f"Money out: {_major_amount(money_out_minor)}", summary_style))
    flow.append(Paragraph(f"Net: {_major_amount(net_minor)}", summary_style))
    flow.append(
        Paragraph(
            f"{len(rows)} record{'' if len(rows) == 1 else 's'} in this period",
            meta_style,
        )
    )
    flow.append(Spacer(1, 8 * mm))

    flow.append(Paragraph("Records", section_style))
    if rows:
        header = [
            Paragraph(text, header_cell_style)
            for text in ("Date", "Direction", "Who", "Category", "Amount", "Reference")
        ]
        table_data: list[list[object]] = [header]
        for row in rows:
            table_data.append(
                [
                    Paragraph(row.transaction_date.isoformat(), cell_style),
                    Paragraph(_direction_label(row.direction), cell_style),
                    Paragraph(row.counterparty or "", cell_style),
                    Paragraph(row.category or "", cell_style),
                    Paragraph(_major_amount(row.amount_minor), cell_style),
                    Paragraph(row.reference or "", cell_style),
                ]
            )
        table = Table(
            table_data,
            repeatRows=1,
            colWidths=[22 * mm, 22 * mm, 38 * mm, 26 * mm, 30 * mm, 36 * mm],
        )
        table.setStyle(
            TableStyle(
                [
                    ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#1f2937")),
                    ("VALIGN", (0, 0), (-1, -1), "TOP"),
                    ("GRID", (0, 0), (-1, -1), 0.4, colors.HexColor("#d1d5db")),
                    (
                        "ROWBACKGROUNDS",
                        (0, 1),
                        (-1, -1),
                        [colors.white, colors.HexColor("#f9fafb")],
                    ),
                    ("LEFTPADDING", (0, 0), (-1, -1), 4),
                    ("RIGHTPADDING", (0, 0), (-1, -1), 4),
                    ("TOPPADDING", (0, 0), (-1, -1), 3),
                    ("BOTTOMPADDING", (0, 0), (-1, -1), 3),
                ]
            )
        )
        flow.append(table)
    else:
        flow.append(Paragraph("No records in this period.", summary_style))

    doc.build(flow)
    return buffer.getvalue()
