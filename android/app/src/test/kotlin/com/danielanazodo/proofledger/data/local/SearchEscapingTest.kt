package com.danielanazodo.proofledger.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchEscapingTest {

    @Test
    fun `escapes percent wildcard`() {
        assertEquals("50\\% off", escapeLikeTerm("50% off"))
    }

    @Test
    fun `escapes underscore wildcard`() {
        assertEquals("a\\_b", escapeLikeTerm("a_b"))
    }

    @Test
    fun `escapes backslash first so wildcard escapes are not double-escaped`() {
        // A literal backslash becomes two; a following % is escaped once.
        assertEquals("x\\\\\\%y", escapeLikeTerm("x\\%y"))
    }

    @Test
    fun `leaves a plain term untouched`() {
        assertEquals("Adewale", escapeLikeTerm("Adewale"))
    }

    @Test
    fun `handles empty string`() {
        assertEquals("", escapeLikeTerm(""))
    }
}
