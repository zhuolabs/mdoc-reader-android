package com.example.mdocreader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderScreenTest {
    @Test
    fun preferredFieldsAreDisplayedFirstAndOtherFieldsKeepTheirOrder() {
        val fields = listOf(
            field("family_name"),
            field("birth_date_unicode"),
            field("resident_address_unicode"),
            field("given_name"),
            field("portrait"),
            field("full_name_unicode"),
            field("expiry_date"),
        )

        assertEquals(
            listOf(
                "portrait",
                "full_name_unicode",
                "resident_address_unicode",
                "birth_date_unicode",
                "family_name",
                "given_name",
                "expiry_date",
            ),
            orderFieldsForDisplay(fields).map { it.name },
        )
    }

    private fun field(name: String) = ReaderField("namespace", name, "value", null)
}
