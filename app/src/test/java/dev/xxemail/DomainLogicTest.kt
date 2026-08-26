package dev.xxemail

import dev.xxemail.domain.AddressUtils
import dev.xxemail.domain.SnoozePresets
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class DomainLogicTest {

    private val zone = ZoneId.of("UTC")
    private fun at(hour: Int, minute: Int = 0) =
        ZonedDateTime.of(2026, 8, 25, hour, minute, 0, 0, zone) // Tuesday

    @Test
    fun `later today caps at 6pm`() {
        val now = at(15)
        assertEquals(at(18).toInstant(), SnoozePresets.laterToday(now).toInstant())
    }

    @Test
    fun `later today rolls to tomorrow when too close to 6pm`() {
        val now = at(17)
        assertEquals(at(8).plusDays(1).toInstant(), SnoozePresets.laterToday(now).toInstant())
    }

    @Test
    fun `later today is plus four hours in the morning`() {
        val now = at(9)
        assertEquals(at(13).toInstant(), SnoozePresets.laterToday(now).toInstant())
    }

    @Test
    fun `next week lands on monday 8am`() {
        val wednesday = ZonedDateTime.of(2026, 8, 26, 12, 0, 0, 0, zone)
        val expected = ZonedDateTime.of(2026, 8, 31, 8, 0, 0, 0, zone) // Monday
        assertEquals(expected.toInstant(), SnoozePresets.nextWeek(wednesday).toInstant())
    }

    @Test
    fun `weekend lands on saturday 8am`() {
        val tuesday = at(10)
        val expected = ZonedDateTime.of(2026, 8, 29, 8, 0, 0, 0, zone) // Saturday
        assertEquals(expected.toInstant(), SnoozePresets.weekend(tuesday).toInstant())
    }

    @Test
    fun `address splitting handles display names`() {
        val (name, address) = AddressUtils.split("Jane Doe <jane@example.com>")
        assertEquals("Jane Doe", name)
        assertEquals("jane@example.com", address)
    }

    @Test
    fun `address splitting falls back to bare address`() {
        val (name, address) = AddressUtils.split("bob@example.com")
        assertEquals("bob@example.com", name)
        assertEquals("bob@example.com", address)
    }

    @Test
    fun `initials derive from display name`() {
        assertEquals("JD", AddressUtils.initials("Jane Doe"))
        assertEquals("AL", AddressUtils.initials("alice@example.com"))
    }
}
