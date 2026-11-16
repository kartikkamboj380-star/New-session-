package com.universalbrain.ai.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore-backed secret storage. AES-256/GCM key is generated inside the
 * Android Keystore (non-exportable); values are encrypted with a random IV
 * and persisted as base64(iv || ciphertext) in app-private preferences.
 *
 * Account passwords/tokens and LLM API keys are stored here — never in the
 * Room database in plain text.
 */
class SecureStore(private val context: Context) {

    private val prefs by lazy {
        context.getSharedPreferences("uba_secure", Context.MODE_PRIVATE)
    }

    private fun obtainKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val out = ByteArray(iv.size + encrypted.size)
        iv.copyInto(out)
        encrypted.copyInto(out, iv.size)
        return android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String? = runCatching {
        val data = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, obtainKey(), GCMParameterSpec(128, data, 0, IV_SIZE))
        String(cipher.doFinal(data, IV_SIZE, data.size - IV_SIZE), Charsets.UTF_8)
    }.getOrNull()

    /** Stores a secret and returns the reference key to persist in Room. */
    fun put(key: String, secret: String): String {
        prefs.edit().putString(key, encrypt(secret)).apply()
        return key
    }

    fun get(key: String): String? =
        prefs.getString(key, null)?.let { decrypt(it) }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    fun entryCount(): Int = prefs.all.size

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "uba_master_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
    }
}
