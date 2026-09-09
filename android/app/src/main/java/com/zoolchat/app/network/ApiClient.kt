package com.zoolchat.app.network

import android.net.Uri
import android.os.Build
import com.zoolchat.app.BuildConfig
import com.zoolchat.app.auth.AuthRepository
import com.zoolchat.app.auth.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, message: String) : Exception(message)

data class MessagePageJson(val messages: JSONArray, val hasMore: Boolean, val nextCursor: String?)

class ApiClient(
    private val auth: AuthRepository,
    private val session: SessionStore,
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build(),
) {
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    val baseUrl: String
        get() = (session.apiBaseUrlOverride ?: BuildConfig.API_BASE_URL).trimEnd('/')

    suspend fun get(path: String): JSONObject = request("GET", path, null)
    suspend fun post(path: String, body: JSONObject = JSONObject()): JSONObject = request("POST", path, body)
    suspend fun put(path: String, body: JSONObject): JSONObject = request("PUT", path, body)
    suspend fun delete(path: String): JSONObject = request("DELETE", path, null)

    private suspend fun applyAuth(builder: Request.Builder) {
        val firebaseToken = auth.bearerToken()
        if (firebaseToken != null) {
            builder.header("Authorization", "Bearer $firebaseToken")
        } else if (BuildConfig.DEV_AUTH_ENABLED && session.devUserId != null) {
            builder.header("x-zoolchat-dev-user", session.devUserId!!)
            session.devPhoneE164?.let { builder.header("x-zoolchat-dev-phone", it) }
            val name = session.devDisplayName.ifBlank { session.localDisplayName }
            if (name.isNotBlank()) builder.header("x-zoolchat-dev-name", name)
        }
    }

    private suspend fun request(method: String, path: String, body: JSONObject?): JSONObject = withContext(Dispatchers.IO) {
        require(!baseUrl.contains("YOUR_BACKEND_HOST")) { "service_unavailable" }
        val builder = Request.Builder().url(baseUrl + path).header("Accept", "application/json")
        applyAuth(builder)
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post((body ?: JSONObject()).toString().toRequestBody(jsonType))
            "PUT" -> builder.put((body ?: JSONObject()).toString().toRequestBody(jsonType))
            "DELETE" -> builder.delete()
        }
        val first = http.newCall(builder.build()).execute()
        first.use { response ->
            val raw = response.body.string()
            if (response.code == 401 && auth.refreshFreeSession()) {
                val retry = Request.Builder().url(baseUrl + path).header("Accept", "application/json")
                applyAuth(retry)
                when (method) {
                    "GET" -> retry.get()
                    "POST" -> retry.post((body ?: JSONObject()).toString().toRequestBody(jsonType))
                    "PUT" -> retry.put((body ?: JSONObject()).toString().toRequestBody(jsonType))
                    "DELETE" -> retry.delete()
                }
                http.newCall(retry.build()).execute().use { r2 ->
                    val raw2 = r2.body.string()
                    if (!r2.isSuccessful) throw ApiException(r2.code, raw2.ifBlank { "HTTP ${r2.code}" })
                    return@withContext if (raw2.isBlank()) JSONObject() else JSONObject(raw2)
                }
            }
            if (!response.isSuccessful) throw ApiException(response.code, raw.ifBlank { "HTTP ${response.code}" })
            if (raw.isBlank()) JSONObject() else JSONObject(raw)
        }
    }

    suspend fun bootstrap(displayName: String, username: String? = null): JSONObject = post(
        "/api/session/bootstrap",
        JSONObject().put("displayName", displayName).apply { if (!username.isNullOrBlank()) put("username", username) },
    )

    suspend fun registerFcm(token: String) = post(
        "/api/devices/fcm",
        JSONObject().put("token", token).put("platform", "android").put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}".trim()),
    )
    suspend fun revokeFcm(token: String) = post("/api/devices/fcm/revoke", JSONObject().put("token", token))

    suspend fun devices(): JSONArray = get("/api/devices").optJSONArray("devices") ?: JSONArray()
    suspend fun revokeDevice(deviceId: String) = delete("/api/devices/$deviceId")
    suspend fun logoutOtherDevices(): JSONObject = post("/api/devices/logout-others")
    suspend fun twoFactorStatus(): JSONObject = get("/api/me/2fa")
    suspend fun requestTwoFactorEnable(password: String): JSONObject = post("/api/me/2fa/enable/request", JSONObject().put("password", password))
    suspend fun confirmTwoFactorEnable(challengeToken: String, code: String): JSONObject = post("/api/me/2fa/enable/confirm", JSONObject().put("challengeToken", challengeToken).put("code", code))
    suspend fun requestTwoFactorDisable(password: String): JSONObject = post("/api/me/2fa/disable/request", JSONObject().put("password", password))
    suspend fun confirmTwoFactorDisable(challengeToken: String, code: String): JSONObject = post("/api/me/2fa/disable/confirm", JSONObject().put("challengeToken", challengeToken).put("code", code))
    suspend fun resolveUsernameProfile(username: String): JSONObject {
        val encoded = URLEncoder.encode(username.trim().removePrefix("@"), StandardCharsets.UTF_8.toString())
        return get("/api/users/by-username/$encoded").getJSONObject("user")
    }

    suspend fun discoverContacts(phoneHashes: List<String>): JSONArray {
        val result = post("/api/contacts/discover", JSONObject().put("phoneHashes", JSONArray(phoneHashes)))
        return result.optJSONArray("contacts") ?: JSONArray()
    }

    suspend fun searchUsers(query: String): JSONArray {
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.toString())
        return get("/api/users/search?q=$encoded").optJSONArray("users") ?: JSONArray()
    }

    suspend fun presence(userId: String): JSONObject = get("/api/users/$userId/presence").getJSONObject("presence")

    suspend fun setTyping(chatId: String, active: Boolean) = post("/api/chats/$chatId/typing", JSONObject().put("active", active))

    suspend fun chats(): JSONArray = get("/api/chats").optJSONArray("chats") ?: JSONArray()
    suspend fun createDirectChat(userId: String): JSONObject = post("/api/chats/direct", JSONObject().put("userId", userId)).getJSONObject("chat")

    suspend fun messages(chatId: String, before: String? = null, limit: Int = 50): MessagePageJson {
        val query = buildString {
            append("?limit=${limit.coerceIn(1, 100)}")
            if (!before.isNullOrBlank()) append("&before=${URLEncoder.encode(before, StandardCharsets.UTF_8.toString())}")
        }
        val o = get("/api/chats/$chatId/messages$query")
        return MessagePageJson(o.optJSONArray("messages") ?: JSONArray(), o.optBoolean("hasMore", false), o.optString("nextCursor").takeIf { it.isNotBlank() })
    }

    suspend fun searchMessages(chatId: String, query: String): JSONArray {
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.toString())
        return get("/api/chats/$chatId/search?q=$encoded").optJSONArray("messages") ?: JSONArray()
    }
    suspend fun sharedContent(chatId: String, kind: String): JSONArray {
        val safeKind = when (kind) { "documents" -> "documents"; "links" -> "links"; else -> "media" }
        return get("/api/chats/$chatId/shared?kind=$safeKind&limit=100").optJSONArray("items") ?: JSONArray()
    }

    suspend fun sendMessage(chatId: String, clientId: String, type: String, body: String, mediaId: String? = null, encrypted: Boolean = false, cryptoVersion: Int = 0): JSONObject = post(
        "/api/chats/$chatId/messages",
        JSONObject().put("clientId", clientId).put("type", type).put("body", body).put("encrypted", encrypted).put("cryptoVersion", cryptoVersion).apply { if (!mediaId.isNullOrBlank()) put("mediaId", mediaId) },
    ).getJSONObject("message")

    suspend fun editMessage(chatId: String, messageId: String, body: String, encrypted: Boolean = false, cryptoVersion: Int = 0): JSONObject = put(
        "/api/chats/$chatId/messages/$messageId",
        JSONObject().put("body", body).put("encrypted", encrypted).put("cryptoVersion", cryptoVersion),
    ).getJSONObject("message")

    suspend fun deleteMessage(chatId: String, messageId: String) = delete("/api/chats/$chatId/messages/$messageId")
    suspend fun reactMessage(chatId: String, messageId: String, emoji: String): JSONObject = put(
        "/api/chats/$chatId/messages/$messageId/reaction",
        JSONObject().put("emoji", emoji),
    )
    suspend fun removeReaction(chatId: String, messageId: String): JSONObject = delete("/api/chats/$chatId/messages/$messageId/reaction")
    suspend fun markDelivered(chatId: String, messageId: String) = post("/api/chats/$chatId/delivered", JSONObject().put("messageId", messageId))
    suspend fun markRead(chatId: String, messageId: String) = post("/api/chats/$chatId/read", JSONObject().put("messageId", messageId))

    suspend fun putE2eeKey(publicKeyB64: String, fingerprintSha256: String): JSONObject = put(
        "/api/e2ee/key",
        JSONObject().put("algorithm", "P256-ECDH").put("publicKeyB64", publicKeyB64).put("fingerprintSha256", fingerprintSha256),
    )

    suspend fun getE2eeKey(userId: String): JSONObject = get("/api/users/$userId/e2ee-key")
    suspend fun groupE2eeKeys(chatId: String): JSONObject = get("/api/groups/$chatId/e2ee-keys")

    suspend fun uploadMedia(uri: Uri, mime: String, displayName: String): JSONObject = withContext(Dispatchers.IO) {
        require(!baseUrl.contains("YOUR_BACKEND_HOST")) { "service_unavailable" }
        val resolver = auth.context.contentResolver
        val temp = File.createTempFile("zool_upload_", ".bin", auth.context.cacheDir)
        val source = if (uri.scheme == "file") File(requireNotNull(uri.path)).inputStream() else resolver.openInputStream(uri)
        source.use { input -> requireNotNull(input) { "cannot_open_media" }; temp.outputStream().use { input.copyTo(it) } }
        try {
            val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", displayName, temp.asRequestBody(mime.toMediaType()))
                .build()
            val builder = Request.Builder().url(baseUrl + "/api/media").header("Accept", "application/json")
            applyAuth(builder)
            http.newCall(builder.post(multipart).build()).execute().use { response ->
                val raw = response.body.string()
                if (!response.isSuccessful) throw ApiException(response.code, raw)
                JSONObject(raw).getJSONObject("media")
            }
        } finally { temp.delete() }
    }

    suspend fun updateProfile(displayName: String? = null, about: String? = null, photoMediaId: String? = null, clearPhoto: Boolean = false): JSONObject = put(
        "/api/me",
        JSONObject().apply {
            displayName?.let { put("displayName", it) }
            about?.let { put("about", it) }
            if (clearPhoto) put("photoMediaId", JSONObject.NULL) else photoMediaId?.let { put("photoMediaId", it) }
        },
    ).getJSONObject("user")

    suspend fun downloadMedia(relativeUrl: String, displayName: String): File = withContext(Dispatchers.IO) {
        val url = if (relativeUrl.startsWith("http://") || relativeUrl.startsWith("https://")) relativeUrl else baseUrl + relativeUrl
        val dir = File(auth.context.cacheDir, "zool_media").apply { mkdirs() }
        val safe = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(100).ifBlank { "attachment" }
        val target = File(dir, "${url.hashCode().toUInt()}_$safe")
        if (target.exists() && target.length() > 0) return@withContext target
        val builder = Request.Builder().url(url)
        applyAuth(builder)
        http.newCall(builder.get().build()).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, "media_download_failed")
            val body = response.body
            target.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
        }
        target
    }

    suspend fun createCall(peerUserId: String, video: Boolean): JSONObject = post(
        "/api/calls",
        JSONObject().put("peerUserIds", JSONArray(listOf(peerUserId))).put("type", if (video) "video" else "audio"),
    )
    suspend fun acceptCall(callId: String): JSONObject = post("/api/calls/$callId/accept")
    suspend fun declineCall(callId: String): JSONObject = post("/api/calls/$callId/decline")
    suspend fun endCall(callId: String): JSONObject = post("/api/calls/$callId/end")
    suspend fun callHistory(): JSONArray = get("/api/calls").optJSONArray("calls") ?: JSONArray()

    suspend fun createGroup(title: String, description: String, memberUserIds: List<String>): JSONObject = post(
        "/api/groups", JSONObject().put("title", title).put("description", description).put("memberUserIds", JSONArray(memberUserIds)),
    ).getJSONObject("group")
    suspend fun groupInfo(chatId: String): JSONObject = get("/api/groups/$chatId").getJSONObject("group")
    suspend fun updateGroup(chatId: String, body: JSONObject): JSONObject = put("/api/groups/$chatId", body)
    suspend fun addGroupMembers(chatId: String, userIds: List<String>): JSONObject = post("/api/groups/$chatId/members", JSONObject().put("userIds", JSONArray(userIds)))
    suspend fun removeGroupMember(chatId: String, userId: String): JSONObject = delete("/api/groups/$chatId/members/$userId")
    suspend fun setGroupMemberRole(chatId: String, userId: String, role: String): JSONObject = post("/api/groups/$chatId/members/$userId/role", JSONObject().put("role", role))
    suspend fun leaveGroup(chatId: String): JSONObject = post("/api/groups/$chatId/leave")
    suspend fun joinGroup(inviteCode: String): JSONObject = post("/api/groups/join/${URLEncoder.encode(inviteCode, StandardCharsets.UTF_8.toString())}")

    suspend fun createMeeting(title: String = "Zool Meet", requireApproval: Boolean = true, scheduledFor: String? = null): JSONObject = post(
        "/api/meetings", JSONObject().put("title", title).put("requireApproval", requireApproval).apply { if (!scheduledFor.isNullOrBlank()) put("scheduledFor", scheduledFor) },
    ).getJSONObject("meeting")
    suspend fun joinMeeting(code: String): JSONObject = post("/api/meetings/join", JSONObject().put("code", code))
    suspend fun meetingJoinStatus(meetingId: String): JSONObject = get("/api/meetings/$meetingId/join-status")
    suspend fun meetingInfo(meetingId: String): JSONObject = get("/api/meetings/$meetingId").getJSONObject("meeting")
    suspend fun admitMeetingParticipant(meetingId: String, userId: String): JSONObject = post("/api/meetings/$meetingId/participants/$userId/admit")
    suspend fun denyMeetingParticipant(meetingId: String, userId: String): JSONObject = post("/api/meetings/$meetingId/participants/$userId/deny")
    suspend fun leaveMeeting(meetingId: String): JSONObject = post("/api/meetings/$meetingId/leave")
    suspend fun endMeeting(meetingId: String): JSONObject = post("/api/meetings/$meetingId/end")

    suspend fun getPrivacy(): JSONObject = get("/api/privacy").getJSONObject("privacy")
    suspend fun updatePrivacy(
        readReceipts: Boolean? = null,
        showLastSeen: Boolean? = null,
        showOnline: Boolean? = null,
        profilePhotoVisibility: String? = null,
        aboutVisibility: String? = null,
    ): JSONObject = put(
        "/api/privacy",
        JSONObject().apply {
readReceipts?.let { put("readReceipts", it) }
showLastSeen?.let { put("showLastSeen", it) }
showOnline?.let { put("showOnline", it) }
profilePhotoVisibility?.let { put("profilePhotoVisibility", it) }
aboutVisibility?.let { put("aboutVisibility", it) }
        },
    ).getJSONObject("privacy")

    suspend fun exportAccount(): JSONObject = get("/api/account/export")
    suspend fun deleteAccount() = delete("/api/account")
}
