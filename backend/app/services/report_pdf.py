from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, date, datetime, timedelta
from html import escape
from io import BytesIO

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.platypus import (
    HRFlowable,
    KeepTogether,
    PageBreak,
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

SUMMARY_CATEGORY_LIMIT = 3
SUMMARY_BIGGEST_RECORD_LIMIT = 3


@dataclass(frozen=True)
class ReportRow:
    """One transaction line as the PDF renders it. Decoupled from the ORM so
    the renderer stays trivially unit-testable and Compose/SQLAlchemy-free."""

    id: str
    transaction_date: date
    direction: str
    counterparty: str
    category: str
    amount_minor: int
    reference: str


@dataclass(frozen=True)
class NamedAmount:
    label: str
    amount_minor: int


@dataclass(frozen=True)
class CategoryShare:
    label: str
    amount_minor: int
    percent: int


@dataclass(frozen=True)
class BiggestRecord:
    id: str
    transaction_date: date
    direction: str
    counterparty: str
    amount_minor: int


@dataclass(frozen=True)
class PriorPeriodChange:
    label: str
    amount_minor: int
    percent: int | None


@dataclass(frozen=True)
class ProofSummary:
    record_count: int
    records_with_proof: int

    @property
    def percent_with_proof(self) -> int:
        if self.record_count == 0:
            return 0
        return round((self.records_with_proof / self.record_count) * 100)


@dataclass(frozen=True)
class ReportInsight:
    prior_period: PriorPeriodChange | None
    money_out_categories: list[CategoryShare]
    paid_me_most: NamedAmount | None
    i_paid_most: NamedAmount | None
    biggest_records: list[BiggestRecord]
    proof: ProofSummary
    proof_transaction_ids: frozenset[str]


def _major_amount(amount_minor: int) -> str:
    """Render integer minor units as a grouped major-unit string, e.g.
    125000 -> "NGN 1,250.00". Amounts are stored as kobo (1/100 naira)."""
    sign = "-" if amount_minor < 0 else ""
    whole, fraction = divmod(abs(amount_minor), 100)
    return f"{sign}{_CURRENCY_PREFIX}{whole:,}.{fraction:02d}"


def _direction_label(direction: str) -> str:
    return _DIRECTION_LABELS.get(direction, "Unknown")


def _short_name(value: str, max_chars: int = 22) -> str:
    cleaned = " ".join((value or "").split())
    if len(cleaned) <= max_chars:
        return cleaned
    return cleaned[: max_chars - 1].rstrip() + "…"


def _date_range_label(start_date: date, end_date: date) -> str:
    if start_date == end_date:
        return start_date.strftime("%d %b %Y")
    if start_date.year == end_date.year and start_date.month == end_date.month:
        return f"{start_date.strftime('%d')}–{end_date.strftime('%d %b %Y')}"
    if start_date.year == end_date.year:
        return f"{start_date.strftime('%d %b')}–{end_date.strftime('%d %b %Y')}"
    return f"{start_date.strftime('%d %b %Y')}–{end_date.strftime('%d %b %Y')}"


def _period_label(start_date: date, end_date: date) -> str:
    if _is_whole_month(start_date, end_date):
        next_day = end_date + timedelta(days=1)
        if next_day.day == 1:
            return start_date.strftime("%B %Y")
    return _date_range_label(start_date, end_date)


def _is_whole_month(start_date: date, end_date: date) -> bool:
    return (
        start_date.day == 1
        and start_date.month == end_date.month
        and start_date.year == end_date.year
    )


def previous_period_range(start_date: date, end_date: date) -> tuple[date, date]:
    """Return the comparison window a business owner expects for a report.

    Whole-month reports compare to the previous calendar month. Other custom
    ranges compare to the immediately preceding range with the same length.
    """
    if _is_whole_month(start_date, end_date):
        next_day = end_date + timedelta(days=1)
        if next_day.day == 1:
            previous_end = start_date - timedelta(days=1)
            previous_start = previous_end.replace(day=1)
            return previous_start, previous_end
    period_days = (end_date - start_date).days + 1
    previous_end = start_date - timedelta(days=1)
    previous_start = previous_end - timedelta(days=period_days - 1)
    return previous_start, previous_end


def _safe_percent(numerator: int, denominator: int) -> int:
    if denominator == 0:
        return 0
    return round((numerator / denominator) * 100)


def build_report_insight(
    *,
    rows: list[ReportRow],
    previous_rows: list[ReportRow],
    start_date: date,
    end_date: date,
    proof_transaction_ids: set[str],
) -> ReportInsight:
    """Compute business-owner friendly report facts from already-loaded rows.

    The endpoint does DB membership/range filtering before calling this, which
    keeps this function pure and easy to test.
    """
    net_minor = _net(rows)
    previous_net_minor = _net(previous_rows)
    previous_start_date, previous_end_date = previous_period_range(start_date, end_date)
    previous_label = _period_label(previous_start_date, previous_end_date)
    prior_delta = net_minor - previous_net_minor
    prior_percent = None
    if previous_net_minor != 0:
        prior_percent = round((prior_delta / abs(previous_net_minor)) * 100)

    money_out_rows = [row for row in rows if row.direction == "money_out"]
    money_out_total = sum(row.amount_minor for row in money_out_rows)
    category_totals = _group_amounts(money_out_rows, lambda row: row.category or "Other")
    category_shares = [
        CategoryShare(
            label=label,
            amount_minor=amount,
            percent=_safe_percent(amount, money_out_total),
        )
        for label, amount in category_totals[:4]
    ]

    money_in_people = _group_amounts(
        [row for row in rows if row.direction == "money_in"],
        lambda row: row.counterparty or "Unknown",
    )
    money_out_people = _group_amounts(
        money_out_rows,
        lambda row: row.counterparty or "Unknown",
    )

    return ReportInsight(
        prior_period=PriorPeriodChange(previous_label, prior_delta, prior_percent),
        money_out_categories=category_shares,
        paid_me_most=_first_named_amount(money_in_people),
        i_paid_most=_first_named_amount(money_out_people),
        biggest_records=[
            BiggestRecord(
                id=row.id,
                transaction_date=row.transaction_date,
                direction=row.direction,
                counterparty=row.counterparty or "Unknown",
                amount_minor=row.amount_minor,
            )
            for row in sorted(rows, key=lambda row: row.amount_minor, reverse=True)[:3]
        ],
        proof=ProofSummary(
            record_count=len(rows),
            records_with_proof=sum(1 for row in rows if row.id in proof_transaction_ids),
        ),
        proof_transaction_ids=frozenset(proof_transaction_ids),
    )


def _net(rows: list[ReportRow]) -> int:
    money_in = sum(row.amount_minor for row in rows if row.direction == "money_in")
    money_out = sum(row.amount_minor for row in rows if row.direction == "money_out")
    return money_in - money_out


def _normalized_label(value: str) -> str:
    return " ".join(value.strip().lower().split())


def _group_amounts(
    rows: list[ReportRow],
    label_for: Callable[[ReportRow], str],
) -> list[tuple[str, int]]:
    buckets: dict[str, tuple[str, int]] = {}
    spellings: dict[str, dict[str, int]] = {}
    for row in rows:
        raw_label = " ".join(str(label_for(row) or "Unknown").split()) or "Unknown"
        key = _normalized_label(raw_label)
        spellings.setdefault(key, {})
        spellings[key][raw_label] = spellings[key].get(raw_label, 0) + 1
        current_label, current_amount = buckets.get(key, (raw_label, 0))
        buckets[key] = (current_label, current_amount + row.amount_minor)
    grouped = []
    for key, (_, amount) in buckets.items():
        label = sorted(spellings[key].items(), key=lambda item: (-item[1], item[0]))[0][0]
        grouped.append((label, amount))
    return sorted(grouped, key=lambda item: item[1], reverse=True)


def _first_named_amount(items: list[tuple[str, int]]) -> NamedAmount | None:
    if not items:
        return None
    label, amount = items[0]
    return NamedAmount(label=label, amount_minor=amount)


def build_report_pdf(
    *,
    business_name: str,
    start_date: date,
    end_date: date,
    money_in_minor: int,
    money_out_minor: int,
    rows: list[ReportRow],
    insight: ReportInsight,
    generated_at: datetime | None = None,
) -> bytes:
    """Render the polished HTML/CSS report to PDF, with ReportLab fallback.

    The preferred path uses Playwright/Chromium so the PDF can closely follow
    the Canva/HTML design reference. The fallback keeps exports working in
    local/dev environments before Playwright browsers are installed.
    """
    generated_at = generated_at or datetime.now(UTC)
    html = build_report_html(
        business_name=business_name,
        start_date=start_date,
        end_date=end_date,
        money_in_minor=money_in_minor,
        money_out_minor=money_out_minor,
        rows=rows,
        insight=insight,
        generated_at=generated_at,
    )
    try:
        return _render_html_pdf(html)
    except Exception:
        return _build_reportlab_pdf(
            business_name=business_name,
            start_date=start_date,
            end_date=end_date,
            money_in_minor=money_in_minor,
            money_out_minor=money_out_minor,
            rows=rows,
            insight=insight,
            generated_at=generated_at,
        )


def build_report_html(
    *,
    business_name: str,
    start_date: date,
    end_date: date,
    money_in_minor: int,
    money_out_minor: int,
    rows: list[ReportRow],
    insight: ReportInsight,
    generated_at: datetime,
) -> str:
    """Build the HTML report used by the preferred PDF renderer."""
    net_minor = money_in_minor - money_out_minor
    summary_categories = insight.money_out_categories[:SUMMARY_CATEGORY_LIMIT]
    summary_biggest_records = insight.biggest_records[:SUMMARY_BIGGEST_RECORD_LIMIT]
    category_rows = "\n".join(_category_html(item) for item in summary_categories)
    if not category_rows:
        category_rows = '<div class="empty">No money out recorded in this period.</div>'

    biggest_rows = "\n".join(_biggest_record_html(item) for item in summary_biggest_records)
    if not biggest_rows:
        biggest_rows = '<tr><td colspan="4" class="empty-cell">No records in this period.</td></tr>'

    evidence_rows = "\n".join(_evidence_row_html(row, insight) for row in rows)
    if not evidence_rows:
        evidence_rows = (
            '<tr><td colspan="6" class="empty-cell">'
            "No records in this period.</td></tr>"
        )
    meta_label = (
        f"{escape(_period_label(start_date, end_date))} &middot; "
        f"{escape(_date_range_label(start_date, end_date))}"
    )
    generated_label = escape(generated_at.strftime("%Y-%m-%d %H:%M UTC"))
    category_aside = (
        f"{escape(_major_amount(money_out_minor))} total money out"
    )
    net_class = "net-positive" if net_minor >= 0 else "net-negative"
    proof_text = (
        f"<b>{insight.proof.record_count} records</b> &middot; "
        f"{insight.proof.records_with_proof} with attached proof "
        f"({insight.proof.percent_with_proof}%)"
    )
    appendix_line = (
        f"{len(rows)} records &middot; {_date_range_label(start_date, end_date)} "
        "&middot; sorted oldest first"
    )

    return f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>{escape(business_name or "Business")} statement</title>
<style>
{_REPORT_CSS}
</style>
</head>
<body>
  <section class="sheet summary-sheet">
    <main class="canvas">
      <header class="masthead">
        <div class="kicker">Business Statement</div>
        <h1>{escape(business_name or "Business")}</h1>
        <div class="meta-bar">
          <span>{meta_label}</span>
          <span class="sep"></span>
          <span class="gen">Generated {generated_label}</span>
        </div>
      </header>

      <section class="hero">
        <div class="eyebrow">Net this period</div>
        <div class="net {net_class}">{_html_signed_amount(net_minor, hero=True)}</div>
        <div class="flow">
          <span>Money in <b class="v-in">{escape(_major_amount(money_in_minor))}</b></span>
          <span class="dot"></span>
          <span>Money out <b class="v-out">{escape(_major_amount(money_out_minor))}</b></span>
        </div>
        <div class="{escape(_delta_class(insight.prior_period))}">
          {escape(_prior_period_text_html(insight.prior_period))}
        </div>
      </section>

      <section class="section">
        <div class="section-head">
          <span class="eyebrow">Money out by category</span>
          <span class="aside">{category_aside}</span>
        </div>
        <p class="section-note">Each line shows how much you paid out in that category.</p>
        <div class="breakdown">
          {category_rows}
        </div>
      </section>

      <section class="section">
        <div class="section-head"><span class="eyebrow">Top people</span></div>
        <div class="people-grid">
          {_person_card_html("Paid me most", insight.paid_me_most, "in")}
          {_person_card_html("I paid most", insight.i_paid_most, "out")}
        </div>
      </section>

      <section class="section">
        <div class="section-head">
          <span class="eyebrow">Biggest records</span>
          <span class="aside">Top {len(summary_biggest_records)} by amount</span>
        </div>
        <table class="ledger compact">
          <thead>
            <tr>
              <th>Date</th>
              <th>Type</th>
              <th>Counterparty</th>
              <th class="r">Amount</th>
            </tr>
          </thead>
          <tbody>{biggest_rows}</tbody>
        </table>
      </section>

      <section class="section">
        <div class="section-head"><span class="eyebrow">Proof</span></div>
        <div class="proof-pill">
          <span class="tick">&#10003;</span>
          <span>{proof_text}</span>
        </div>
      </section>

      <footer class="doc-foot">
        <span>{escape(business_name or "Business")} &middot; Payment Proof Tracker</span>
        <span>Page 1 of 2</span>
      </footer>
    </main>
  </section>

  <section class="sheet evidence-sheet page-break">
    <main class="canvas">
      <header class="appendix-head">
        <div class="eyebrow">Appendix</div>
        <h2>All records (evidence)</h2>
        <p>{appendix_line}</p>
      </header>
      <table class="ledger evidence">
        <thead>
          <tr>
            <th>Date</th>
            <th>Type</th>
            <th>Counterparty</th>
            <th>Category</th>
            <th>Proof</th>
            <th class="r">Amount</th>
          </tr>
        </thead>
        <tbody>{evidence_rows}</tbody>
      </table>
      <footer class="doc-foot">
        <span>Generated by Payment Proof Tracker</span>
        <span>Page 2 of 2</span>
      </footer>
    </main>
  </section>
</body>
</html>"""


def _category_html(item: CategoryShare) -> str:
    width = max(2, min(100, item.percent))
    return f"""
      <div class="brow">
        <div class="cat-block">
          <span class="cat">{escape(_short_name(item.label, 34))}</span>
          <span class="cat-help">money out</span>
        </div>
        <div class="spend-block">
          <span class="amt">{escape(_major_amount(item.amount_minor))}</span>
          <span class="pct">{item.percent}% of money out</span>
        </div>
        <span class="track" aria-hidden="true">
          <span class="fill" style="width:{width}%"></span>
        </span>
      </div>"""


def _person_card_html(title: str, item: NamedAmount | None, direction_class: str) -> str:
    if item is None:
        name = "No records yet"
        amount = ""
    else:
        name = _short_name(item.label, 28)
        amount = _html_signed_amount(
            item.amount_minor if direction_class == "in" else -item.amount_minor
        )
    return f"""
      <div class="person-card">
        <div class="label">{escape(title)}</div>
        <div class="name">{escape(name)}</div>
        <div class="amt {escape(direction_class)}">{amount}</div>
      </div>"""


def _biggest_record_html(item: BiggestRecord) -> str:
    direction_class = "in" if item.direction == "money_in" else "out"
    signed_amount = item.amount_minor if item.direction == "money_in" else -item.amount_minor
    direction_label = escape(_direction_label(item.direction))
    return f"""
      <tr>
        <td class="date">{escape(item.transaction_date.strftime("%d %b"))}</td>
        <td><span class="badge {direction_class}">{direction_label}</span></td>
        <td class="party">{escape(_short_name(item.counterparty, 32))}</td>
        <td class="amt {direction_class}">{_html_signed_amount(signed_amount)}</td>
      </tr>"""


def _evidence_row_html(row: ReportRow, insight: ReportInsight) -> str:
    direction_class = "in" if row.direction == "money_in" else "out"
    signed_amount = row.amount_minor if row.direction == "money_in" else -row.amount_minor
    proof = "Yes" if row.id in insight.proof_transaction_ids else "-"
    direction_label = escape(_short_direction(row.direction))
    counterparty = escape(_short_name(row.counterparty or "Unknown", 32))
    category = escape(_short_name(row.category or "Other", 30))
    return f"""
      <tr>
        <td class="date">{escape(row.transaction_date.strftime("%d %b"))}</td>
        <td><span class="badge {direction_class}">{direction_label}</span></td>
        <td class="party">{counterparty}</td>
        <td class="muted">{category}</td>
        <td class="proof">{escape(proof)}</td>
        <td class="amt {direction_class}">{_html_signed_amount(signed_amount)}</td>
      </tr>"""


def _short_direction(direction: str) -> str:
    return "In" if direction == "money_in" else "Out" if direction == "money_out" else "Unknown"


def _html_signed_amount(amount_minor: int, *, hero: bool = False) -> str:
    sign = "+" if amount_minor >= 0 else "-"
    amount = _major_amount(abs(amount_minor))
    if hero:
        whole, _, cents = amount.removeprefix(_CURRENCY_PREFIX).partition(".")
        return (
            f'<span class="sign">{sign}</span><span class="cur">NGN</span>'
            f"{escape(whole)}<span class=\"cents\">.{escape(cents)}</span>"
        )
    return f"{sign} {escape(amount)}"


def _delta_class(prior: PriorPeriodChange | None) -> str:
    if prior is None or prior.amount_minor >= 0:
        return "delta up"
    return "delta down"


def _prior_period_text_html(prior: PriorPeriodChange | None) -> str:
    if prior is None:
        return "No prior-period comparison yet"
    direction = "up" if prior.amount_minor >= 0 else "down"
    percent = "" if prior.percent is None else f" ({prior.percent:+d}%)"
    return f"{direction}: {_signed_amount(prior.amount_minor)} vs {prior.label}{percent}"


def _render_html_pdf(html: str) -> bytes:
    from playwright.sync_api import sync_playwright

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(args=["--no-sandbox"])
        page = browser.new_page()
        page.set_content(html, wait_until="load")
        pdf = page.pdf(
            format="Letter",
            print_background=True,
            margin={"top": "0", "right": "0", "bottom": "0", "left": "0"},
        )
        browser.close()
        return pdf


def _build_reportlab_pdf(
    *,
    business_name: str,
    start_date: date,
    end_date: date,
    money_in_minor: int,
    money_out_minor: int,
    rows: list[ReportRow],
    insight: ReportInsight,
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
    title_style = ParagraphStyle("ReportTitle", parent=styles["Title"], fontSize=19, spaceAfter=2)
    meta_style = ParagraphStyle(
        "ReportMeta", parent=styles["Normal"], fontSize=9, textColor=colors.grey
    )
    section_style = ParagraphStyle(
        "ReportSection", parent=styles["Heading2"], fontSize=12, spaceBefore=6, spaceAfter=4
    )
    summary_style = ParagraphStyle("ReportSummary", parent=styles["Normal"], fontSize=11)
    hero_label_style = ParagraphStyle(
        "HeroLabel",
        parent=styles["Normal"],
        fontName="Helvetica-Bold",
        fontSize=9,
        textColor=colors.HexColor("#374151"),
        leading=12,
    )
    hero_amount_style = ParagraphStyle(
        "HeroAmount",
        parent=styles["Title"],
        fontSize=24,
        leading=28,
        alignment=2,
        textColor=colors.HexColor("#111827"),
    )
    insight_label_style = ParagraphStyle(
        "InsightLabel",
        parent=styles["Normal"],
        fontName="Helvetica-Bold",
        fontSize=9,
        textColor=colors.HexColor("#111827"),
        leading=11,
    )
    insight_text_style = ParagraphStyle(
        "InsightText", parent=styles["Normal"], fontSize=9, leading=11
    )
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
            f"{_period_label(start_date, end_date)} · {_date_range_label(start_date, end_date)}",
            meta_style,
        )
    )
    flow.append(
        Paragraph(
            f"Generated {generated_at.strftime('%Y-%m-%d %H:%M UTC')}",
            meta_style,
        )
    )
    flow.append(HRFlowable(width="100%", thickness=0.8, color=colors.HexColor("#d1d5db")))
    flow.append(Spacer(1, 6 * mm))

    flow.extend(
        _build_insight_cover(
            net_minor=net_minor,
            money_in_minor=money_in_minor,
            money_out_minor=money_out_minor,
            insight=insight,
            styles=(
                hero_label_style,
                hero_amount_style,
                insight_label_style,
                insight_text_style,
                meta_style,
            ),
        )
    )

    flow.append(PageBreak())

    flow.append(Paragraph("All records (evidence)", section_style))
    flow.append(
        Paragraph(
            "Full transaction table for the selected period. Keep this page with "
            "the summary as the supporting proof trail.",
            meta_style,
        )
    )
    flow.append(Spacer(1, 4 * mm))
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


def _build_insight_cover(
    *,
    net_minor: int,
    money_in_minor: int,
    money_out_minor: int,
    insight: ReportInsight,
    styles: tuple[ParagraphStyle, ParagraphStyle, ParagraphStyle, ParagraphStyle, ParagraphStyle],
) -> list[object]:
    (
        hero_label_style,
        hero_amount_style,
        insight_label_style,
        insight_text_style,
        meta_style,
    ) = styles
    flow: list[object] = []
    flow.append(
        Table(
            [
                [
                    Paragraph("1  NET THIS PERIOD", hero_label_style),
                    Paragraph(_signed_amount(net_minor), hero_amount_style),
                ],
                [
                    Paragraph(
                        f"Money in&nbsp;&nbsp;{_major_amount(money_in_minor)}"
                        f"&nbsp;&nbsp;&nbsp;&nbsp;Money out&nbsp;&nbsp;"
                        f"{_major_amount(money_out_minor)}",
                        meta_style,
                    ),
                    Paragraph(_prior_period_text(insight.prior_period), meta_style),
                ],
            ],
            colWidths=[76 * mm, 98 * mm],
            style=TableStyle(
                [
                    ("BACKGROUND", (0, 0), (-1, -1), colors.HexColor("#f3f4f6")),
                    ("BOX", (0, 0), (-1, -1), 0.8, colors.HexColor("#d1d5db")),
                    ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
                    ("LEFTPADDING", (0, 0), (-1, -1), 8),
                    ("RIGHTPADDING", (0, 0), (-1, -1), 8),
                    ("TOPPADDING", (0, 0), (-1, -1), 7),
                    ("BOTTOMPADDING", (0, 0), (-1, -1), 7),
                ]
            ),
        )
    )
    flow.append(Spacer(1, 7 * mm))

    flow.append(_section_title("2  WHERE THE MONEY WENT", insight_label_style))
    flow.append(_category_share_table(insight.money_out_categories, insight_text_style))
    flow.append(Spacer(1, 5 * mm))

    flow.append(_section_title("3  TOP PEOPLE", insight_label_style))
    flow.append(
        _two_column_fact_table(
            left_title="Paid me most",
            left_value=_named_amount_text(insight.paid_me_most),
            right_title="I paid most",
            right_value=_named_amount_text(insight.i_paid_most),
            text_style=insight_text_style,
            label_style=insight_label_style,
        )
    )
    flow.append(Spacer(1, 5 * mm))

    flow.append(_section_title("4  BIGGEST RECORDS", insight_label_style))
    flow.append(_biggest_records_table(insight.biggest_records, insight_text_style))
    flow.append(Spacer(1, 5 * mm))

    flow.append(_section_title("5  PROOF", insight_label_style))
    proof_text = (
        f"{insight.proof.record_count} record{'' if insight.proof.record_count == 1 else 's'} · "
        f"{insight.proof.records_with_proof} with attached proof "
        f"({insight.proof.percent_with_proof}%)"
    )
    flow.append(
        KeepTogether(
            [
                Table(
                    [[Paragraph(proof_text, insight_text_style)]],
                    colWidths=[174 * mm],
                    style=TableStyle(
                        [
                            ("BACKGROUND", (0, 0), (-1, -1), colors.HexColor("#ecfdf5")),
                            ("BOX", (0, 0), (-1, -1), 0.7, colors.HexColor("#a7f3d0")),
                            ("LEFTPADDING", (0, 0), (-1, -1), 8),
                            ("RIGHTPADDING", (0, 0), (-1, -1), 8),
                            ("TOPPADDING", (0, 0), (-1, -1), 7),
                            ("BOTTOMPADDING", (0, 0), (-1, -1), 7),
                        ]
                    ),
                )
            ]
        )
    )
    return flow


def _section_title(text: str, style: ParagraphStyle) -> Paragraph:
    return Paragraph(text, style)


def _prior_period_text(prior: PriorPeriodChange | None) -> str:
    if prior is None:
        return "No prior-period comparison yet"
    marker = "▲" if prior.amount_minor >= 0 else "▼"
    percent = "" if prior.percent is None else f" ({prior.percent:+d}%)"
    return f"{marker} {_signed_amount(prior.amount_minor)} vs {prior.label}{percent}"


def _signed_amount(amount_minor: int) -> str:
    prefix = "+" if amount_minor >= 0 else "-"
    return f"{prefix}{_major_amount(abs(amount_minor))}"


def _named_amount_text(item: NamedAmount | None) -> str:
    if item is None:
        return "No records yet"
    return f"{_short_name(item.label, 24)}    {_major_amount(item.amount_minor)}"


def _category_share_table(items: list[CategoryShare], text_style: ParagraphStyle) -> Table:
    if not items:
        rows: list[list[object]] = [
            [Paragraph("No money out recorded in this period.", text_style)]
        ]
        widths = [174 * mm]
    else:
        rows = []
        for item in items:
            rows.append(
                [
                    Paragraph(_short_name(item.label, 26), text_style),
                    Paragraph(_major_amount(item.amount_minor), text_style),
                    _bar(item.percent),
                    Paragraph(f"{item.percent}%", text_style),
                ]
            )
        widths = [52 * mm, 42 * mm, 60 * mm, 20 * mm]
    table = Table(rows, colWidths=widths)
    table.setStyle(
        TableStyle(
            [
                ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
                ("LEFTPADDING", (0, 0), (-1, -1), 3),
                ("RIGHTPADDING", (0, 0), (-1, -1), 3),
                ("TOPPADDING", (0, 0), (-1, -1), 3),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 3),
            ]
        )
    )
    return table


def _bar(percent: int) -> str:
    width = max(1, round(percent / 8)) if percent > 0 else 0
    return "#" * width


def _two_column_fact_table(
    *,
    left_title: str,
    left_value: str,
    right_title: str,
    right_value: str,
    text_style: ParagraphStyle,
    label_style: ParagraphStyle,
) -> Table:
    table = Table(
        [
            [Paragraph(left_title, label_style), Paragraph(right_title, label_style)],
            [Paragraph(left_value, text_style), Paragraph(right_value, text_style)],
        ],
        colWidths=[87 * mm, 87 * mm],
    )
    table.setStyle(
        TableStyle(
            [
                ("BACKGROUND", (0, 0), (-1, -1), colors.HexColor("#f9fafb")),
                ("BOX", (0, 0), (-1, -1), 0.5, colors.HexColor("#e5e7eb")),
                ("INNERGRID", (0, 0), (-1, -1), 0.4, colors.HexColor("#e5e7eb")),
                ("LEFTPADDING", (0, 0), (-1, -1), 7),
                ("RIGHTPADDING", (0, 0), (-1, -1), 7),
                ("TOPPADDING", (0, 0), (-1, -1), 5),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
            ]
        )
    )
    return table


def _biggest_records_table(items: list[BiggestRecord], text_style: ParagraphStyle) -> Table:
    if not items:
        rows: list[list[object]] = [[Paragraph("No records in this period.", text_style)]]
        widths = [174 * mm]
    else:
        rows = [
            [
                Paragraph(item.transaction_date.isoformat(), text_style),
                Paragraph(_direction_label(item.direction), text_style),
                Paragraph(_short_name(item.counterparty, 24), text_style),
                Paragraph(_major_amount(item.amount_minor), text_style),
            ]
            for item in items
        ]
        widths = [32 * mm, 30 * mm, 66 * mm, 46 * mm]
    table = Table(rows, colWidths=widths)
    table.setStyle(
        TableStyle(
            [
                ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
                ("LEFTPADDING", (0, 0), (-1, -1), 3),
                ("RIGHTPADDING", (0, 0), (-1, -1), 3),
                ("TOPPADDING", (0, 0), (-1, -1), 3),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 3),
            ]
        )
    )
    return table


_REPORT_CSS = """
:root {
  --ink: #1c1f1e;
  --ink-soft: #44483f;
  --muted: #5f6368;
  --faint: #9ca3af;
  --line: #e5e1d8;
  --line-faint: #f0ece3;
  --card: #f7f4ed;
  --brand: #0f3d2e;
  --gold: #b08d3f;
  --pos: #0f3d2e;
  --pos-soft: #eaf3ee;
  --pos-ink: #0f3d2e;
  --neg-soft: #efebe1;
  --page-w: 8.5in;
  --page-h: 11in;
  --pad: 0.5in;
  --col: 7.1in;
}

* { box-sizing: border-box; }
body {
  margin: 0;
  background: #ece7dd;
  color: var(--ink);
  font-family: "Aptos", "Segoe UI", "Helvetica Neue", Arial, sans-serif;
  font-variant-numeric: tabular-nums;
  line-height: 1.5;
}
.sheet {
  width: var(--page-w);
  min-height: var(--page-h);
  background: #fff;
  padding: var(--pad);
  page-break-after: always;
  position: relative;
}
.summary-sheet {
  height: var(--page-h);
  overflow: hidden;
}
.sheet:last-child { page-break-after: auto; }
.canvas {
  margin: 0 auto;
  max-width: var(--col);
}
.summary-sheet .canvas { padding-bottom: 0.72in; }
.eyebrow {
  color: var(--faint);
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: 0.16em;
  text-transform: uppercase;
}
.masthead {
  border-bottom: 1px solid var(--line);
  padding-bottom: 14px;
  text-align: center;
}
.kicker {
  color: var(--gold);
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: 0.22em;
  margin-bottom: 7px;
  text-transform: uppercase;
}
h1 {
  color: var(--ink);
  font-size: 25px;
  font-weight: 800;
  letter-spacing: -0.03em;
  line-height: 1.05;
  margin: 0;
}
.meta-bar {
  align-items: center;
  color: var(--muted);
  display: flex;
  font-size: 10.5px;
  font-weight: 500;
  gap: 12px;
  justify-content: center;
  margin-top: 7px;
}
.meta-bar .sep {
  background: var(--line);
  display: inline-block;
  height: 11px;
  width: 1px;
}
.meta-bar .gen { color: var(--faint); }
.hero {
  border-bottom: 1px solid var(--line);
  padding: 22px 0 20px;
  text-align: center;
}
.hero .net {
  color: var(--pos);
  font-size: 38px;
  font-weight: 800;
  letter-spacing: -0.02em;
  line-height: 1;
  margin-top: 8px;
}
.hero .net-negative { color: var(--ink); }
.hero .net-positive { color: var(--pos); }
.hero .cur {
  color: inherit;
  font-size: 18px;
  font-weight: 700;
  letter-spacing: -0.01em;
  margin-right: 7px;
  vertical-align: 0.42em;
}
.hero .sign { margin-right: 8px; }
.hero .cents {
  font-size: 22px;
  letter-spacing: 0;
}
.flow {
  align-items: center;
  color: var(--ink-soft);
  display: inline-flex;
  font-size: 11.5px;
  font-weight: 500;
  gap: 12px;
  margin-top: 10px;
}
.flow .dot {
  background: var(--faint);
  border-radius: 50%;
  height: 3px;
  width: 3px;
}
.flow b { font-weight: 800; }
.v-in { color: var(--pos-ink); }
.v-out { color: var(--ink); }
.delta {
  border-radius: 999px;
  display: inline-block;
  font-size: 10.5px;
  font-weight: 700;
  margin-top: 10px;
  padding: 5px 12px;
}
.delta.up {
  background: var(--pos-soft);
  color: var(--pos-ink);
}
.delta.down {
  background: var(--neg-soft);
  color: var(--ink-soft);
}
.section { margin-top: 20px; }
.section-head {
  align-items: baseline;
  display: flex;
  justify-content: space-between;
  margin-bottom: 8px;
}
.section-head .eyebrow { color: var(--muted); }
.aside {
  color: var(--faint);
  font-size: 11px;
  font-weight: 600;
}
.brow {
  align-items: end;
  border-bottom: 1px solid var(--line-faint);
  column-gap: 14px;
  display: grid;
  grid-template-columns: 1.15fr 1fr;
  padding: 8px 0;
}
.brow:last-child { border-bottom: none; }
.cat-block,
.spend-block {
  display: flex;
  flex-direction: column;
}
.spend-block { align-items: flex-end; }
.cat {
  color: var(--ink);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: -0.01em;
}
.cat-help {
  color: var(--faint);
  font-size: 9.5px;
  font-weight: 700;
  letter-spacing: 0.08em;
  line-height: 1.2;
  margin-top: 1px;
  text-transform: uppercase;
}
.track {
  background: var(--line-faint);
  border-radius: 4px;
  grid-column: 1 / -1;
  display: block;
  height: 3px;
  margin-top: 6px;
  overflow: hidden;
  width: 100%;
}
.fill {
  background: var(--brand);
  border-radius: 4px;
  display: block;
  height: 100%;
}
.pct {
  color: var(--muted);
  font-size: 10px;
  font-weight: 600;
  line-height: 1.2;
  margin-top: 1px;
  text-align: right;
}
.amt {
  color: var(--ink);
  font-size: 12px;
  font-weight: 800;
  letter-spacing: -0.01em;
  text-align: right;
}
.people-grid {
  display: grid;
  gap: 12px;
  grid-template-columns: 1fr 1fr;
}
.person-card {
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 12px 14px;
}
.person-card .label {
  color: var(--muted);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.04em;
}
.person-card .name {
  color: var(--ink);
  font-size: 15px;
  font-weight: 800;
  letter-spacing: -0.02em;
  margin-top: 6px;
}
.person-card .amt {
  font-size: 12.5px;
  margin-top: 2px;
  text-align: left;
}
.person-card .amt.in,
.ledger .amt.in { color: var(--pos-ink); }
.person-card .amt.out,
.ledger .amt.out { color: var(--ink); }
.ledger {
  border-collapse: collapse;
  width: 100%;
}
.ledger th {
  border-bottom: 1px solid var(--line);
  color: var(--faint);
  font-size: 9px;
  font-weight: 800;
  letter-spacing: 0.12em;
  padding: 0 9px 6px;
  text-align: left;
  text-transform: uppercase;
}
.ledger th.r { text-align: right; }
.ledger td {
  border-bottom: 1px solid var(--line-faint);
  color: var(--ink);
  font-size: 11.5px;
  padding: 7px 9px;
  vertical-align: middle;
}
.ledger tr:nth-child(even) td { background: #faf8f3; }
.ledger .date {
  color: var(--muted);
  font-weight: 600;
  white-space: nowrap;
}
.ledger .party {
  font-weight: 800;
  letter-spacing: -0.01em;
}
.ledger .muted {
  color: var(--muted);
  font-weight: 600;
}
.ledger .proof {
  color: var(--pos-ink);
  font-weight: 800;
  text-align: center;
}
.badge {
  border-radius: 999px;
  display: inline-block;
  font-size: 9.5px;
  font-weight: 800;
  padding: 2px 7px;
  white-space: nowrap;
}
.badge.in {
  background: var(--pos-soft);
  color: var(--pos-ink);
}
.badge.out {
  background: var(--neg-soft);
  color: var(--ink-soft);
}
.proof-pill {
  align-items: center;
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 999px;
  color: var(--ink-soft);
  display: flex;
  font-size: 11px;
  font-weight: 600;
  gap: 10px;
  justify-content: center;
  padding: 9px 16px;
}
.proof-pill .tick {
  align-items: center;
  background: var(--brand);
  border-radius: 50%;
  color: #fff;
  display: inline-flex;
  font-size: 10px;
  font-weight: 800;
  height: 16px;
  justify-content: center;
  width: 16px;
}
.doc-foot {
  align-items: center;
  border-top: 1px solid var(--line);
  color: var(--faint);
  display: flex;
  font-size: 10.5px;
  font-weight: 600;
  justify-content: space-between;
  letter-spacing: 0.01em;
  margin-top: 0;
  padding-top: 16px;
}
.summary-sheet .doc-foot {
  bottom: 0.42in;
  left: var(--pad);
  position: absolute;
  right: var(--pad);
}
.appendix-head { margin-bottom: 18px; }
.appendix-head h2 {
  color: var(--ink);
  font-size: 19px;
  font-weight: 800;
  letter-spacing: -0.025em;
  margin: 8px 0 0;
}
.appendix-head p {
  color: var(--muted);
  font-size: 12px;
  font-weight: 600;
  margin: 5px 0 0;
}
.empty,
.empty-cell {
  color: var(--muted);
  font-size: 13px;
  font-weight: 600;
  padding: 14px 0;
}
@page { size: letter; margin: 0; }
"""
