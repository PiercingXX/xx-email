package dev.xxemail

import dev.xxemail.sync.SendRetryPolicy
import dev.xxemail.sync.SendRetryPolicy.Outcome.MARK_FAILED
import dev.xxemail.sync.SendRetryPolicy.Outcome.MARK_SENT
import dev.xxemail.sync.SendRetryPolicy.Outcome.RETRY
import org.junit.Assert.assertEquals
import org.junit.Test

class SendRetryPolicyTest {

    @Test
    fun `200 with garbage body is marked sent and never retried`() {
        // A decode failure after 2xx reaches the policy as a definite success code.
        assertEquals(MARK_SENT, SendRetryPolicy.decide(httpCode = 200, transportError = false))
    }

    @Test
    fun `200 valid response is marked sent`() {
        assertEquals(MARK_SENT, SendRetryPolicy.decide(httpCode = 200, transportError = false))
        assertEquals(MARK_SENT, SendRetryPolicy.decide(httpCode = 201, transportError = false))
        assertEquals(MARK_SENT, SendRetryPolicy.decide(httpCode = 204, transportError = false))
    }

    @Test
    fun `500 is retried`() {
        assertEquals(RETRY, SendRetryPolicy.decide(httpCode = 500, transportError = false))
        assertEquals(RETRY, SendRetryPolicy.decide(httpCode = 503, transportError = false))
    }

    @Test
    fun `401 is marked failed without retry`() {
        assertEquals(MARK_FAILED, SendRetryPolicy.decide(httpCode = 401, transportError = false))
        assertEquals(MARK_FAILED, SendRetryPolicy.decide(httpCode = 400, transportError = false))
        assertEquals(MARK_FAILED, SendRetryPolicy.decide(httpCode = 403, transportError = false))
    }

    @Test
    fun `429 is retried`() {
        assertEquals(RETRY, SendRetryPolicy.decide(httpCode = 429, transportError = false))
    }

    @Test
    fun `transport error is retried`() {
        assertEquals(RETRY, SendRetryPolicy.decide(httpCode = null, transportError = true))
    }

    @Test
    fun `unknown outcomes are retried conservatively`() {
        assertEquals(RETRY, SendRetryPolicy.decide(httpCode = null, transportError = false))
        assertEquals(RETRY, SendRetryPolicy.decide(httpCode = 302, transportError = false))
    }
}
