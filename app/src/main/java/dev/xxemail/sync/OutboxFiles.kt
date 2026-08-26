package dev.xxemail.sync

import dev.xxemail.domain.SafePaths
import java.io.File

/**
 * File-backed storage for outbox RFC822 payloads. Bytes live under the
 * app-private `files/outbox/{id}.eml` so Room rows never carry megabytes of
 * base64 through a CursorWindow. Stored paths are relative to `filesDir`
 * and validated on every read.
 */
object OutboxFiles {

    private const val DIR_NAME = "outbox"
    private const val EXT = ".eml"
    private const val TMP_EXT = ".eml.tmp"

    fun relativePath(id: Long): String = "$DIR_NAME/$id$EXT"

    /** Durably writes the payload (tmp + rename); returns the relative path to store in Room. */
    fun writeNew(filesDir: File, id: Long, bytes: ByteArray): String {
        val dir = File(filesDir, DIR_NAME).apply { mkdirs() }
        val target = File(dir, "$id$EXT")
        check(SafePaths.isInside(dir, target)) { "Refusing to write outside $DIR_NAME" }
        val tmp = File(dir, "$id$TMP_EXT")
        try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) error("Could not persist outbox payload #$id")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        return relativePath(id)
    }

    /**
     * Resolves a stored relative path to an existing file strictly inside
     * `filesDir/outbox`; null for anything malformed, escaping, or missing.
     */
    fun resolve(filesDir: File, storedPath: String?): File? {
        if (storedPath.isNullOrBlank() || storedPath.contains("..") || storedPath.startsWith('/')) return null
        val dir = File(filesDir, DIR_NAME)
        val candidate = File(filesDir, storedPath)
        if (!SafePaths.isInside(dir, candidate)) return null
        return if (candidate.isFile) candidate else null
    }

    /** Best-effort delete of a row's payload file (and any stale tmp). */
    fun deletePayloadFile(filesDir: File, storedPath: String?, id: Long) {
        resolve(filesDir, storedPath ?: relativePath(id))?.delete()
        File(File(filesDir, DIR_NAME), "$id$TMP_EXT").delete()
    }
}
