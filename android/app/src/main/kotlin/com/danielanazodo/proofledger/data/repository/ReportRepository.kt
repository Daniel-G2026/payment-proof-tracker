package com.danielanazodo.proofledger.data.repository

import android.net.Uri
import com.danielanazodo.proofledger.data.export.ExportFileWriter
import com.danielanazodo.proofledger.data.local.entity.LocalTransactionEntity
import com.danielanazodo.proofledger.ui.format.ReportPeriod
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A counterparty or category with its total money moved in the period. */
data class NamedTotal(
    val name: String,
    val totalMinor: Long,
)

/** Computed summary for one [ReportPeriod], everything the screen renders. */
data class ReportSummary(
    val period: ReportPeriod,
    val moneyInMinor: Long,
    val moneyOutMinor: Long,
    val netMinor: Long,
    val recordCount: Int,
    val topCounterparties: List<NamedTotal>,
    val topCategories: List<NamedTotal>,
)

/**
 * Owns reporting math and export-file generation. Aggregation is pure and
 * synchronous (operates on an already-loaded transaction list) so it's trivial
 * to unit-test without Room; only the file write touches IO and is pushed onto
 * [Dispatchers.IO]. No network — summaries and CSV are fully offline, computed
 * from local records, honoring offline-first.
 */
@Singleton
class ReportRepository @Inject constructor(
    private val exportFileWriter: ExportFileWriter,
) {
    fun summarize(
        transactions: List<LocalTransactionEntity>,
        period: ReportPeriod,
    ): ReportSummary {
        val rows = transactions.filter { period.contains(it.transactionDate) }
        val moneyIn = rows.filter { it.direction == DIRECTION_IN }.sumOf { it.amountMinor }
        val moneyOut = rows.filter { it.direction == DIRECTION_OUT }.sumOf { it.amountMinor }
        return ReportSummary(
            period = period,
            moneyInMinor = moneyIn,
            moneyOutMinor = moneyOut,
            netMinor = moneyIn - moneyOut,
            recordCount = rows.size,
            topCounterparties = topBy(rows) { it.counterpartyName },
            topCategories = topBy(rows) { it.category },
        )
    }

    /**
     * Build a UTF-8 CSV of every record in [period]. Amounts are rendered as
     * major-unit decimals (e.g. 1250.00) for accountant/spreadsheet
     * readability; directions use the app's plain language ("Money in"/"Money
     * out"). Rows are ordered oldest-first, the natural reading order for a
     * statement.
     */
    fun buildCsv(
        transactions: List<LocalTransactionEntity>,
        period: ReportPeriod,
    ): String {
        val rows = transactions
            .filter { period.contains(it.transactionDate) }
            .sortedWith(compareBy({ it.transactionDate }, { it.createdAt }))
        val sb = StringBuilder()
        sb.append(CSV_HEADER).append("\r\n")
        for (row in rows) {
            sb.append(
                listOf(
                    row.transactionDate,
                    directionLabel(row.direction),
                    row.counterpartyName.orEmpty(),
                    row.category.orEmpty(),
                    majorAmount(row.amountMinor),
                    row.currency,
                    row.referenceNumber.orEmpty(),
                    row.bankName.orEmpty(),
                    row.note.orEmpty(),
                ).joinToString(",") { csvEscape(it) },
            ).append("\r\n")
        }
        return sb.toString()
    }

    /** Generate the CSV and hand back a shareable URI. IO runs off the main thread. */
    suspend fun exportCsv(
        transactions: List<LocalTransactionEntity>,
        period: ReportPeriod,
    ): Uri = withContext(Dispatchers.IO) {
        val content = buildCsv(transactions, period)
        exportFileWriter.writeText(csvFileName(period), content)
    }

    private fun topBy(
        rows: List<LocalTransactionEntity>,
        key: (LocalTransactionEntity) -> String?,
    ): List<NamedTotal> =
        rows
            .mapNotNull { row -> key(row)?.trim()?.takeIf { it.isNotBlank() }?.let { it to row.amountMinor } }
            .groupBy({ it.first }, { it.second })
            .map { (name, amounts) -> NamedTotal(name, amounts.sum()) }
            .sortedByDescending { it.totalMinor }
            .take(TOP_N)

    private companion object {
        const val DIRECTION_IN = "money_in"
        const val DIRECTION_OUT = "money_out"
        const val TOP_N = 5
        const val CSV_HEADER =
            "Date,Direction,Counterparty,Category,Amount,Currency,Reference,Bank,Note"

        fun directionLabel(direction: String): String = when (direction) {
            DIRECTION_IN -> "Money in"
            DIRECTION_OUT -> "Money out"
            else -> "Unknown"
        }

        fun majorAmount(amountMinor: Long): String {
            val whole = amountMinor / 100
            val fraction = (amountMinor % 100).toString().padStart(2, '0')
            return "$whole.$fraction"
        }

        fun csvFileName(period: ReportPeriod): String =
            "proofledger-${period.startIso}-to-${period.endIso}.csv"

        /** RFC-4180 quoting: wrap in quotes and double internal quotes when needed. */
        fun csvEscape(value: String): String =
            if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
                "\"" + value.replace("\"", "\"\"") + "\""
            } else {
                value
            }
    }
}
