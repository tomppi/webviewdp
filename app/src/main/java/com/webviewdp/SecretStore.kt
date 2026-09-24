package com.webviewdp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted store for the one secret this app owns: the harness session cookie.
 *
 * The key is generated in the Android Keystore and never leaves it, so the
 * ciphertext in SharedPreferences is unreadable to anything but this app on this
 * device. A missing or invalidated key is not an error: it means the session is
 * gone and the user pastes a fresh launch URL, exactly as after the 30-day
 * cookie expires.
 */
class SecretStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun put(name: String, value: String) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val body = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            prefs.edit()
                .putString("$name.iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString("$name.ct", Base64.encodeToString(body, Base64.NO_WRAP))
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "cannot store $name: ${e.message}")
        }
    }

    fun get(name: String): String? {
        return try {
            val iv = prefs.getString("$name.iv", null) ?: return null
            val body = prefs.getString("$name.ct", null) ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(body, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "cannot read $name: ${e.message}")
            null
        }
    }

    fun remove(name: String) {
        prefs.edit().remove("$name.iv").remove("$name.ct").apply()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "WebViewDP"
        const val PREFS = "webviewdp-secrets"
        const val KEY_ALIAS = "webviewdp-session"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
