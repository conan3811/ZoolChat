package com.zoolchat.app.crypto

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

/** Pure-JVM cryptographic primitive used by ZoolChat group E2EE v2. */
object GroupE2eeProtocol {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun derivePairwiseKey(
        myPrivateKey: PrivateKey,
        peerPublicKey: PublicKey,
        myUserId: String,
        peerUserId: String,
        info: String,
    ): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(myPrivateKey)
        agreement.doPhase(peerPublicKey, true)
        val secret = agreement.generateSecret()
        val sorted = listOf(myUserId, peerUserId).sorted().joinToString("|")
        val salt = sha256("ZoolChat-E2EE-v1|$sorted".toByteArray(StandardCharsets.UTF_8))
        return hkdf(secret, salt, info.toByteArray(StandardCharsets.UTF_8), 32)
    }

    fun randomContentKey(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(32).also(random::nextBytes)

    fun encrypt(key: ByteArray, plaintext: ByteArray, aad: String, random: SecureRandom = SecureRandom()): String {
        require(key.size == 32) { "group_key_must_be_256_bits" }
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad.toByteArray(StandardCharsets.UTF_8))
        return encode(iv + cipher.doFinal(plaintext))
    }

    fun decrypt(key: ByteArray, encoded: String, aad: String): ByteArray {
        require(key.size == 32) { "group_key_must_be_256_bits" }
        val all = decode(encoded)
        require(all.size > 28) { "bad_group_ciphertext" }
        val iv = all.copyOfRange(0, 12)
        val ciphertext = all.copyOfRange(12, all.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad.toByteArray(StandardCharsets.UTF_8))
        return cipher.doFinal(ciphertext)
    }

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)
    fun decode(text: String): ByteArray = decoder.decode(text)

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val n = minOf(previous.size, length - offset)
            System.arraycopy(previous, 0, out, offset, n)
            offset += n
            counter++
        }
        return out
    }

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
}
