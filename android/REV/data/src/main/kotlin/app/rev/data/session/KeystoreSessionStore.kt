package app.rev.data.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the session encrypted with an AES-256-GCM key held in the Android Keystore.
 * The key never leaves secure hardware (where available), so the ciphertext in app storage is
 * useless if copied off the device.
 */
class KeystoreSessionStore(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher,
) : SessionStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val state = MutableStateFlow<StoredSession?>(null)
    private val mutex = Mutex()
    private var loaded = false

    override val session: Flow<StoredSession?> = flow {
        ensureLoaded()
        emitAll(state)
    }

    override suspend fun current(): StoredSession? {
        ensureLoaded()
        return state.value
    }

    override suspend fun save(session: StoredSession) = mutex.withLock {
        withContext(ioDispatcher) {
            val plaintext = Json.encodeToString(StoredSession.serializer(), session)
            prefs.edit(commit = true) { putString(PREF_SESSION, encrypt(plaintext)) }
        }
        loaded = true
        state.value = session
    }

    override suspend fun clear() = mutex.withLock {
        withContext(ioDispatcher) { prefs.edit(commit = true) { remove(PREF_SESSION) } }
        loaded = true
        state.value = null
    }

    private suspend fun ensureLoaded() = mutex.withLock {
        if (loaded) return@withLock
        state.value = withContext(ioDispatcher) { read() }
        loaded = true
    }

    private fun read(): StoredSession? {
        val stored = prefs.getString(PREF_SESSION, null) ?: return null
        return try {
            Json.decodeFromString(StoredSession.serializer(), decrypt(stored))
        } catch (_: Exception) {
            // Key lost (e.g. device restored, lock screen reset) or data corrupt: start signed out.
            prefs.edit(commit = true) { remove(PREF_SESSION) }
            null
        }
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return b64(cipher.iv) + ":" + b64(ciphertext)
    }

    private fun decrypt(stored: String): String {
        val (iv, ciphertext) = stored.split(':', limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private companion object {
        const val PREFS_NAME = "rev_session"
        const val PREF_SESSION = "session"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "rev_session_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
