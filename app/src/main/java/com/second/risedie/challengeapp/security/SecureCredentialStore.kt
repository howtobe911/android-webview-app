package com.second.risedie.challengeapp.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Single protected native copy of the Web API access token.
 *
 * Existing plaintext Health Sync / push preferences are migrated once. Legacy
 * values are removed only after an encrypted write has been read back successfully.
 */
class SecureCredentialStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun writeAccessToken(token: String) {
        require(token.isNotBlank()) { "Access token must not be blank" }
        persistEncrypted(token, KEY_CIPHERTEXT, KEY_IV)
        check(decryptStored(KEY_CIPHERTEXT, KEY_IV) == token) { "Secure access-token readback failed" }
        clearLegacyTokens()
    }

    @Synchronized
    fun readAccessToken(): String? {
        decryptStored(KEY_CIPHERTEXT, KEY_IV)?.takeIf { it.isNotBlank() }?.let { return it }
        val legacy = readLegacyToken() ?: return null
        persistEncrypted(legacy, KEY_CIPHERTEXT, KEY_IV)
        if (decryptStored(KEY_CIPHERTEXT, KEY_IV) != legacy) {
            prefs.edit().remove(KEY_CIPHERTEXT).remove(KEY_IV).commit()
            return null
        }
        clearLegacyTokens()
        return legacy
    }

    @Synchronized
    fun clearAccessToken() {
        prefs.edit().remove(KEY_CIPHERTEXT).remove(KEY_IV).commit()
        clearLegacyTokens()
    }

    @Synchronized
    fun writeRefreshToken(token: String) {
        require(token.isNotBlank()) { "Refresh token must not be blank" }
        persistEncrypted(token, KEY_REFRESH_CIPHERTEXT, KEY_REFRESH_IV)
        check(decryptStored(KEY_REFRESH_CIPHERTEXT, KEY_REFRESH_IV) == token) { "Secure refresh-token readback failed" }
    }

    @Synchronized
    fun readRefreshToken(): String? =
        decryptStored(KEY_REFRESH_CIPHERTEXT, KEY_REFRESH_IV)?.takeIf { it.isNotBlank() }

    @Synchronized
    fun clearRefreshToken() {
        prefs.edit().remove(KEY_REFRESH_CIPHERTEXT).remove(KEY_REFRESH_IV).commit()
    }

    @Synchronized
    fun clearSessionTokens() {
        prefs.edit()
            .remove(KEY_CIPHERTEXT).remove(KEY_IV)
            .remove(KEY_REFRESH_CIPHERTEXT).remove(KEY_REFRESH_IV)
            .commit()
        clearLegacyTokens()
    }

    private fun persistEncrypted(token: String, ciphertextKey: String, ivKey: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val saved = prefs.edit()
            .putString(ciphertextKey, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(ivKey, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .commit()
        check(saved) { "Unable to persist secure access token" }
    }

    private fun decryptStored(ciphertextKey: String, ivKey: String): String? {
        val ciphertext = prefs.getString(ciphertextKey, null)?.takeIf { it.isNotBlank() } ?: return null
        val iv = prefs.getString(ivKey, null)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun readLegacyToken(): String? {
        val health = appContext.getSharedPreferences(LEGACY_HEALTH_PREFS, Context.MODE_PRIVATE)
            .getString(LEGACY_HEALTH_TOKEN, null)
            ?.takeIf { it.isNotBlank() }
        if (health != null) return health

        return appContext.getSharedPreferences(LEGACY_PUSH_PREFS, Context.MODE_PRIVATE)
            .getString(LEGACY_PUSH_TOKEN, null)
            ?.takeIf { it.isNotBlank() }
    }

    private fun clearLegacyTokens() {
        appContext.getSharedPreferences(LEGACY_HEALTH_PREFS, Context.MODE_PRIVATE)
            .edit().remove(LEGACY_HEALTH_TOKEN).commit()
        appContext.getSharedPreferences(LEGACY_PUSH_PREFS, Context.MODE_PRIVATE)
            .edit().remove(LEGACY_PUSH_TOKEN).commit()
    }

    companion object {
        private const val PREFS = "grafit_secure_credentials"
        private const val KEY_CIPHERTEXT = "access_token_ciphertext_v1"
        private const val KEY_IV = "access_token_iv_v1"
        private const val KEY_REFRESH_CIPHERTEXT = "refresh_token_ciphertext_v1"
        private const val KEY_REFRESH_IV = "refresh_token_iv_v1"
        private const val KEY_ALIAS = "grafit_access_token_aes_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128

        private const val LEGACY_HEALTH_PREFS = "grafit_native_health_sync"
        private const val LEGACY_HEALTH_TOKEN = "auth_token"
        private const val LEGACY_PUSH_PREFS = "grafit_push"
        private const val LEGACY_PUSH_TOKEN = "access_token"
    }
}
