package dev.xxemail.domain

import java.net.URI

/** HTTPS-only gate for optional remote images in HTML bodies. */
object RemoteImagePolicy {
    const val MAX_BYTES: Long = 2L * 1024 * 1024

    fun isHttpsUrl(source: String): Boolean {
        if (source.isBlank()) return false
        val uri = runCatching { URI(source.trim()) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
    }
}
