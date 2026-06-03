package com.danielanazodo.proofledger.data.repository

import android.net.Uri
import com.danielanazodo.proofledger.data.export.ExportFileWriter
import com.danielanazodo.proofledger.data.local.SyncStatus
import com.danielanazodo.proofledger.data.local.entity.LocalTransactionEntity
import com.danielanazodo.proofledger.data.remote.ApiService
import com.danielanazodo.proofledger.ui.format.PeriodUnit
import com.danielanazodo.proofledger.ui.format.ReportPeriod
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class ReportRepositoryTest {

    private val repository = ReportRepository(NotUsedExportFileWriter, StubApiService())

    private val june = ReportPeriod(PeriodUnit.MONTH, "2026-06-01", "2026-06-30")

    @Test
    fun `exportPdf fetches server bytes and writes them verbatim`() = runBlocking {
        val pdfBytes = "%PDF-1.7 fake".toByteArray()
        val writer = RecordingExportFileWriter()
        val api = StubApiService(
            exportPdfBody = pdfBytes.toResponseBody("application/pdf".toMediaType()),
        )
        val repo = ReportRepository(writer, api)

        repo.exportPdf("business-1", june)

        assertEquals("business-1", api.exportPdfBusinessId)
        assertEquals("2026-06-01", api.exportPdfStart)
        assertEquals("2026-06-30", api.exportPdfEnd)
        assertEquals("proofledger-2026-06-01-to-2026-06-30.pdf", writer.lastBytesFileName)
        assertArrayEquals(pdfBytes, writer.lastBytes)
    }

    @Test
    fun `summarize totals only records inside the period`() {
        val transactions = listOf(
            tx(date = "2026-06-02", direction = "money_in", amountMinor = 100_00),
            tx(date = "2026-06-20", direction = "money_out", amountMinor = 40_00),
            // Outside June — must be ignored.
            tx(date = "2026-05-31", direction = "money_in", amountMinor = 999_00),
            tx(date = "2026-07-01", direction = "money_out", amountMinor = 999_00),
        )

        val summary = repository.summarize(transactions, june)

        assertEquals(100_00, summary.moneyInMinor)
        assertEquals(40_00, summary.moneyOutMinor)
        assertEquals(60_00, summary.netMinor)
        assertEquals(2, summary.recordCount)
    }

    @Test
    fun `summarize ranks top counterparties by total and caps at five`() {
        val transactions = (1..6).flatMap { i ->
            // Person i gets i transactions of 10_00 each → totals 10..60.
            List(i) { tx(date = "2026-06-10", direction = "money_in", amountMinor = 10_00, name = "Person $i") }
        }

        val top = repository.summarize(transactions, june).topCounterparties

        assertEquals(5, top.size)
        assertEquals("Person 6", top.first().name)
        assertEquals(60_00, top.first().totalMinor)
        // Person 1 (smallest) drops off the top-five.
        assertTrue(top.none { it.name == "Person 1" })
    }

    @Test
    fun `buildCsv emits a header and one row per in-period record oldest first`() {
        val transactions = listOf(
            tx(date = "2026-06-20", direction = "money_out", amountMinor = 250_00, name = "Musa"),
            tx(date = "2026-06-02", direction = "money_in", amountMinor = 1_250_00, name = "Ada"),
            tx(date = "2026-05-01", direction = "money_in", amountMinor = 1_00, name = "Old"),
        )

        val lines = repository.buildCsv(transactions, june).trim().split("\r\n")

        assertEquals("Date,Direction,Counterparty,Category,Amount,Currency,Reference,Bank,Note", lines[0])
        assertEquals(3, lines.size) // header + 2 June rows (May excluded)
        // Oldest first, amounts as major-unit decimals, plain-language direction.
        assertTrue(lines[1].startsWith("2026-06-02,Money in,Ada,,1250.00,NGN"))
        assertTrue(lines[2].startsWith("2026-06-20,Money out,Musa,,250.00,NGN"))
    }

    @Test
    fun `buildCsv quotes fields containing commas and quotes`() {
        val transactions = listOf(
            tx(
                date = "2026-06-05",
                direction = "money_in",
                amountMinor = 5_00,
                name = "Smith, Co",
                note = "Said \"thanks\"",
            ),
        )

        val row = repository.buildCsv(transactions, june).trim().split("\r\n")[1]

        assertTrue(row.contains("\"Smith, Co\""))
        assertTrue(row.contains("\"Said \"\"thanks\"\"\""))
    }

    @Test
    fun `buildCsv neutralizes formula injection in user fields (CWE-1236)`() {
        val transactions = listOf(
            tx(
                date = "2026-06-03",
                direction = "money_in",
                amountMinor = 10_00,
                name = "=HYPERLINK(\"http://evil\")",
                category = "-2+3",
                note = "@SUM(A1:A9)",
            ),
        )

        val row = repository.buildCsv(transactions, june).trim().split("\r\n")[1]

        // Each formula-triggering cell is prefixed with a single quote so a
        // spreadsheet treats it as text rather than executing it.
        assertTrue(row.contains("'=HYPERLINK"))
        assertTrue(row.contains("'-2+3"))
        assertTrue(row.contains("'@SUM(A1:A9)"))
    }

    @Test
    fun `summarize and buildCsv handle a large ledger`() {
        val transactions = (0 until 5_000).map { i ->
            tx(
                date = "2026-06-%02d".format((i % 28) + 1),
                direction = if (i % 2 == 0) "money_in" else "money_out",
                amountMinor = 100,
                name = "Person ${i % 50}",
            )
        }

        val summary = repository.summarize(transactions, june)
        assertEquals(5_000, summary.recordCount)
        assertEquals(2_500 * 100L, summary.moneyInMinor)
        assertEquals(2_500 * 100L, summary.moneyOutMinor)
        assertEquals(5, summary.topCounterparties.size)

        val lines = repository.buildCsv(transactions, june).trim().split("\r\n")
        assertEquals(5_001, lines.size) // header + 5000 rows
    }

    private fun tx(
        date: String,
        direction: String,
        amountMinor: Long,
        name: String? = null,
        category: String? = null,
        note: String? = null,
    ): LocalTransactionEntity = LocalTransactionEntity(
        id = "$date-$direction-$amountMinor-${name.orEmpty()}",
        businessId = "business-1",
        amountMinor = amountMinor,
        currency = "NGN",
        direction = direction,
        transactionDate = date,
        counterpartyId = null,
        counterpartyName = name,
        category = category,
        note = note,
        bankName = null,
        referenceNumber = null,
        source = "manual",
        status = "confirmed",
        createdAt = 0L,
        updatedAt = 0L,
        deletedAt = null,
        syncStatus = SyncStatus.SYNCED,
        lastSyncAttemptAt = null,
    )

    /** The pure summarize/buildCsv paths never touch the writer. */
    private object NotUsedExportFileWriter : ExportFileWriter {
        override fun writeText(fileName: String, content: String): Uri =
            throw UnsupportedOperationException("not used in these tests")

        override fun writeBytes(fileName: String, bytes: ByteArray): Uri =
            throw UnsupportedOperationException("not used in these tests")
    }

    /** Captures what exportPdf hands the writer, returning a stub Uri. */
    private class RecordingExportFileWriter : ExportFileWriter {
        var lastBytesFileName: String? = null
        var lastBytes: ByteArray? = null

        override fun writeText(fileName: String, content: String): Uri =
            throw UnsupportedOperationException("not used in these tests")

        override fun writeBytes(fileName: String, bytes: ByteArray): Uri {
            lastBytesFileName = fileName
            lastBytes = bytes
            // android.net.Uri can't be instantiated in a plain JVM test, so we
            // return a Mockito mock. The test asserts on the captured bytes;
            // the returned Uri is never dereferenced.
            return mock(Uri::class.java)
        }
    }

    /**
     * Only exportPdf is exercised; every other call is "not used". Records the
     * exportPdf args so the test can assert the period was passed as ISO dates.
     */
    private class StubApiService(
        private val exportPdfBody: ResponseBody? = null,
    ) : ApiService {
        var exportPdfBusinessId: String? = null
        var exportPdfStart: String? = null
        var exportPdfEnd: String? = null

        override suspend fun exportPdf(
            businessId: String,
            startDate: String,
            endDate: String,
        ): ResponseBody {
            exportPdfBusinessId = businessId
            exportPdfStart = startDate
            exportPdfEnd = endDate
            return exportPdfBody ?: error("no body configured")
        }

        override suspend fun health(): com.danielanazodo.proofledger.data.remote.HealthResponse =
            error("not used")
        override suspend fun getMe() = error("not used")
        override suspend fun updateMe(
            body: com.danielanazodo.proofledger.data.remote.dto.ProfileUpdateDto,
        ) = error("not used")
        override suspend fun listBusinesses() = error("not used")
        override suspend fun createBusiness(
            body: com.danielanazodo.proofledger.data.remote.dto.BusinessCreateDto,
        ) = error("not used")
        override suspend fun getBusiness(id: String) = error("not used")
        override suspend fun listCounterparties(
            businessId: String,
            search: String?,
            type: String?,
        ) = error("not used")
        override suspend fun createCounterparty(
            businessId: String,
            body: com.danielanazodo.proofledger.data.remote.dto.CounterpartyCreateDto,
        ) = error("not used")
        override suspend fun getCounterparty(businessId: String, counterpartyId: String) =
            error("not used")
        override suspend fun updateCounterparty(
            businessId: String,
            counterpartyId: String,
            body: com.danielanazodo.proofledger.data.remote.dto.CounterpartyUpdateDto,
        ) = error("not used")
        override suspend fun deleteCounterparty(businessId: String, counterpartyId: String) =
            error("not used")
        override suspend fun getCounterpartySummary(businessId: String, counterpartyId: String) =
            error("not used")
        override suspend fun listTransactions(
            businessId: String,
            direction: String?,
            search: String?,
            startDate: String?,
            endDate: String?,
        ) = error("not used")
        override suspend fun createTransaction(
            businessId: String,
            body: com.danielanazodo.proofledger.data.remote.dto.TransactionCreateDto,
        ) = error("not used")
        override suspend fun duplicateCandidates(
            businessId: String,
            body: com.danielanazodo.proofledger.data.remote.dto.DuplicateCandidateRequestDto,
        ) = error("not used")
        override suspend fun getTransaction(businessId: String, transactionId: String) =
            error("not used")
        override suspend fun updateTransaction(
            businessId: String,
            transactionId: String,
            body: com.danielanazodo.proofledger.data.remote.dto.TransactionUpdateDto,
        ) = error("not used")
        override suspend fun deleteTransaction(businessId: String, transactionId: String) =
            error("not used")
        override suspend fun uploadScreenshot(
            businessId: String,
            id: okhttp3.RequestBody,
            transactionId: okhttp3.RequestBody,
            originalFileSize: okhttp3.RequestBody,
            width: okhttp3.RequestBody,
            height: okhttp3.RequestBody,
            file: okhttp3.MultipartBody.Part,
        ) = error("not used")
        override suspend fun uploadOrphanScreenshot(
            businessId: String,
            id: okhttp3.RequestBody,
            originalFileSize: okhttp3.RequestBody,
            width: okhttp3.RequestBody,
            height: okhttp3.RequestBody,
            file: okhttp3.MultipartBody.Part,
        ) = error("not used")
        override suspend fun deleteScreenshot(screenshotId: String) = error("not used")
        override suspend fun getScreenshotSignedUrl(screenshotId: String) = error("not used")
        override suspend fun getScreenshotDraft(screenshotId: String) = error("not used")
        override suspend fun confirmDraft(
            draftId: String,
            body: com.danielanazodo.proofledger.data.remote.dto.TransactionDraftConfirmDto,
        ) = error("not used")
    }
}
