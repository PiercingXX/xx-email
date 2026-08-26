package dev.xxemail.data.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Outcome of one token acquisition attempt, decoupled from AppAuth types for JVM tests. */
sealed interface TokenAcquisition {
    data class Success(val accessToken: String) : TokenAcquisition

    /** Refresh failed permanently (e.g. invalid_grant): user must sign in again. */
    data class ReauthNeeded(val error: String?) : TokenAcquisition

    /** Transient failure (network, unknown): retry later, no re-auth implied. */
    data class Failed(val reason: String) : TokenAcquisition
}

/**
 * Minimal OAuth-state surface [TokenCache] coordinates around. Production wraps
 * AppAuth's AuthState; unit tests substitute fakes so this class stays JVM-pure.
 */
interface TokenSession {
    /** Serialized snapshot; compared before/after refresh to detect changes. */
    fun serializedSnapshot(): String

    /** Ensure a usable access token, completing with exactly one [TokenAcquisition]. */
    fun acquire(onDone: (TokenAcquisition) -> Unit)
}

/**
 * In-memory per-account token cache with change-detecting persistence.
 *
 * - First use loads the session once via [loadSession]; later calls hit memory
 *   (GmailApiFactory's interceptor therefore never re-reads/decrypts disk per request).
 * - Acquisitions are serialized per account (no duplicate refresh bursts); the caller's
 *   network callback runs outside the lock.
 * - If the session snapshot changed after a refresh, it is persisted via [persist].
 * - Every [clear] bumps a per-account generation: an in-flight refresh from a
 *   superseded session (e.g. completing just after re-auth saved fresh tokens)
 *   is never persisted nor left cached.
 * - Permanent refresh failures surface in [reauthNeeded] for "sign in again" UX.
 */
class TokenCache(
    private val loadSession: suspend (email: String) -> TokenSession?,
    private val persist: suspend (email: String, snapshot: String) -> Unit,
) {

    private val _reauthNeeded = MutableStateFlow<Set<String>>(emptySet())
    val reauthNeeded: StateFlow<Set<String>> get() = _reauthNeeded.asStateFlow()

    /** Guards the non-suspending maps below; never held across suspension. */
    private val guard = Any()
    private val sessions = HashMap<String, TokenSession>()
    private val locks = HashMap<String, Mutex>()
    private val generations = HashMap<String, Int>()

    suspend fun <T> withAccessToken(email: String, block: (String) -> T): T {
        val (acquisition, changedSnapshot) = acquireFresh(email)
        return when (acquisition) {
            is TokenAcquisition.Success -> {
                _reauthNeeded.update { it - email }
                if (changedSnapshot != null) persist(email, changedSnapshot)
                block(acquisition.accessToken)
            }
            is TokenAcquisition.ReauthNeeded -> throw IllegalStateException(
                "Session expired (${acquisition.error}); sign in again to reconnect $email.",
            )
            is TokenAcquisition.Failed -> throw IllegalStateException(acquisition.reason)
        }
    }

    /** Drop the cached session (after sign-out or a fresh authorization result). */
    fun clear(email: String) {
        synchronized(guard) {
            sessions.remove(email)
            // Invalidate any refresh in flight against the old session: when it
            // completes it must neither persist nor recache its stale snapshot.
            generations[email] = generations.getOrDefault(email, 0) + 1
        }
        _reauthNeeded.update { it - email }
    }

    private suspend fun acquireFresh(email: String): Pair<TokenAcquisition, String?> =
        lockFor(email).withLock {
            val generation = generationOf(email)
            val session = cachedSession(email)
                ?: loadSession(email)?.also { cacheSession(email, it) }
                ?: error("Account not authorized: $email")

            val before = session.serializedSnapshot()
            val acquisition = suspendCancellableCoroutine { cont ->
                session.acquire(onDone = { cont.resume(it) })
            }
            val after = session.serializedSnapshot()
            if (generationOf(email) != generation) {
                // Superseded mid-refresh by clear(): drop everything derived from
                // this stale session so the next use reloads fresh tokens.
                synchronized(guard) { sessions.remove(email) }
                return@withLock acquisition to null
            }
            if (acquisition is TokenAcquisition.ReauthNeeded) {
                synchronized(guard) { sessions.remove(email) }
                _reauthNeeded.update { it + email }
            }
            acquisition to after.takeIf { it != before }
        }

    private fun cachedSession(email: String): TokenSession? = synchronized(guard) { sessions[email] }

    private fun cacheSession(email: String, session: TokenSession) {
        synchronized(guard) { sessions[email] = session }
    }

    private fun generationOf(email: String): Int = synchronized(guard) { generations.getOrDefault(email, 0) }

    private fun lockFor(email: String): Mutex = synchronized(guard) { locks.getOrPut(email) { Mutex() } }
}
