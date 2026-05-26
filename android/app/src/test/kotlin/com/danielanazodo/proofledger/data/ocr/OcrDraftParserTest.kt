package com.danielanazodo.proofledger.data.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrDraftParserTest {

    private val parser = OcrDraftParser()

    @Test
    fun `extracts amount labeled as Amount`() {
        val text = """
            GTBank
            Transfer Successful
            Amount: NGN 25,000.00
            Date: 24/05/2026
            Reference: TRF20260524ABC123
        """.trimIndent()

        val draft = parser.parse(text)

        assertEquals(2_500_000L, draft.amountMinor)
    }

    @Test
    fun `picks largest plausible amount when no label`() {
        // Receipts often include both the transaction amount and an account
        // balance. Without a label, the largest figure is the most likely
        // transaction amount.
        val text = """
            GTBank
            ₦15,000.00
            Balance ₦125,432.10
            Charge ₦26.88
        """.trimIndent()

        val draft = parser.parse(text)

        // Balance is bigger here — this is the known failure mode of the
        // unlabeled heuristic. The labeled path above is the preferred match.
        assertEquals(12_543_210L, draft.amountMinor)
    }

    @Test
    fun `prefers labeled amount over larger unlabeled value`() {
        val text = """
            Transfer Amount ₦5,000.00
            Available Balance ₦150,000.00
        """.trimIndent()

        val draft = parser.parse(text)

        assertEquals(500_000L, draft.amountMinor)
    }

    @Test
    fun `ignores tiny bare numbers when picking unlabeled amount`() {
        // "Page 1 of 2" and "Ref 7" must not be treated as ₦1 or ₦7.
        val text = """
            Receipt
            Page 1 of 2
            ₦12,500.00
            Ref 7
        """.trimIndent()

        val draft = parser.parse(text)

        assertEquals(1_250_000L, draft.amountMinor)
    }

    @Test
    fun `ignores account number when picking unlabeled amount`() {
        // GTCO-style receipt with no "Amount:" label, an account number
        // (10 bare digits), and a long reference. The real amount must win
        // even though the account number is a numerically larger digit run.
        val text = """
            Receipt
            10 Sep 2025 10:07, GMT+1
            N 8,000,000.00
            Eight Million Naira Zero Kobo
            Success
            Sender
            Yusuff-oladepo Owoade
            Receiver
            Van-daniels Properties Ltd
            Account number
            5540017919
            Receiving bank
            Fidelity Bank
            Reference number
            00001325091010074800040508618
        """.trimIndent()
        val draft = parser.parse(text)
        assertEquals(800_000_000L, draft.amountMinor)
    }

    @Test
    fun `parses slash date dd MM yyyy`() {
        val draft = parser.parse("Date: 24/05/2026")
        assertEquals("2026-05-24", draft.dateIso)
    }

    @Test
    fun `parses day month name year`() {
        val draft = parser.parse("Posted 24 May 2026")
        assertEquals("2026-05-24", draft.dateIso)
    }

    @Test
    fun `parses month name day year`() {
        val draft = parser.parse("May 24, 2026 09:31:02")
        assertEquals("2026-05-24", draft.dateIso)
    }

    @Test
    fun `rejects far future date`() {
        val draft = parser.parse("Date: 24/05/2099")
        assertNull(draft.dateIso)
    }

    @Test
    fun `rejects ancient date`() {
        val draft = parser.parse("Date: 24/05/1995")
        assertNull(draft.dateIso)
    }

    @Test
    fun `extracts reference after Ref label`() {
        val draft = parser.parse("Reference: TRF20260524XYZ987")
        assertEquals("TRF20260524XYZ987", draft.reference)
    }

    @Test
    fun `extracts reference after Transaction ID label`() {
        val draft = parser.parse("Transaction ID: 100023948571")
        assertEquals("100023948571", draft.reference)
    }

    @Test
    fun `extracts reference when value sits on line below label`() {
        val text = """
            Reference
            TRF20260524ABC123
        """.trimIndent()
        val draft = parser.parse(text)
        assertEquals("TRF20260524ABC123", draft.reference)
    }

    @Test
    fun `extracts reference when label is Reference Number on line above`() {
        val text = """
            Reference Number
            TRF20260524XYZ987
        """.trimIndent()
        val draft = parser.parse(text)
        assertEquals("TRF20260524XYZ987", draft.reference)
    }

    @Test
    fun `extracts reference when label is Reference No with value below`() {
        val text = """
            Reference No.
            ABC123456789
        """.trimIndent()
        val draft = parser.parse(text)
        assertEquals("ABC123456789", draft.reference)
    }

    @Test
    fun `extracts unformatted amount when label is on line above`() {
        // Real Zenith-style receipt: "Amount" label on its own line, value on
        // the next line as a bare digit run jammed against "Credit" with the
        // naira symbol OCR'd as a stray N. No commas, no decimal.
        val text = """
            Transaction Details
            Amount
            N78000000Credit
            Reference
            252440083011687
        """.trimIndent()
        val draft = parser.parse(text)
        assertEquals(7_800_000_000L, draft.amountMinor)
    }

    @Test
    fun `extracts reference from real receipt layout`() {
        // Mirrors a real "Transaction Details" screen — Reference label on
        // its own line, value on the line below, surrounded by other labeled
        // fields that must not be mistaken for the reference.
        val text = """
            Transaction Details
            Amount
            N78000000Credit
            Description
            NFT ONOSHOKEMEH PAULINA
            /PURCHASE OF 2 BED FLAT A
            Reference
            252440083011687
            Value Date
            9/1/2025 12:00:00 AM
            Transaction Date
            9/1/2025 5:20:11 PM
        """.trimIndent()
        val draft = parser.parse(text)
        assertEquals("252440083011687", draft.reference)
    }

    @Test
    fun `extracts RRN value on line below`() {
        val text = """
            RRN
            123456789012
        """.trimIndent()
        val draft = parser.parse(text)
        assertEquals("123456789012", draft.reference)
    }

    @Test
    fun `extracts reference across blank line`() {
        val text = "Reference\n\nTRF20260524ABC123"
        val draft = parser.parse(text)
        assertEquals("TRF20260524ABC123", draft.reference)
    }

    @Test
    fun `returns null reference when label missing`() {
        val draft = parser.parse("Just some payment text with no ref.")
        assertNull(draft.reference)
    }

    @Test
    fun `recognizes GTBank`() {
        val draft = parser.parse("Sent via GTBank Mobile")
        assertEquals("GTBank", draft.bankName)
    }

    @Test
    fun `recognizes Opay`() {
        val draft = parser.parse("OPay\nTransfer Successful")
        assertEquals("Opay", draft.bankName)
    }

    @Test
    fun `recognizes multi-word First Bank before generic`() {
        val draft = parser.parse("First Bank of Nigeria\nReceipt")
        assertEquals("First Bank", draft.bankName)
    }

    @Test
    fun `returns null bank for unknown name`() {
        val draft = parser.parse("Some Other Bank\nReceipt")
        assertNull(draft.bankName)
    }

    @Test
    fun `realistic Opay receipt parses end to end`() {
        val text = """
            OPay
            Transfer Successful
            Amount: ₦8,500.00
            To: Jane Doe
            Date: 24/05/2026 11:42
            Reference: OPAY24052600045
            Charge: ₦0.00
        """.trimIndent()

        val draft = parser.parse(text)

        assertEquals(850_000L, draft.amountMinor)
        assertEquals("2026-05-24", draft.dateIso)
        assertEquals("OPAY24052600045", draft.reference)
        assertEquals("Opay", draft.bankName)
        assertTrue(draft.hasAnyField())
    }

    @Test
    fun `empty text yields empty draft`() {
        val draft = parser.parse("")
        assertNull(draft.amountMinor)
        assertNull(draft.dateIso)
        assertNull(draft.reference)
        assertNull(draft.bankName)
        assertEquals("", draft.rawText)
        assertTrue(!draft.hasAnyField())
    }

    @Test
    fun `garbage text yields empty draft without crash`() {
        val draft = parser.parse("@@@ ### ??? \n \t ")
        assertNotNull(draft)
        assertNull(draft.amountMinor)
    }
}
