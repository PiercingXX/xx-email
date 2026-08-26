package dev.xxemail.domain

import jakarta.mail.internet.InternetAddress

/**
 * Pure reply / reply-all recipient-set computation (E2/E4).
 *
 * Address lists are parsed with jakarta.mail's RFC 5322 parser in lenient mode so a
 * quoted display name containing a comma (`"Doe, Jane" <jane@x.com>`) stays ONE address —
 * never split(',') + contains('@').
 */
object Recipients {

    /**
     * Lenient RFC 5322 address-list parse. Never throws; entries the parser cannot
     * resolve to an address are dropped. Returns bare addresses (`jane@x.com`).
     *
     * A wholly unparseable list falls back to salvaging bracketed / bare addresses so
     * one malformed token cannot silently drop every valid recipient.
     */
    fun parse(headerValue: String): List<String> {
        if (headerValue.isBlank()) return emptyList()
        val parsed = runCatching {
            InternetAddress.parse(headerValue, false)
                .mapNotNull { it.address?.trim() }
                .filter { it.isNotEmpty() }
        }.getOrNull()
        if (parsed != null) return parsed
        // Wholly unparseable list: salvage bracketed addresses first, then any
        // email-shaped token, so one malformed entry cannot drop valid recipients.
        val bracketed = Regex("<([^<>]+@[^<>]+)>").findAll(headerValue)
            .map { it.groupValues[1].trim() }
            .filter { it.contains('@') }
            .toList()
        if (bracketed.isNotEmpty()) return bracketed
        return Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").findAll(headerValue)
            .map { it.value }
            .toList()
    }

    /** Send-time validation gate: keep only plausible addresses (local@domain). */
    fun parseValidated(raw: String): List<String> = parse(raw).filter { it.contains('@') }

    private fun normalize(address: String): String = address.lowercase()

    private fun isSelf(address: String, self: String): Boolean =
        self.isNotBlank() && normalize(address) == normalize(self)

    /**
     * Reply: To = original From, unless that is us — then fall back to the original To.
     */
    fun replyTo(fromHeader: String, toHeader: String, self: String): List<String> {
        val from = parse(fromHeader)
        return if (from.any { isSelf(it, self) }) parse(toHeader) else from
    }

    /**
     * Reply-all: To = From + original To; Cc = original Cc minus self minus anyone
     * already in To. Self is excluded everywhere; the original To recipients are kept.
     */
    fun replyAll(
        fromHeader: String,
        toHeader: String,
        ccHeader: String,
        self: String,
    ): Pair<List<String>, List<String>> {
        val from = parse(fromHeader)
        val to = parse(toHeader)
        val cc = parse(ccHeader)

        val toSet = (from + to)
            .filterNot { isSelf(it, self) }
            .distinctBy { normalize(it) }
        val ccSet = cc
            .filterNot { isSelf(it, self) }
            .filterNot { addr -> toSet.any { normalize(it) == normalize(addr) } }
            .distinctBy { normalize(it) }
        return toSet to ccSet
    }
}
