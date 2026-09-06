package com.zoolchat.app.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.zoolchat.app.auth.SessionStore
import java.security.KeyStore
import java.security.MessageDigest
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts cached chat text/metadata at rest while keeping Room schema simple. */
class LocalStorageCrypto(context: Context) {
    private val session = SessionStore(context)
    private val prefs=context.getSharedPreferences("zoolchat_cache_keys",Context.MODE_PRIVATE)

    private fun tag(): String {
        val userId=session.currentUserId
        if(userId==null) return MessageDigest.getInstance("SHA-256").digest(session.accountUsername.ifBlank { "zoolchat-local" }.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
        val pref="cache_tag_$userId"
        prefs.getString(pref,null)?.let { return it }
        // Preserve the legacy alias on first upgrade; pin it before future profile changes.
        val raw=session.accountUsername.ifBlank { userId }
        val tag=MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
        prefs.edit().putString(pref,tag).commit()
        return tag
    }
    private fun alias() = "zoolchat_local_cache_${tag()}"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias(), null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(alias(), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    fun protect(value: String?): String? {
        if (value == null || value.isEmpty() || value.startsWith(PREFIX)) return value
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        // AndroidKeyStore must generate a fresh IV for GCM encryption.
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        require(iv.size == 12) { "bad_generated_gcm_iv" }
        return PREFIX + Base64.encodeToString(iv + c.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }

    fun reveal(value: String?): String? {
        if (value == null || !value.startsWith(PREFIX)) return value
        return runCatching {
            val all = Base64.decode(value.removePrefix(PREFIX), Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
            require(all.size > 28)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all.copyOfRange(0, 12)))
            c.doFinal(all.copyOfRange(12, all.size)).toString(Charsets.UTF_8)
        }.getOrElse { "🔒 تعذر فتح البيانات المحلية" }
    }
    fun protectStream(input: InputStream, output: File) {
        output.parentFile?.mkdirs()
        val c=Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE,key())
        val iv=c.iv
        require(iv.size==12){"bad_generated_gcm_iv"}
        FileOutputStream(output).use { raw ->
            raw.write(byteArrayOf('Z'.code.toByte(),'L'.code.toByte(),'P'.code.toByte(),1))
            raw.write(iv)
            CipherOutputStream(raw,c).use { encrypted -> input.use { it.copyTo(encrypted) } }
        }
    }

    fun revealFile(input: File, output: File) {
        output.parentFile?.mkdirs()
        FileInputStream(input).use { raw ->
            val magic=ByteArray(4)
            val read=raw.read(magic)
            if(read!=4 || magic[0]!='Z'.code.toByte() || magic[1]!='L'.code.toByte() || magic[2]!='P'.code.toByte() || magic[3].toInt()!=1){
                // v0.20 legacy pending files were plaintext. Preserve upgrade compatibility.
                FileOutputStream(output).use { out ->
                    if(read>0) out.write(magic,0,read)
                    raw.copyTo(out)
                }
                return
            }
            val iv=ByteArray(12); require(raw.read(iv)==12){"bad_local_media"}
            val c=Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,iv))
            FileOutputStream(output).use { out -> CipherInputStream(raw,c).use { it.copyTo(out) } }
        }
    }

}
private const val PREFIX = "zlc1:"
