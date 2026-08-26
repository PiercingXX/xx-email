package dev.xxemail.sync

/**
 * Pure decision table for outbox send retries. A send that reached Gmail with HTTP 2xx
 * must NEVER be retried, even when the response body fails to decode — the message has
 * already left the device and a resend would duplicate it.
 */
object SendRetryPolicy {

    enum class Outcome { MARK_SENT, MARK_FAILED, RETRY }

    /**
     * @param httpCode HTTP status of messages.send, or null when no definite status is known.
     * @param transportError true for IO-level/unknown failures (DNS, timeout, reset) where
     *   delivery is unconfirmed; false when the server returned a definite status.
     */
    fun decide(httpCode: Int?, transportError: Boolean): Outcome = when {
        // Checked first: 2xx always wins — body decode problems are irrelevant to delivery.
        httpCode != null && httpCode in 200..299 -> Outcome.MARK_SENT
        transportError -> Outcome.RETRY
        httpCode == 429 -> Outcome.RETRY
        httpCode != null && httpCode in 500..599 -> Outcome.RETRY
        // Remaining 4xx are permanent rejections (auth, bad request, policy).
        httpCode != null && httpCode in 400..499 -> Outcome.MARK_FAILED
        else -> Outcome.RETRY
    }
}
