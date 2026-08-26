package dev.xxemail.domain

/**
 * Turns free-text user input into a safe SQLite FTS4 MATCH prefix query.
 *
 * Raw input must never reach MATCH: bare `AND` / `OR` / `NOT` / `NEAR` are operators,
 * and `"`, `(`, `)`, `*`, `:`, `-`, `^` are syntax — any of them throws a SQLite
 * malformed-match-expression error. Wrapping every whitespace-separated token in
 * double quotes makes it a literal phrase; a trailing `*` (outside the quotes)
 * keeps the existing prefix-search behavior.
 */
object FtsEscaper {

    fun escapePrefixQuery(raw: String): String = raw
        .split(Regex("\\s+"))
        .mapNotNull { token ->
            // Quotes cannot appear inside a quoted phrase un-doubled; dropping them is
            // simpler than doubling and keeps user-visible behavior forgiving.
            val cleaned = token.replace("\"", "")
            if (cleaned.isEmpty()) null else "\"$cleaned\"*"
        }
        .joinToString(" ")
}
