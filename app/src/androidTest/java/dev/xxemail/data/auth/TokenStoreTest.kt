package dev.xxemail.data.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.openid.appauth.AuthState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TokenStoreTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = InstrumentationRegistry.getInstrumentation()
            .targetContext.cacheDir
            .resolve("tokenstore-test-${System.nanoTime()}")
        assertTrue(dir.mkdirs())
        assertTrue(dir.isDirectory)
        assertTrue(dir.canWrite())
    }

    @After
    fun tearDown() {
        dir.setWritable(true)
        dir.deleteRecursively()
    }

    @Test
    fun saveSerialized_thenLoad_roundTripsPerAccount() = runBlocking {
        val store = TokenStore(dir)

        store.saveSerialized(EMAIL_A, JSON_A)
        store.saveSerialized(EMAIL_B, JSON_B)

        assertEquals(setOf(EMAIL_A, EMAIL_B), store.allEmails().toSet())

        val loadedA = store.load(EMAIL_A)
        assertNotNull(loadedA)
        assertEquals("jwt-alice", loadedA!!.idToken)
        val loadedB = store.load(EMAIL_B)
        assertNotNull(loadedB)
        assertEquals("jwt-bob", loadedB!!.idToken)
        assertEquals("rt-bob", loadedB.refreshToken)

        assertEquals(
            AuthState.jsonDeserialize(JSON_A).jsonSerializeString(),
            loadedA.jsonSerializeString(),
        )
        assertEquals(
            AuthState.jsonDeserialize(JSON_B).jsonSerializeString(),
            loadedB.jsonSerializeString(),
        )
        assertEquals(false, store.unreadable.value)
    }

    @Test
    fun remove_deletesOnlyTargetedAccount() = runBlocking {
        val store = TokenStore(dir)
        store.saveSerialized(EMAIL_A, JSON_A)
        store.saveSerialized(EMAIL_B, JSON_B)

        store.remove(EMAIL_A)

        assertEquals(listOf(EMAIL_B), store.allEmails())
        assertEquals(null, store.load(EMAIL_A))
        assertNotNull(store.load(EMAIL_B))
    }
    @Test
    fun failedPersist_throws_andOriginalFileKeepsPreviousGoodContent() = runBlocking {
        val store = TokenStore(dir)
        store.saveSerialized(EMAIL_A, JSON_A)
        val originalBytes = File(dir, "authstates.bin").readBytes()

        assertTrue("chmod u-w must succeed to force the persist failure", dir.setWritable(false))
        try {
            val outcome = runCatching { store.saveSerialized(EMAIL_B, JSON_B) }
            assertTrue(
                "persist must throw when the store cannot be written: ${outcome.exceptionOrNull()}",
                outcome.isFailure,
            )
        } finally {
            assertTrue(dir.setWritable(true))
        }

        assertTrue(
            "original authstates.bin must be byte-for-byte intact after failed persist",
            originalBytes.contentEquals(File(dir, "authstates.bin").readBytes()),
        )

        val fresh = TokenStore(dir)
        val recovered = fresh.load(EMAIL_A)
        assertNotNull(recovered)
        assertEquals("jwt-alice", recovered!!.idToken)
        assertEquals(listOf(EMAIL_A), fresh.allEmails())
        assertEquals(false, fresh.unreadable.value)
    }

    private companion object {
        const val EMAIL_A = "alice@dev.xxemail"
        const val EMAIL_B = "bob@dev.xxemail"
        const val JSON_A = """{"idToken":"jwt-alice"}"""
        const val JSON_B = """{"idToken":"jwt-bob","refreshToken":"rt-bob"}"""
    }
}
