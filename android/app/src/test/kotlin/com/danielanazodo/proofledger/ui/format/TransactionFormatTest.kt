package com.danielanazodo.proofledger.ui.format

import org.junit.Assert.assertEquals
import org.junit.Test

class TransactionFormatTest {
    @Test
    fun `formats typed whole amounts with comma groups`() {
        assertEquals("1,000", formatNairaInput("1000"))
        assertEquals("1,234,567", formatNairaInput("1234567"))
        assertEquals("1,234,567", formatNairaInput("1,234,567"))
    }

    @Test
    fun `formats typed decimal amounts while preserving decimal entry`() {
        assertEquals("1,000.", formatNairaInput("1000."))
        assertEquals("1,000.5", formatNairaInput("1000.5"))
        assertEquals("1,000.56", formatNairaInput("1000.567"))
        assertEquals("0.5", formatNairaInput(".5"))
    }

    @Test
    fun `formats minor unit amounts for ai and existing records`() {
        assertEquals("8,500", formatNairaInput(850_000L))
        assertEquals("8,500.75", formatNairaInput(850_075L))
    }

    @Test
    fun `parses comma grouped amount for save`() {
        assertEquals(123_456_789L, parseNairaToMinor("1,234,567.89"))
    }
}
