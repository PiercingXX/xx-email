package dev.xxemail.data.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TokenCacheTest {

    /**
     * Models AppAuth's AuthState.performActionWithFreshTokens: every acquire completes;
     * a network refresh happens only while no valid token is held, consuming one scripted
     * outcome. A successful refresh grants validity (and rotates the snapshot unless disabled).
     */
    private class FakeSession(
        initialSnapshot: String,
        private val refreshOutcomes: ArrayDeque<TokenAcquisition>,
        private val rotateOnSuccess: Boolean = true,
    ) : TokenSession {
        var acquires = 0
            private set

        var refreshAttempts = 0
            private set

        private var snapshot = initialSnapshot
        private var heldToken: String? = null

        override fun serializedSnapshot(): String = snapshot

        override fun acquire(onDone: (TokenAcquisition) -> Unit) {
            acquires++
            if (heldToken != null) {
                onDone(TokenAcquisition.Success(heldToken!!))
                return
            }
            refreshAttempts++
            when (val outcome = refreshOutcomes.removeFirst()) {
                is TokenAcquisition.Success -> {
                    heldToken = outcome.accessToken
                    if (rotateOnSuccess) snapshot += "+rotated"
                    onDone(outcome)
                }
                else -> onDone(outcome)
            }
        }
    }

    /** Session whose refresh blocks mid-flight so a test can interleave a clear(). */
    private class BlockingSession(initialSnapshot: String) : TokenSession {
        val refreshStarted = CountDownLatch(1)
        val releaseRefresh = CountDownLatch(1)
        var acquires = 0
            private set

        private var snapshot = initialSnapshot

        override fun serializedSnapshot(): String = snapshot

        override fun acquire(onDone: (TokenAcquisition) -> Unit) {
            acquires++
            refreshStarted.countDown()
            releaseRefresh.await()
            snapshot += "+rotated"
            onDone(TokenAcquisition.Success("tok-stale"))
        }
    }

    private class Harness {
        val sessions = ArrayDeque<TokenSession>()
        val persisted = mutableListOf<Pair<String, String>>()
        var loads = 0

        val cache = TokenCache(
            loadSession = { _ ->
                loads++
                sessions.removeFirstOrNull() ?: error("no session queued")
            },
            persist = { email, snapshot -> persisted.add(email to snapshot) },
        )
    }

    @Test
    fun `expired token triggers one refresh and persists the rotated snapshot`() = runBlocking {
        val h = Harness()
        val session = FakeSession("snap-1", ArrayDeque(listOf(TokenAcquisition.Success("tok-1"))))
        h.sessions.add(session)

        val token = h.cache.withAccessToken("a@x") { it }

        assertEquals("tok-1", token)
        assertEquals(1, session.acquires)
        assertEquals(1, session.refreshAttempts)
        assertEquals(1, h.persisted.size)
        assertEquals("a@x" to "snap-1+rotated", h.persisted.single())
    }

    @Test
    fun `second call hits the in-memory cache without second refresh or persist`() = runBlocking {
        val h = Harness()
        val session = FakeSession("snap-1", ArrayDeque(listOf(TokenAcquisition.Success("tok-1"))))
        h.sessions.add(session)

        h.cache.withAccessToken("a@x") {}
        val second = h.cache.withAccessToken("a@x") { it }

        assertEquals(1, h.loads)
        assertEquals(2, session.acquires)
        assertEquals(1, session.refreshAttempts)
        assertEquals(1, h.persisted.size)
        assertEquals("tok-1", second)
    }

    @Test
    fun `unchanged snapshot after refresh is not persisted`() = runBlocking {
        val h = Harness()
        val session = FakeSession("snap-1", ArrayDeque(listOf(TokenAcquisition.Success("tok-1"))), rotateOnSuccess = false)
        h.sessions.add(session)

        h.cache.withAccessToken("a@x") {}

        assertEquals(1, session.refreshAttempts)
        assertTrue(h.persisted.isEmpty())
    }

    @Test
    fun `reauth outcome throws, flags the account and evicts the session`() = runBlocking {
        val h = Harness()
        h.sessions.add(FakeSession("snap-1", ArrayDeque(listOf(TokenAcquisition.ReauthNeeded("invalid_grant")))))

        val thrown = runCatching { h.cache.withAccessToken("a@x") { it } }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertEquals(setOf("a@x"), h.cache.reauthNeeded.value)
        assertTrue(h.persisted.isEmpty())

        // After signing in again a fresh session succeeds and the flag clears.
        h.sessions.add(FakeSession("snap-2", ArrayDeque(listOf(TokenAcquisition.Success("tok-2")))))
        assertEquals("tok-2", h.cache.withAccessToken("a@x") { it })
        assertFalse(h.cache.reauthNeeded.value.contains("a@x"))
    }

    @Test
    fun `transient failure throws but never flags re-auth`() = runBlocking {
        val h = Harness()
        h.sessions.add(FakeSession("snap-1", ArrayDeque(listOf(TokenAcquisition.Failed("network down")))))

        val thrown = runCatching { h.cache.withAccessToken("a@x") { it } }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertTrue(h.cache.reauthNeeded.value.isEmpty())
        // Session stays cached for retry.
        assertEquals(1, h.loads)
    }

    @Test
    fun `refresh completing after clear neither persists nor repopulates the cache`() = runBlocking {
        val h = Harness()
        val stale = BlockingSession("snap-stale")
        h.sessions.add(stale)

        // Refresh in flight while re-auth saves fresh tokens and clears the cache.
        val flow = launch(Dispatchers.IO) { h.cache.withAccessToken("a@x") {} }
        assertTrue(stale.refreshStarted.await(5, TimeUnit.SECONDS))
        h.cache.clear("a@x")
        stale.releaseRefresh.countDown()
        flow.join()

        // The stale-session rotation must never overwrite the fresh AuthState on disk.
        assertTrue(h.persisted.isEmpty())

        // The superseded session was evicted, not recached: next use reloads from disk.
        h.sessions.add(FakeSession("snap-fresh", ArrayDeque(listOf(TokenAcquisition.Success("tok-fresh")))))
        assertEquals("tok-fresh", h.cache.withAccessToken("a@x") { it })
        assertEquals(2, h.loads)
        assertEquals(listOf("a@x" to "snap-fresh+rotated"), h.persisted)
    }
}
