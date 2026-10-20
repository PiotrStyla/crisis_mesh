package dev.crisismesh

import org.junit.Assert.*
import org.junit.Test

class ContactHandoffTest {
    @Test fun keepsExplicitNumbersAndDeduplicatesWithoutInventingRecipients() {
        assertEquals(listOf("+48123123123", "5550100"), ContactHandoff.parse("+48123123123;5550100\n+48123123123"))
        assertTrue(ContactHandoff.parse("").isEmpty())
    }
    @Test fun rejectsUriInjectionShortCodesAndRecipientOverflow() {
        for (input in listOf("112", "5550100?body=spam", "+48123&to=99999", "55501,55502,55503,55504,55505,55506")) {
            assertThrows(IllegalArgumentException::class.java) { ContactHandoff.parse(input) }
        }
    }
}
