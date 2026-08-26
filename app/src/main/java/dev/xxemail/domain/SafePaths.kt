package dev.xxemail.domain

import java.io.File

/**
 * Pure filename/path sanitization for anything that becomes a file on disk
 * (attachment downloads, content-provider uploads). JVM-unit-testable by design.
 *
 * Threat model: a hostile server-controlled `filename` (or a crafted provider
 * display name) must never escape its designated cache directory via traversal
 * (`../`), absolute paths, separators, or NUL-byte tricks.
 */
object SafePaths {

    /** Keep names well under any filesystem's 255-byte component limit. */
    const val MAX_NAME_LENGTH = 200

    /**
     * Reduces [raw] to a safe single path segment, or returns null when nothing
     * safe remains. Rules: strip every directory component (both `/` and `\`),
     * reject blank / "." / ".." / NUL bytes, cap length.
     */
    fun childName(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        if (raw.indexOf('\u0000') >= 0) return null
        var name = raw.replace('\\', '/').substringAfterLast('/').trim()
        // Trailing dots/dots-only names are a classic Windows/Win32 hazard ("..", "...").
        name = name.trimEnd('.')
        if (name.isEmpty() || name == "." || name == "..") return null
        return name.take(MAX_NAME_LENGTH)
    }

    /**
     * Convenience overload: sanitized [raw], else a sanitized [fallbackSeed],
     * else [lastResort]. Never returns null.
     */
    fun childNameOr(raw: String?, fallbackSeed: String?, lastResort: String): String =
        childName(raw) ?: childName(fallbackSeed) ?: lastResort

    /** True only when [candidate] resolves strictly inside [dir] (canonical paths). */
    fun isInside(dir: File, candidate: File): Boolean {
        val dirPath = dir.canonicalFile.path
        return candidate.canonicalFile.path.startsWith(dirPath + File.separator)
    }
}
