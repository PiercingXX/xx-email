package dev.xxemail.data.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthState
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores one serialized [AuthState] per account, encrypted at rest with an
 * AndroidKeyStore AES-GCM key (hardware-backed where available).
 */
class TokenStore(private val filesDir: File) {

    private val file = File(filesDir, "authstates.bin")
    private val mutex = Mutex()
    private var cache: MutableMap<String, String>? = null

    /** True when authstates.bin exists but could not be read/decrypted. */
    private val _unreadable = MutableStateFlow(false)
    val unreadable: StateFlow<Boolean> = _unreadable.asStateFlow()

    suspend fun save(email: String, state: AuthState): Unit = saveSerialized(email, state.jsonSerializeString())

    suspend fun saveSerialized(email: String, json: String): Unit = locked {
        val map = loadMap().toMutableMap()
        map[email] = json
        persist(map)
    }

    suspend fun load(email: String): AuthState? = locked {
        loadMap()[email]?.let { json ->
            runCatching { AuthState.jsonDeserialize(json) }
                .onFailure { Log.w(TAG, "Corrupt AuthState for $email", it) }
                .getOrNull()
        }
    }

    suspend fun remove(email: String): Unit = locked {
        val map = loadMap().toMutableMap()
        map.remove(email)
        persist(map)
    }

    suspend fun allEmails(): List<String> = locked { loadMap().keys.toList() }

    // --- internals (all called under [mutex]) ---

    private suspend fun <T> locked(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { mutex.withLock { block() } }

    private fun loadMap(): Map<String, String> {
        cache?.let { return it }
        if (!file.exists()) return emptyMap<String, String>().also { cache = it.toMutableMap() }
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                val count = input.readInt()
                val out = LinkedHashMap<String, String>(count)
                repeat(count) {
                    val email = input.readUTF()
                    val blob = ByteArray(input.readInt()).also { input.readFully(it) }
                    out[email] = String(decrypt(blob), Charsets.UTF_8)
                }
                out
            }.also {
                cache = it.toMutableMap()
                _unreadable.value = false
            }
        } catch (t: Throwable) {
            // The file exists but cannot be read/decrypted (e.g. keystore key lost).
            // Never pretend the store is empty: flag it so the UI can ask for re-auth
            // instead of silently leaving a zombie account behind.
            Log.w(TAG, "Token store unreadable; treating as empty and flagging", t)
            _unreadable.value = true
            mutableMapOf<String, String>().also { cache = it }
        }
    }

    private fun persist(map: Map<String, String>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { raw ->
            DataOutputStream(raw.buffered()).use { output ->
                output.writeInt(map.size)
                map.forEach { (email, json) ->
                    val blob = encrypt(json.toByteArray(Charsets.UTF_8))
                    output.writeUTF(email)
                    output.writeInt(blob.size)
                    output.write(blob)
                }
                output.flush()
            }
            raw.fd.sync() // fsync before rename so a crash can't leave an empty/truncated store
        }
        if (!tmp.renameTo(file)) {
            // Do NOT delete the original file on failed rename — old credentials stay intact.
            tmp.delete()
            throw IllegalStateException("Could not persist token store atomically; previous file left untouched")
        }
        cache = map.toMutableMap()
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        return ByteArray(iv.size + ct.size).also { iv.copyInto(it); ct.copyInto(it, iv.size) }
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, blob, 0, IV_LEN))
        return cipher.doFinal(blob, IV_LEN, blob.size - IV_LEN)
    }

    companion object {
        private const val TAG = "TokenStore"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "xxemail_master"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_LEN = 12
    }
}
