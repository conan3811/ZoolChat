package com.zoolchat.app.contacts

import android.content.Context
import android.provider.ContactsContract
import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale
import java.security.MessageDigest

/** Names and raw phone numbers stay on-device. Only SHA-256 discovery hashes are sent. */
data class LocalPhoneContact(val localName: String, val e164: String, val discoveryHash: String)

class DeviceContactsRepository(private val context: Context) {
    private val phoneUtil = PhoneNumberUtil.getInstance()

    private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun normalizeToE164(raw: String, primaryRegion: String): String? {
        val cleaned = raw.trim().replace(Regex("^00"), "+")
        val regions = linkedSetOf(primaryRegion.ifBlank { "SA" }, "SA")
        for (region in regions) {
            val e164 = runCatching {
                val parsed = phoneUtil.parse(cleaned, region)
                if (!phoneUtil.isValidNumber(parsed)) null else phoneUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
            }.getOrNull()
            if (!e164.isNullOrBlank()) return e164
        }
        return null
    }

    fun readAll(): List<LocalPhoneContact> {
        val defaultRegion = Locale.getDefault().country.ifBlank { "SA" }
        val result = linkedMapOf<String, LocalPhoneContact>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY + " COLLATE LOCALIZED ASC",
        )?.use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
            val numberIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx).orEmpty().trim()
                val raw = cursor.getString(numberIdx).orEmpty()
                val e164 = normalizeToE164(raw, defaultRegion) ?: continue
                result.putIfAbsent(e164, LocalPhoneContact(name.ifBlank { e164 }, e164, sha256Hex(e164)))
            }
        }
        return result.values.toList()
    }
}
