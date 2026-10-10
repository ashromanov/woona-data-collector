package com.example.myapplication.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AccountIdTest {
    @Test fun normalizesAsciiIdentifier() {
        assertEquals("dog-team_1.2", normalizeAccountId(" Dog-Team_1.2 "))
    }
    @Test fun rejectsInvalidIdentifiers() {
        listOf("", "a b", "собака", "../account", "a".repeat(65)).forEach {
            assertThrows(IllegalArgumentException::class.java) { normalizeAccountId(it) }
        }
    }
}
