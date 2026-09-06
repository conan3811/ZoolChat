package com.zoolchat.app.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small Android-Keystore backed value protector for access/refresh tokens. */
class SecureValueStore(context: Context, private val alias: String = "zoolchat_session_v1") {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun protect(value: String): String {
        if (value.startsWith(PREFIX)) return value
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // AndroidKeyStore AES-GCM keys must let the provider create the encryption IV.
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        require(iv.size == 12) { "bad_generated_gcm_iv" }
        val out = iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }

    fun reveal(value: String?): String? {
        if (value == null) return null
        if (!value.startsWith(PREFIX)) return value // transparently migrate legacy plaintext on next write
        return runCatching {
            val all = Base64.decode(value.removePrefix(PREFIX), Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
            require(all.size > 28)
            val iv = all.copyOfRange(0, 12)
            val ciphertext = all.copyOfRange(12, all.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    companion object { private const val PREFIX = "zcsv1:" }
}
