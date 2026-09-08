package com.zoolchat.app.auth

import android.os.Build
import android.util.Patterns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Temporary registration compatibility for the still-live v0.31 backend.
 *
 * v0.32 intentionally does not ask the user for a username during registration. The v0.32
 * backend accepts username as optional, while an older production endpoint can still reject the
 * new payload because it expects the historical username field. We first use the real v0.32
 * request. Only when that request is rejected as invalid AND /health confirms v0.31 do we retry
 * with a random, non-personal compatibility username. Nothing is derived from email or phone.
 */
private const val LEGACY_TEMP_USERNAME_PREFIX = "zc_tmp_"

private val registrationCompatHttp = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .build()
private val registrationCompatJson = "application/json; charset=utf-8".toMediaType()

private fun temporaryLegacyUsername(): String =
    LEGACY_TEMP_USERNAME_PREFIX + UUID.randomUUID().toString().replace("-", "").take(18)

private fun compatibilityError(raw: String, status: Int): String {
    val code = runCatching { JSONObject(raw).optString("error") }.getOrNull().orEmpty()
    return when (code) {
        "username_taken" -> "تعذر إنشاء الحساب. حاول مرة أخرى."
        "email_in_use" -> "البريد الإلكتروني مستخدم بالفعل"
        "phone_in_use" -> "رقم الجوال مستخدم بالفعل"
        "invalid_request" -> "تحقق من البيانات المدخلة"
        else -> if (status >= 500) "خدمة ZoolChat غير متاحة حالياً. حاول لاحقاً."
        else "تعذر إكمال الطلب. حاول مرة أخرى."
    }
}

private suspend fun backendVersion(auth: AuthRepository): String? = withContext(Dispatchers.IO) {
    val base = auth.resolveBaseUrl()
    runCatching {
        registrationCompatHttp.newCall(
            Request.Builder().url("$base/health").get().header("Accept", "application/json").build()
        ).execute().use { response ->
            if (!response.isSuccessful) return@use null
            JSONObject(response.body.string()).optString("version").takeIf { it.isNotBlank() }
        }
    }.getOrNull()
}

private fun persistCompatibilitySession(session: SessionStore, payload: JSONObject) {
    session.accessToken = payload.getString("accessToken")
    session.refreshToken = payload.getString("refreshToken")
    session.accessTokenExpiresAtMs = runCatching {
        Instant.parse(payload.getString("accessExpiresAt")).toEpochMilli()
    }.getOrDefault(System.currentTimeMillis() + 55 * 60_000)

    val user = payload.optJSONObject("user")
    user?.optString("id")
        ?.takeIf { it.isNotBlank() && it != "null" }
        ?.let { session.currentUserId = it }
    user?.optString("displayName")
        ?.takeIf { it.isNotBlank() && it != "null" }
        ?.let { session.localDisplayName = it }

    val returnedUsername = user?.optString("username").orEmpty()
    session.accountUsername = returnedUsername
        .takeIf { it.isNotBlank() && !it.startsWith(LEGACY_TEMP_USERNAME_PREFIX) }
        ?.lowercase()
        .orEmpty()
}

suspend fun registerAccountCompatible(
    auth: AuthRepository,
    session: SessionStore,
    email: String,
    password: String,
    phoneE164: String,
): Result<Unit> {
    val primary = auth.registerAccount(email, password, phoneE164)
    if (primary.isSuccess) {
        // Registration must enter Chats immediately. Profile/username setup remains optional
        // and is available later from Settings > Profile.
        session.initialProfileSetupPending = false
        return primary
    }

    val primaryError = primary.exceptionOrNull()
    if (primaryError?.message != "تحقق من البيانات المدخلة") return primary

    val version = backendVersion(auth)
    if (version?.startsWith("0.31.") != true && version != "0.31") return primary

    return runCatching {
        val normalizedEmail = email.trim().lowercase()
        require(Patterns.EMAIL_ADDRESS.matcher(normalizedEmail).matches() && normalizedEmail.length <= 254) {
            "البريد الإلكتروني غير صالح"
        }
        require(password.length in 8..128) { "كلمة المرور يجب أن تكون بين 8 و128 حرفاً" }
        require(Regex("^\\+[1-9]\\d{5,14}$").matches(phoneE164)) { "رقم الجوال غير صالح" }

        val body = JSONObject()
            .put("username", temporaryLegacyUsername())
            .put("email", normalizedEmail)
            .put("password", password)
            .put("phoneE164", phoneE164)
            .put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(100))

        val responsePayload = withContext(Dispatchers.IO) {
            val base = auth.resolveBaseUrl()
            val request = Request.Builder()
                .url("$base/api/auth/register")
                .post(body.toString().toRequestBody(registrationCompatJson))
                .header("Accept", "application/json")
                .build()
            registrationCompatHttp.newCall(request).execute().use { response ->
                val raw = response.body.string()
                if (!response.isSuccessful) throw IllegalStateException(compatibilityError(raw, response.code))
                if (raw.isBlank()) JSONObject() else JSONObject(raw)
            }
        }
        persistCompatibilitySession(session, responsePayload)
        RecentAuthSecret.put(password)
        session.initialProfileSetupPending = false
    }
}
