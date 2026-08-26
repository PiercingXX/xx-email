package dev.xxemail.domain

import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

/** Mailbox views. Inbox tabs map to Gmail CATEGORY_* labels (see docs/cleanroom-gmail.md). */
enum class MailboxFolder(
    val title: String,
    val category: String? = null,
    val includeEmptyPrimary: Boolean = false,
    val labelId: String? = null,
) {
    PRIMARY("Primary", category = "CATEGORY_PERSONAL", includeEmptyPrimary = true),
    SOCIAL("Social", category = "CATEGORY_SOCIAL"),
    PROMOTIONS("Promotions", category = "CATEGORY_PROMOTIONS"),
    UPDATES("Updates", category = "CATEGORY_UPDATES"),
    FORUMS("Forums", category = "CATEGORY_FORUMS"),
    STARRED("Starred"),
    SNOOZED("Snoozed"),
    SENT("Sent", labelId = "SENT"),
    DRAFTS("Drafts", labelId = "DRAFT"),
    SPAM("Spam", labelId = "SPAM"),
    TRASH("Trash", labelId = "TRASH"),
    ALL_MAIL("All mail"),
}

val INBOX_TABS: List<MailboxFolder> =
    listOf(MailboxFolder.PRIMARY, MailboxFolder.SOCIAL, MailboxFolder.PROMOTIONS, MailboxFolder.UPDATES, MailboxFolder.FORUMS)

/** Label filter used when hydrating a non-inbox folder from the server. */
fun serverLabelFilter(folder: MailboxFolder): List<String>? = folder.labelId?.let { listOf(it) }

/** Snooze presets mirroring Gmail's UX constants (see docs/cleanroom-gmail.md). */
object SnoozePresets {

    fun laterToday(now: ZonedDateTime): ZonedDateTime {
        val plusFourHours = now.plusHours(4)
        val sixPm = now.toLocalDate().atTime(18, 0).atZone(now.zone)
        val chosen = minOf(plusFourHours, sixPm)
        // Less than ~2 hours out ⇒ not meaningfully "later today"; roll to tomorrow morning.
        return if (!chosen.isAfter(now.plusHours(2))) tomorrowMorning(now) else chosen
    }

    fun tomorrowMorning(now: ZonedDateTime): ZonedDateTime =
        now.plusDays(1).toLocalDate().atTime(8, 0).atZone(now.zone)

    fun nextWeek(now: ZonedDateTime): ZonedDateTime {
        var date = now.plusDays(1).toLocalDate()
        while (date.dayOfWeek != DayOfWeek.MONDAY) date = date.plusDays(1)
        return date.atTime(8, 0).atZone(now.zone)
    }

    fun weekend(now: ZonedDateTime): ZonedDateTime {
        var date = now.plusDays(1).toLocalDate()
        while (date.dayOfWeek != DayOfWeek.SATURDAY) date = date.plusDays(1)
        return date.atTime(8, 0).atZone(now.zone)
    }

    fun zoneOrDefault(zoneId: ZoneId?): ZoneId = zoneId ?: ZoneId.systemDefault()
}

/** Pure helpers for parsing display names/addresses out of RFC headers. */
object AddressUtils {
    /** "Jane Doe <jane@example.com>" -> ("Jane Doe", "jane@example.com") */
    fun split(headerValue: String): Pair<String, String> {
        val lt = headerValue.lastIndexOf('<')
        val gt = headerValue.lastIndexOf('>')
        return if (lt in 1 until gt) {
            headerValue.substring(0, lt).trim().trim('"') to headerValue.substring(lt + 1, gt).trim()
        } else {
            headerValue.trim() to headerValue.trim()
        }
    }

    fun initials(nameOrAddress: String): String {
        val trimmed = nameOrAddress.trim()
        if (trimmed.isEmpty()) return "?"
        return if (trimmed.contains('@')) {
            // Email address ⇒ derive from the local part ("alice@example.com" -> "AL").
            val local = trimmed.substringBefore('@')
            val words = local.split(Regex("[._\\-+]+")).filter { it.isNotBlank() }
            if (words.size >= 2) {
                words.take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("")
            } else {
                local.take(2).uppercase()
            }
        } else {
            // Display name ⇒ initial letters of the first two words.
            val words = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.size >= 2) {
                words.take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("")
            } else {
                words[0].take(2).uppercase()
            }
        }.ifEmpty { "?" }
    }
}
