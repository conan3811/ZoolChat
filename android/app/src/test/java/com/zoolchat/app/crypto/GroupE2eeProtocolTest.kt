package com.zoolchat.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class GroupE2eeProtocolTest {
    private fun identity(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    @Test
    fun senderAndRecipientsDeriveSamePairwiseWrapKeysAndTamperingFails() {
        val sender = identity()
        val recipientA = identity()
        val recipientB = identity()
        val outsider = identity()
        val senderId = "11111111-1111-4111-8111-111111111111"
        val aId = "22222222-2222-4222-8222-222222222222"
        val bId = "33333333-3333-4333-8333-333333333333"
        val outsiderId = "44444444-4444-4444-8444-444444444444"
        val chatId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val clientId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val type = "text"
        val contentKey = GroupE2eeProtocol.randomContentKey()
        val plaintext = "group E2EE round trip ✅".toByteArray(StandardCharsets.UTF_8)
        val payloadAad = "group-payload-v2|$chatId|$senderId|$clientId|$type"
        val encryptedPayload = GroupE2eeProtocol.encrypt(contentKey, plaintext, payloadAad)

        fun wrapFor(recipientId: String, recipient: KeyPair): String {
            val info = "group-wrap-v2|$chatId|$senderId|$recipientId|$clientId|$type"
            val pairKey = GroupE2eeProtocol.derivePairwiseKey(sender.private, recipient.public, senderId, recipientId, info)
            return GroupE2eeProtocol.encrypt(pairKey, contentKey, info)
        }
        fun unwrapAs(recipientId: String, recipient: KeyPair, wrapped: String): ByteArray {
            val info = "group-wrap-v2|$chatId|$senderId|$recipientId|$clientId|$type"
            val pairKey = GroupE2eeProtocol.derivePairwiseKey(recipient.private, sender.public, recipientId, senderId, info)
            return GroupE2eeProtocol.decrypt(pairKey, wrapped, info)
        }

        val wrappedA = wrapFor(aId, recipientA)
        val wrappedB = wrapFor(bId, recipientB)
        assertNotEquals(wrappedA, wrappedB)

        val keyA = unwrapAs(aId, recipientA, wrappedA)
        val keyB = unwrapAs(bId, recipientB, wrappedB)
        assertArrayEquals(contentKey, keyA)
        assertArrayEquals(contentKey, keyB)
        assertEquals(String(plaintext, StandardCharsets.UTF_8), String(GroupE2eeProtocol.decrypt(keyA, encryptedPayload, payloadAad), StandardCharsets.UTF_8))
        assertEquals(String(plaintext, StandardCharsets.UTF_8), String(GroupE2eeProtocol.decrypt(keyB, encryptedPayload, payloadAad), StandardCharsets.UTF_8))

        val outsiderInfo = "group-wrap-v2|$chatId|$senderId|$aId|$clientId|$type"
        val outsiderKey = GroupE2eeProtocol.derivePairwiseKey(outsider.private, sender.public, outsiderId, senderId, outsiderInfo)
        assertThrows(Exception::class.java) { GroupE2eeProtocol.decrypt(outsiderKey, wrappedA, outsiderInfo) }
        assertThrows(Exception::class.java) { GroupE2eeProtocol.decrypt(keyA, encryptedPayload, payloadAad + "|tampered") }
    }
}
