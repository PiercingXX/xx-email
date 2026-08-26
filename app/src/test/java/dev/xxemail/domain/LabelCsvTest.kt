package dev.xxemail.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelCsvTest {

    @Test
    fun `exact token match - SENT never matches CONSENT`() {
        // The old substring query (LIKE '%SENT%') wrongly matched user label "CONSENT" into Sent.
        assertFalse(LabelCsv.contains("CONSENT", "SENT"))
        assertFalse(LabelCsv.contains("INBOX,CONSENT", "SENT"))
    }

    @Test
    fun `TRASH does not match a label containing TRASH as a substring`() {
        assertFalse(LabelCsv.contains("MYTRASHFOLDER", "TRASH"))
        assertTrue(LabelCsv.contains("MYTRASHFOLDER", "MYTRASHFOLDER"))
    }

    @Test
    fun `real labels match exactly`() {
        assertTrue(LabelCsv.contains("INBOX,TRASH,UNREAD", "TRASH"))
        assertTrue(LabelCsv.contains("TRASH", "TRASH"))
        assertFalse(LabelCsv.contains("INBOX,CATEGORY_PERSONAL", "CATEGORY_PERSON"))
        assertFalse(LabelCsv.contains("INBOX", ""))
        assertFalse(LabelCsv.contains("", "INBOX"))
    }

    @Test
    fun `add appends and dedupes`() {
        assertEquals("INBOX,TRASH", LabelCsv.add("INBOX", "TRASH"))
        assertEquals("INBOX,TRASH", LabelCsv.add("INBOX,TRASH", "TRASH"))
        assertEquals("TRASH", LabelCsv.add("", "TRASH"))
        assertEquals("INBOX", LabelCsv.add("INBOX", ""))
    }

    @Test
    fun `remove drops only the exact token`() {
        assertEquals("INBOX", LabelCsv.remove("INBOX,TRASH", "TRASH"))
        assertEquals("TRASH", LabelCsv.remove("MYTRASHFOLDER,TRASH", "MYTRASHFOLDER"))
        assertEquals("", LabelCsv.remove("TRASH", "TRASH"))
        assertEquals("INBOX", LabelCsv.remove("INBOX", "MISSING"))
    }

    @Test
    fun `add remove roundtrip restores original csv`() {
        val csv = "INBOX,CATEGORY_PROMOTIONS,UNREAD"
        assertEquals(csv, LabelCsv.remove(LabelCsv.add(csv, "TEMP"), "TEMP"))
    }
}
