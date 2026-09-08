package com.zoolchat.app.auth

import android.content.Context
import android.os.Build
import android.util.Patterns
import com.google.firebase.FirebaseApp
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.zoolchat.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.time.Instant
import java.util.Collections
import java.util.Locale
import java.util.concurrent.TimeUnit

class AuthRepository(val context: Context, private val sessionStore: SessionStore) {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val probeHttp = OkHttpClient.Builder().connectTimeout(350, TimeUnit.MILLISECONDS).readTimeout(500, TimeUnit.MILLISECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val phoneUtil = PhoneNumberUtil.getInstance()

    val firebaseConfigured: Boolean get() = FirebaseApp.getApps(context).isNotEmpty()
    val signedIn: Boolean get() = sessionStore.accessToken != null || (BuildConfig.DEV_AUTH_ENABLED && sessionStore.devUserId != null)

    private fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(100)

    /**
     * Normal users never see or type a server URL. Production builds use the configured HTTPS
     * endpoint. LAN beta builds can automatically discover a ZoolChat server on the current /24
     * private network and remember it for the rest of the session.
     */
    suspend fun resolveBaseUrl(): String {
        val saved = sessionStore.apiBaseUrlOverride?.trimEnd('/')
        if (!saved.isNullOrBlank() && !saved.contains("YOUR_BACKEND_HOST")) return saved

        val configured = BuildConfig.API_BASE_URL.trimEnd('/')
        if (!configured.contains("YOUR_BACKEND_HOST")) {
            require(BuildConfig.LAN_BETA || configured.startsWith("https://", ignoreCase = true)) { "ZoolChat requires a secure server connection" }
            sessionStore.apiBaseUrlOverride = configured
            return configured
        }

        if (!BuildConfig.LAN_BETA) error("تعذر الاتصال بخدمة ZoolChat. حاول مرة أخرى لاحقاً.")
        val discovered = discoverLanServer()
            ?: error("تعذر الاتصال بخدمة ZoolChat. تأكد من الإنترنت أو أن خدمة ZoolChat تعمل ثم حاول مرة أخرى.")
        sessionStore.apiBaseUrlOverride = discovered
        return discovered
    }

    private suspend fun discoverLanServer(): String? = coroutineScope {
        val ipv4 = Collections.list(NetworkInterface.getNetworkInterfaces())
            .asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { Collections.list(it.inetAddresses).asSequence() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress.orEmpty() }
            .filter { isPrivateIpv4(it) }
            .distinct()
            .toList()

        val candidates = linkedSetOf<String>()
        for (ip in ipv4) {
            val parts = ip.split('.')
            if (parts.size != 4) continue
            val prefix = parts.take(3).joinToString(".")
            listOf(1, 10, 2, 20, 100, 200).forEach { candidates += "http://$prefix.$it:8080" }
            for (host in 1..254) candidates += "http://$prefix.$host:8080"
        }

        for (batch in candidates.chunked(32)) {
            val hit = batch.map { base ->
                async(Dispatchers.IO) { if (isZoolChatServer(base)) base else null }
            }.awaitAll().firstOrNull { it != null }
            if (hit != null) return@coroutineScope hit
        }
        null
    }

    private fun isPrivateIpv4(ip: String): Boolean {
        val p = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168)
    }

    private fun isZoolChatServer(base: String): Boolean = runCatching {
        probeHttp.newCall(Request.Builder().url("$base/health").get().build()).execute().use { r ->
            if (!r.isSuccessful) return@use false
            val o = JSONObject(r.body.string())
            o.optBoolean("ok") && o.optString("service").contains("zoolchat", ignoreCase = true)
        }
    }.getOrDefault(false)

    private suspend fun authPost(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val base = resolveBaseUrl()
        try {
            val req = Request.Builder().url(base + path).post(body.toString().toRequestBody(jsonType)).header("Accept", "application/json").build()
            http.newCall(req).execute().use { response ->
                val raw = response.body.string()
                if (!response.isSuccessful) throw IllegalStateException(readableError(raw, response.code))
                if (raw.isBlank()) JSONObject() else JSONObject(raw)
            }
        } catch (e: IllegalStateException) {
            throw e
        } catch (_: Exception) {
            throw IllegalStateException("تعذر الاتصال بخدمة ZoolChat. تحقق من اتصال الإنترنت وحاول مرة أخرى.")
        }
    }

    private fun readableError(raw: String, status: Int): String {
        val code = runCatching { JSONObject(raw).optString("error") }.getOrNull().orEmpty()
        return when(code) {
            "username_taken" -> "اسم المستخدم مستخدم بالفعل"
            "email_in_use" -> "البريد الإلكتروني مستخدم بالفعل"
            "phone_in_use" -> "رقم الجوال مستخدم بالفعل"
            "invalid_credentials" -> "رقم الجوال أو كلمة المرور غير صحيحة"
            "email_delivery_unavailable" -> "استرداد البريد غير متاح حالياً. حاول لاحقاً."
            "invalid_request" -> "تحقق من البيانات المدخلة"
            "invalid_or_expired_refresh_token" -> "انتهت الجلسة. سجّل الدخول مرة أخرى"
            "invalid_recovery_code" -> "اسم المستخدم أو رمز الاسترداد غير صحيح"
            "invalid_two_factor_code" -> "رمز التحقق غير صحيح أو انتهت صلاحيته"
            "verified_email_required" -> "يجب توثيق البريد الإلكتروني أولاً لاستخدام التحقق بخطوتين"
            "two_factor_rate_limited" -> "انتظر قليلاً قبل طلب رمز تحقق جديد"
            else -> if (status >= 500) "خدمة ZoolChat غير متاحة حالياً. حاول لاحقاً." else "تعذر إكمال الطلب. حاول مرة أخرى."
        }
    }

    private fun saveSession(o: JSONObject, fallbackUsername: String? = null) {
        sessionStore.accessToken = o.getString("accessToken")
        sessionStore.refreshToken = o.getString("refreshToken")
        sessionStore.accessTokenExpiresAtMs = runCatching { Instant.parse(o.getString("accessExpiresAt")).toEpochMilli() }.getOrDefault(System.currentTimeMillis() + 55*60_000)
        val user = o.optJSONObject("user")
        user?.optString("id")?.takeIf { it.isNotBlank() && it != "null" }?.let { sessionStore.currentUserId = it }
        val username = user?.optString("username")?.takeIf { it.isNotBlank() && it != "null" } ?: fallbackUsername
        sessionStore.accountUsername = username?.lowercase().orEmpty()
        user?.optString("displayName")?.takeIf { it.isNotBlank() }?.let { sessionStore.localDisplayName = it }
    }

    private fun normalizePhone(raw: String): String {
        val defaultRegion = Locale.getDefault().country.ifBlank { "SA" }
        val parsed = runCatching { phoneUtil.parse(raw.trim(), defaultRegion) }.getOrNull()
            ?: error("رقم الجوال غير صالح")
        require(phoneUtil.isValidNumber(parsed)) { "رقم الجوال غير صالح" }
        return phoneUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
    }

    suspend fun registerAccount(email: String, password: String, phone: String): Result<Unit> = runCatching {
        val e=email.trim().lowercase()
        require(Patterns.EMAIL_ADDRESS.matcher(e).matches() && e.length<=254) { "البريد الإلكتروني غير صالح" }
        require(password.length in 8..128) { "كلمة المرور يجب أن تكون 8 أحرف على الأقل" }
        val response=authPost("/api/auth/register",JSONObject().put("email",e).put("password",password).put("phoneE164",normalizePhone(phone)).put("deviceName",deviceName()))
        saveSession(response)
        RecentAuthSecret.put(password)
    }
    suspend fun requestPasswordCode(email: String): Result<Unit> = runCatching {
        authPost("/api/auth/password/request",JSONObject().put("email",email.trim().lowercase())); Unit
    }
    suspend fun resetPassword(email:String,code:String,password:String): Result<Unit> = runCatching {
        authPost("/api/auth/password/reset",JSONObject().put("email",email.trim().lowercase()).put("code",code).put("newPassword",password)); Unit
    }

    suspend fun recoverFree(username: String, recoveryCode: String, newPassword: String): Result<String> = runCatching {
        val u=username.trim().removePrefix("@").lowercase(); require(u.length>=3) { "اسم المستخدم مطلوب" }
        require(recoveryCode.trim().length>=16) { "رمز الاسترداد غير صالح" }
        require(newPassword.length in 8..128) { "كلمة المرور الجديدة يجب أن تكون 8 أحرف على الأقل" }
        val response=authPost("/api/auth/recover",JSONObject().put("username",u).put("recoveryCode",recoveryCode.trim()).put("newPassword",newPassword).put("deviceName",deviceName()))
        saveSession(response,u)
        RecentAuthSecret.put(newPassword)
        response.getString("recoveryCode")
    }

    suspend fun loginFree(identifier: String, password: String): Result<String?> = runCatching {
        val id = normalizePhone(identifier)
        require(id.length >= 3) { "أدخل رقم الجوال" }
        require(password.length >= 8) { "كلمة المرور غير صحيحة" }
        val o = authPost("/api/auth/login", JSONObject().put("identifier", id).put("password", password).put("deviceName", deviceName()))
        if (o.optBoolean("twoFactorRequired", false)) {
            RecentAuthSecret.put(password)
            return@runCatching o.getString("challengeToken")
        }
        saveSession(o)
        RecentAuthSecret.put(password)
        null
    }

    suspend fun completeTwoFactorLogin(challengeToken: String, code: String): Result<Unit> = runCatching {
        require(Regex("^\d{8}$").matches(code)) { "رمز التحقق يجب أن يتكون من 8 أرقام" }
        val o = authPost("/api/auth/login/2fa", JSONObject().put("challengeToken", challengeToken).put("code", code).put("deviceName", deviceName()))
        saveSession(o)
    }

    suspend fun testBackend(): Result<String> = runCatching { withContext(Dispatchers.IO) {
        val base = resolveBaseUrl()
        probeHttp.newCall(Request.Builder().url("$base/health").get().build()).execute().use { r ->
            if(!r.isSuccessful) error("service_unavailable")
            val o=JSONObject(r.body.string()); if(!o.optBoolean("ok")) error("service_unavailable"); "${o.optString("service","ZoolChat")} ${o.optString("version")}".trim()
        }
    } }

    suspend fun refreshFreeSession(): Boolean {
        val refresh=sessionStore.refreshToken ?: return false
        val previousAccess = sessionStore.accessToken
        val previousExpiry = sessionStore.accessTokenExpiresAtMs
        return runCatching {
            val o=authPost("/api/auth/refresh",JSONObject().put("refreshToken",refresh).put("deviceName",deviceName()))
            saveSession(o,sessionStore.accountUsername)
            true
        }.getOrElse {
            sessionStore.accessToken = previousAccess
            sessionStore.refreshToken = refresh
            sessionStore.accessTokenExpiresAtMs = previousExpiry
            false
        }
    }

    suspend fun bearerToken(forceRefresh: Boolean = false): String? {
        if (sessionStore.accessToken != null) {
            val expiring = sessionStore.accessTokenExpiresAtMs <= System.currentTimeMillis() + 60_000
            if (forceRefresh || expiring) refreshFreeSession()
            return sessionStore.accessToken
        }
        return null
    }

    fun signInDev(testId: String, phoneE164: String, displayName: String): Result<Unit> = runCatching {
        check(BuildConfig.DEV_AUTH_ENABLED) { "وضع الاختبار غير مفعّل" }
        require(Regex("^[A-Za-z0-9._-]{3,80}$").matches(testId)) { "معرّف الاختبار غير صالح" }
        require(Regex("^\\+[1-9]\\d{5,14}$").matches(phoneE164)) { "اكتب الرقم بصيغة دولية مثل +9665…" }
        require(displayName.trim().isNotBlank()) { "الاسم مطلوب" }
        sessionStore.devUserId = testId; sessionStore.devPhoneE164 = phoneE164; sessionStore.devDisplayName = displayName.trim().take(80); sessionStore.localDisplayName = displayName.trim().take(80)
    }

    suspend fun signOut() {
        RecentAuthSecret.clear()
        val refresh=sessionStore.refreshToken
        if(refresh!=null) runCatching { authPost("/api/auth/logout",JSONObject().put("refreshToken",refresh)) }
        sessionStore.clearFreeAuth(); sessionStore.clearDevIdentity()
    }
}
