package dev.xxemail.domain

/**
 * Exact-token matching and editing for comma-joined Gmail label id lists.
 *
 * Matching always wraps both sides in commas — `,SENT,CONSENT,` searched for
 * `,SENT,` does not match — so a label only ever matches itself:
 * user label CONSENT never satisfies a SENT filter, TRASH never matches a
 * label whose id merely contains "TRASH" as a substring.
 *
 * The Room queries mirror this exactly via
 * `instr(',' || labelsCsv || ',', ',' || :label || ',') > 0` (byte-exact token
 * match — SQLite LIKE would case-fold ASCII and treat %/_ as wildcards).
 */
object LabelCsv {

    fun contains(csv: String, label: String): Boolean =
        label.isNotEmpty() && wrap(csv).contains(",$label,")

    fun add(csv: String, label: String): String {
        if (label.isEmpty() || contains(csv, label)) return csv
        return (tokens(csv) + label).joinToString(",")
    }

    fun remove(csv: String, label: String): String =
        tokens(csv).filterNot { it == label }.joinToString(",")

    private fun wrap(csv: String): String = ",${csv.trim(',')},"

    private fun tokens(csv: String): List<String> =
        csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}
