package com.danielanazodo.proofledger.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyAmountTest {
    @Test
    fun `keeps preferred size when amount fits`() {
        assertEquals(24f, adaptiveMoneyFontSize(8, 180f, 24f, 13f))
    }

    @Test
    fun `shrinks long amounts to available width`() {
        val size = adaptiveMoneyFontSize(18, 170f, 24f, 13f)

        assertTrue(size < 24f)
        assertTrue(size >= 13f)
    }

    @Test
    fun `never shrinks below minimum`() {
        assertEquals(13f, adaptiveMoneyFontSize(30, 80f, 24f, 13f))
    }
}
