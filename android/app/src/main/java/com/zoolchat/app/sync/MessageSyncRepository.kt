package com.zoolchat.app.sync

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.zoolchat.app.auth.AuthRepository
import com.zoolchat.app.auth.SessionStore
import com.zoolchat.app.crypto.E2eeManager
import com.zoolchat.app.crypto.LocalStorageCrypto
import com.zoolchat.app.data.RemoteChat
import com.zoolchat.app.data.RemoteMessage
import com.zoolchat.app.data.RemoteReaction
import com.zoolchat.app.data.local.CachedChatEntity
import com.zoolchat.app.data.local.CachedMessageEntity
import com.zoolchat.app.data.local.PendingMessageEntity
import com.zoolchat.app.data.local.ZoolDatabase
import com.zoolchat.app.network.ApiClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

data class PageLoadResult(val hasMore: Boolean, val nextCursor: String?)

class MessageSyncRepository(
    private val context: Context,
    private val api: ApiClient,
    private val db: ZoolDatabase = ZoolDatabase.get(context),
    private val crypto: E2eeManager = E2eeManager(context, api, SessionStore(context)),
    private val localCrypto: LocalStorageCrypto = LocalStorageCrypto(context),
) {
    private suspend fun decode(message: RemoteMessage): RemoteMessage {
        val directCipher=message.body.startsWith("zce1:")
        val groupCipher=message.body.startsWith("zcg2:") || message.cryptoVersion==2
        if ((!message.encrypted && !directCipher && !groupCipher) || message.deleted) return message
        val chat = db.chats().byId(message.chatId) ?: return message.copy(body = "🔒 رسالة مشفرة", decryptionFailed = true)
        val clientId = message.clientId ?: return message.copy(body = "🔒 تعذر فك التشفير", decryptionFailed = true)
        return runCatching {
            val plain = if(groupCipher) crypto.decryptGroupMessage(message.chatId,message.senderId,clientId,message.type,message.body)
                else crypto.decryptMessage(message.chatId,chat.peerUserId ?: error("peer_identity_missing"),message.senderId,clientId,message.type,message.body)
            if (message.type == "text") message.copy(body = plain, decryptionFailed = false)
            else {
                val meta = JSONObject(plain)
                if(groupCipher)meta.optString("mediaKeyB64").takeIf{it.isNotBlank()}?.let{crypto.cacheGroupMediaKey(message.chatId,clientId,it)}
                val name = meta.optString("name").ifBlank { "مرفق" }
                message.copy(
                    body = name,
                    mediaName = name,
                    mediaMime = meta.optString("mime").takeIf { it.isNotBlank() } ?: message.mediaMime,
                    mediaSize = meta.optLong("size").takeIf { it > 0 } ?: message.mediaSize,
                    decryptionFailed = false,
                )
            }
        }.getOrElse { failure ->
            val text=if(groupCipher && failure.message=="group_message_not_for_member") "🔒 هذه الرسالة سبقت انضمامك أو تفعيل التشفير لديك" else "🔒 تعذر فك تشفير هذه الرسالة"
            message.copy(body=text,decryptionFailed=true)
        }
    }

    private suspend fun decodeAll(items: List<RemoteMessage>): List<RemoteMessage> = items.map { decode(it) }

    fun observeChats(): Flow<List<RemoteChat>> = db.chats().observeAll().map { list -> list.map { it.toRemote(localCrypto) } }
    fun observeMessages(chatId: String): Flow<List<RemoteMessage>> = db.messages().observeChat(chatId).map { list -> list.map { it.toRemote(localCrypto) } }

    private suspend fun updatePreview(chatId: String, message: String, at: String) =
        db.chats().updateLastMessage(chatId, localCrypto.protect(message) ?: "", at)

    private fun previewFor(message: RemoteMessage, myUserId: String? = SessionStore(context).currentUserId): String = when {
        message.deleted -> "تم حذف هذه الرسالة"
        message.type == "call" -> callPreview(message, myUserId)
        message.type == "text" -> if (message.decryptionFailed || message.body.startsWith("zce1:") || message.body.startsWith("zcg2:")) "رسالة" else message.body
        message.type == "image" -> "📷 صورة"
        message.type == "video" -> "🎥 فيديو"
        message.type == "audio" -> "🎤 رسالة صوتية"
        message.type == "sticker" -> "🖼️ ملصق"
        message.type == "document" -> "📄 ${message.mediaName?.takeIf { it.isNotBlank() } ?: "مستند"}"
        else -> "📎 ${message.mediaName ?: message.body.ifBlank { "مرفق" }}"
    }

    private fun callPreview(message: RemoteMessage, myUserId: String?): String {
        val o = runCatching { JSONObject(message.body) }.getOrNull() ?: return "📞 مكالمة"
        val mode = o.optString("mode", "audio")
        val status = o.optString("status", "ended")
        val mine = !myUserId.isNullOrBlank() && message.senderId == myUserId
        val icon = if (mode == "video") "📹" else "📞"
        val kind = if (mode == "video") "مكالمة فيديو" else "مكالمة صوتية"
        return when (status) {
            "missed" -> if (mine) "$icon لم يتم الرد" else "$icon مكالمة فائتة"
            "declined" -> if (mine) "$icon تم رفض المكالمة" else "$icon مكالمة مرفوضة"
            else -> if (mine) "$icon $kind صادرة" else "$icon $kind واردة"
        }
    }

    suspend fun clearLocal() {
        db.withTransaction {
            db.pending().clearAll()
            db.messages().clearAll()
            db.chats().clearAll()
        }
        runCatching { File(context.filesDir, "pending_media").deleteRecursively() }
        runCatching { File(context.cacheDir, "zool_media").deleteRecursively() }
    }

    suspend fun refreshChats() {
        val arr = api.chats()
        val baseItems = (0 until arr.length()).map { i -> parseChat(arr.getJSONObject(i)) }

        // Cache chat identity first so direct-message decryption has the peer id available even
        // immediately after a reinstall when the local Room database starts empty.
        db.withTransaction {
            db.chats().upsertAll(baseItems.map { it.toEntity(localCrypto) })
            if (baseItems.isEmpty()) db.chats().clearAll() else db.chats().deleteMissing(baseItems.map { it.id })
        }

        val myUserId = SessionStore(context).currentUserId
        val items = baseItems.map { remote ->
            val preview = remote.lastMessage.orEmpty()
            val needsClientPreview = preview.isBlank() || preview == "🔒 رسالة مشفرة" || preview == "📞 مكالمة" || preview.startsWith("zce1:") || preview.startsWith("zcg2:") || preview.contains("callId\"")
            if (!needsClientPreview) return@map remote

            val latest = runCatching { api.messages(remote.id, limit = 1) }.getOrNull()
                ?.messages?.takeIf { it.length() > 0 }
                ?.getJSONObject(0)
                ?.let(::parseMessage)
                ?.let { runCatching { decode(it) }.getOrElse { _ -> it.copy(body = "رسالة", decryptionFailed = true) } }
            if (latest == null) remote.copy(lastMessage = null)
            else {
                db.messages().upsert(latest.toEntity(localCrypto))
                remote.copy(lastMessage = previewFor(latest, myUserId), lastMessageAt = latest.createdAt)
            }
        }

        db.withTransaction {
            db.chats().upsertAll(items.map { it.toEntity(localCrypto) })
            if (items.isEmpty()) db.chats().clearAll() else db.chats().deleteMissing(items.map { it.id })
        }
    }

    suspend fun refreshMessages(chatId: String): PageLoadResult {
        val page = api.messages(chatId, limit = 50)
        val items = decodeAll((0 until page.messages.length()).map { i -> parseMessage(page.messages.getJSONObject(i)) })
        db.messages().upsertAll(items.map { it.toEntity(localCrypto) })
        items.lastOrNull()?.let { last -> updatePreview(chatId, previewFor(last), last.createdAt) }
        items.forEach { if (!it.id.startsWith("local:")) runCatching { api.markDelivered(chatId, it.id) } }
        val myUserId = SessionStore(context).currentUserId
        items.lastOrNull { it.senderId != myUserId && !it.id.startsWith("local:") }?.let { latestIncoming ->
            runCatching { api.markRead(chatId, latestIncoming.id) }.onSuccess { clearUnread(chatId) }
        }
        return PageLoadResult(page.hasMore, page.nextCursor)
    }

    suspend fun loadOlder(chatId: String): PageLoadResult {
        val oldest = db.messages().oldestCreatedAt(chatId) ?: return refreshMessages(chatId)
        val page = api.messages(chatId, before = oldest, limit = 50)
        val items = decodeAll((0 until page.messages.length()).map { i -> parseMessage(page.messages.getJSONObject(i)) })
        db.messages().upsertAll(items.map { it.toEntity(localCrypto) })
        items.forEach { if (!it.id.startsWith("local:")) runCatching { api.markDelivered(chatId, it.id) } }
        return PageLoadResult(page.hasMore, page.nextCursor)
    }

    suspend fun onRemoteMessage(message: RemoteMessage) {
        val decoded = decode(message)
        db.messages().upsert(decoded.toEntity(localCrypto))
        updatePreview(decoded.chatId, previewFor(decoded), decoded.createdAt)
        api.markDelivered(message.chatId, message.id)
    }

    suspend fun onEditedMessage(message: RemoteMessage) {
        val decoded = decode(message)
        val existing = db.messages().byId(decoded.id)?.toRemote(localCrypto)
        val merged = if (decoded.reactions.isEmpty() && existing != null) decoded.copy(reactions = existing.reactions) else decoded
        db.messages().upsert(merged.toEntity(localCrypto))
        updatePreview(merged.chatId, previewFor(merged), merged.createdAt)
    }

    suspend fun onDeletedMessage(messageId: String) {
        db.messages().markDeleted(messageId)
    }

    suspend fun applyReactionSnapshot(messageId: String, reactions: JSONArray) {
        db.messages().updateReactions(messageId, reactions.toString())
    }

    suspend fun setReaction(chatId: String, messageId: String, emoji: String?) {
        val result = if (emoji.isNullOrBlank()) api.removeReaction(chatId, messageId) else api.reactMessage(chatId, messageId, emoji)
        applyReactionSnapshot(messageId, result.optJSONArray("reactions") ?: JSONArray())
    }

    suspend fun onReceipt(messageId: String, status: String) {
        db.messages().updateStatus(messageId, status)
    }

    suspend fun enqueueText(chatId: String, senderId: String, text: String) {
        val clientId = UUID.randomUUID().toString()
        val createdAt = Instant.now().toString()
        val localId = "local:$clientId"
        db.withTransaction {
            db.messages().upsert(CachedMessageEntity(localId, clientId, chatId, senderId, "text", localCrypto.protect(text) ?: "", createdAt, "pending"))
            db.pending().upsert(PendingMessageEntity(clientId, chatId, senderId, "text", localCrypto.protect(text) ?: "", createdAt = createdAt))
            db.chats().updateLastMessage(chatId, localCrypto.protect(text) ?: "", createdAt)
        }
        scheduleSend(context)
    }

    suspend fun enqueueMedia(chatId: String, senderId: String, uri: Uri, mime: String, displayName: String) {
        val clientId = UUID.randomUUID().toString()
        val createdAt = Instant.now().toString()
        val type = when {
            mime.startsWith("image/") -> "image"
            mime.startsWith("video/") -> "video"
            mime.startsWith("audio/") -> "audio"
            else -> "document"
        }
        val dir = File(context.filesDir, "pending_media").apply { mkdirs() }
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80).ifBlank { "attachment" }
        val localFile = File(dir, "${clientId}_$safeName.zlp")
        if (uri.scheme == "file") {
            val source = File(requireNotNull(uri.path))
            source.inputStream().use { input -> localCrypto.protectStream(input, localFile) }
            if (source.absolutePath.startsWith(File(context.filesDir, "voice_notes").absolutePath)) runCatching { source.delete() }
        } else {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "cannot_open_media" }
                localCrypto.protectStream(input, localFile)
            }
        }
        db.withTransaction {
            db.messages().upsert(CachedMessageEntity("local:$clientId", clientId, chatId, senderId, type, localCrypto.protect(displayName) ?: "", createdAt, "pending", mediaMime = mime, mediaName = localCrypto.protect(displayName)))
            db.pending().upsert(PendingMessageEntity(clientId, chatId, senderId, type, localCrypto.protect(displayName) ?: "", Uri.fromFile(localFile).toString(), mime, localCrypto.protect(displayName), createdAt))
            db.chats().updateLastMessage(chatId, localCrypto.protect("📎 $displayName") ?: "", createdAt)
        }
        scheduleSend(context)
    }

    suspend fun editMessage(chatId: String, messageId: String, newBody: String): RemoteMessage {
        val local = db.messages().byId(messageId) ?: error("message_not_cached")
        val chat = db.chats().byId(chatId) ?: error("chat_not_cached")
        val clientId = local.clientId ?: error("message_client_id_missing")
        val peer = chat.peerUserId
        val remote = if (peer != null) {
            val envelope = crypto.encryptMessage(chatId, peer, local.senderId, clientId, "text", newBody)
            parseMessage(api.editMessage(chatId, messageId, envelope.body, envelope.encrypted, envelope.cryptoVersion))
        } else {
            val envelope=crypto.encryptGroupMessage(chatId,local.senderId,clientId,"text",newBody)
            parseMessage(api.editMessage(chatId,messageId,envelope.body,envelope.encrypted,envelope.cryptoVersion))
        }
        val decoded = decode(remote)
        val merged = if (decoded.reactions.isEmpty()) decoded.copy(reactions = local.toRemote(localCrypto).reactions) else decoded
        db.messages().upsert(merged.toEntity(localCrypto))
        return merged
    }

    suspend fun retry(clientId: String) {
        val pending = db.pending().byClientId(clientId) ?: return
        db.withTransaction {
            db.pending().resetAttempts(clientId)
            db.messages().updateStatusByClientId(clientId, "pending")
        }
        if (pending.localUri != null) {
            val uri = Uri.parse(pending.localUri)
            if (uri.scheme == "file" && !File(requireNotNull(uri.path)).exists()) {
                db.messages().updateStatusByClientId(clientId, "failed")
                return
            }
        }
        scheduleSend(context)
    }

    suspend fun clearUnread(chatId: String) {
        db.chats().clearUnread(chatId)
        com.zoolchat.app.notifications.NotificationHelper.clearMessage(context, chatId)
    }

    suspend fun markRead(chatId: String, messageId: String) {
        api.markRead(chatId, messageId)
        clearUnread(chatId)
    }

    companion object {
        fun scheduleSend(context: Context) {
            val request = OneTimeWorkRequestBuilder<PendingMessageWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("zoolchat-pending-send", ExistingWorkPolicy.REPLACE, request)
        }

        fun scheduleIncomingSync(context: Context, chatId: String) {
            if (chatId.isBlank()) return
            val request = OneTimeWorkRequestBuilder<IncomingMessageSyncWorker>()
                .setInputData(workDataOf("chatId" to chatId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("zoolchat-incoming-$chatId", ExistingWorkPolicy.REPLACE, request)
        }

        private fun jsonStringOrNull(o: JSONObject, key: String): String? =
            if (!o.has(key) || o.isNull(key)) null else o.optString(key).takeIf { it.isNotBlank() && !it.equals("null", true) }

        private fun parseReactions(o: JSONObject): List<RemoteReaction> {
            val arr = o.optJSONArray("reactions") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val r = arr.optJSONObject(i) ?: return@mapNotNull null
                val emoji = r.optString("emoji").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                RemoteReaction(emoji, r.optInt("count", 0).coerceAtLeast(0), r.optBoolean("reactedByMe", false))
            }
        }

        fun parseChat(o: JSONObject) = RemoteChat(
            o.getString("id"),
            jsonStringOrNull(o, "type") ?: "direct",
            jsonStringOrNull(o, "title") ?: "محادثة",
            jsonStringOrNull(o, "peerUserId"),
            jsonStringOrNull(o, "lastMessage")?.takeUnless { it.startsWith("zce1:") || it.startsWith("zcg2:") || it == "🔒 رسالة مشفرة" },
            jsonStringOrNull(o, "lastMessageAt"),
            o.optInt("unread", 0),
        )

        fun parseMessage(o: JSONObject) = RemoteMessage(
            id = o.getString("id"),
            chatId = o.getString("chatId"),
            senderId = o.getString("senderId"),
            type = jsonStringOrNull(o, "type") ?: "text",
            body = jsonStringOrNull(o, "body") ?: "",
            createdAt = o.getString("createdAt"),
            editedAt = jsonStringOrNull(o, "editedAt"),
            deleted = o.optBoolean("deleted", false),
            status = jsonStringOrNull(o, "status") ?: "sent",
            clientId = jsonStringOrNull(o, "clientId"),
            mediaUrl = o.optJSONObject("media")?.let { jsonStringOrNull(it, "url") },
            mediaMime = o.optJSONObject("media")?.let { jsonStringOrNull(it, "mime") },
            mediaName = o.optJSONObject("media")?.let { jsonStringOrNull(it, "name") },
            mediaSize = o.optJSONObject("media")?.optLong("size")?.takeIf { it > 0 },
            encrypted = o.optBoolean("encrypted", false) || o.optString("body").startsWith("zce1:") || o.optString("body").startsWith("zcg2:"),
            cryptoVersion = o.optInt("cryptoVersion", if (o.optString("body").startsWith("zcg2:")) 2 else if (o.optString("body").startsWith("zce1:")) 1 else 0),
            reactions = parseReactions(o),
        )
    }
}

class PendingMessageWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val session = SessionStore(applicationContext)
        val auth = AuthRepository(applicationContext, session)
        if (!auth.signedIn && !com.zoolchat.app.BuildConfig.DEV_AUTH_ENABLED) return Result.retry()
        val api = ApiClient(auth, session)
        val db = ZoolDatabase.get(applicationContext)
        val crypto = E2eeManager(applicationContext, api, session)
        val localCrypto = LocalStorageCrypto(applicationContext)
        val pending = db.pending().next(20)
        if (pending.isEmpty()) return Result.success()

        for (item in pending) {
            var transportTemp: File? = null
            try {
                val chat = db.chats().byId(item.chatId) ?: error("chat_not_cached")
                val peer = chat.peerUserId
                var mediaId: String? = null
                val pendingBody = localCrypto.reveal(item.body).orEmpty()
                val pendingMediaName = localCrypto.reveal(item.mediaName)
                var transportBody = pendingBody

                if (item.localUri != null) {
                    val sourceUri = Uri.parse(item.localUri)
                    val protectedSource = if (sourceUri.scheme == "file") File(requireNotNull(sourceUri.path)) else error("pending_media_must_be_local_file")
                    val sourceFile = File.createTempFile("zool_pending_plain_", ".tmp", applicationContext.cacheDir)
                    localCrypto.revealFile(protectedSource, sourceFile)
                    try {
                        if (peer != null) {
                            transportTemp = File.createTempFile("zool_media_", ".zce", applicationContext.cacheDir)
                            crypto.encryptMediaFile(item.chatId, peer, item.clientId, item.type, sourceFile, transportTemp!!)
                            val media = api.uploadMedia(Uri.fromFile(transportTemp), "application/octet-stream", "zool-media.zce")
                            mediaId = media.getString("id")
                            transportBody = JSONObject()
                                .put("name", pendingMediaName ?: pendingBody.ifBlank { "مرفق" })
                                .put("mime", item.mediaMime ?: "application/octet-stream")
                                .put("size", sourceFile.length())
                                .put("mediaEncrypted", true)
                                .toString()
                        } else {
                            val mediaKey=crypto.newGroupMediaKey()
                            transportTemp=File.createTempFile("zool_group_media_", ".zcg", applicationContext.cacheDir)
                            crypto.encryptGroupMediaFile(item.chatId,item.clientId,item.type,sourceFile,transportTemp!!,mediaKey)
                            val media=api.uploadMedia(Uri.fromFile(transportTemp),"application/octet-stream","zool-group-media.zcg")
                            mediaId=media.getString("id")
                            transportBody=JSONObject()
                                .put("name",pendingMediaName ?: pendingBody.ifBlank { "مرفق" })
                                .put("mime",item.mediaMime ?: "application/octet-stream")
                                .put("size",sourceFile.length())
                                .put("mediaEncrypted",true)
                                .put("mediaCryptoVersion",2)
                                .put("mediaKeyB64",mediaKey)
                                .toString()
                        }
                    } finally {
                        sourceFile.delete()
                    }
                }

                val remoteRaw = if (peer != null) {
                    val envelope = crypto.encryptMessage(item.chatId, peer, item.senderId, item.clientId, item.type, transportBody)
                    MessageSyncRepository.parseMessage(
                        api.sendMessage(item.chatId, item.clientId, item.type, envelope.body, mediaId, envelope.encrypted, envelope.cryptoVersion),
                    )
                } else {
                    val envelope=crypto.encryptGroupMessage(item.chatId,item.senderId,item.clientId,item.type,transportBody)
                    MessageSyncRepository.parseMessage(
                        api.sendMessage(item.chatId,item.clientId,item.type,envelope.body,mediaId,envelope.encrypted,envelope.cryptoVersion),
                    )
                }

                val remote = if (peer != null) {
                    remoteRaw.copy(
                        body = pendingBody,
                        mediaName = pendingMediaName,
                        mediaMime = item.mediaMime,
                        encrypted = true,
                        cryptoVersion = 1,
                        decryptionFailed = false,
                    )
                } else {
                    remoteRaw.copy(
                        body = pendingBody,
                        mediaName = pendingMediaName ?: remoteRaw.mediaName,
                        mediaMime = item.mediaMime ?: remoteRaw.mediaMime,
                        encrypted = true,
                        cryptoVersion = 2,
                        decryptionFailed = false,
                    )
                }

                db.withTransaction {
                    db.messages().deleteById("local:${item.clientId}")
                    db.messages().upsert(remote.toEntity(localCrypto))
                    db.pending().delete(item.clientId)
                }
                item.localUri?.let { uriText ->
                    runCatching {
                        val uri = Uri.parse(uriText)
                        if (uri.scheme == "file") File(requireNotNull(uri.path)).delete()
                    }
                }
            } catch (_: Throwable) {
                db.pending().incrementAttempts(item.clientId)
                if (item.attempts >= 4) {
                    db.messages().updateStatusByClientId(item.clientId, "failed")
                    continue
                }
                return Result.retry()
            } finally {
                transportTemp?.delete()
            }
        }
        return Result.success()
    }
}

class IncomingMessageSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val chatId = inputData.getString("chatId").orEmpty()
        if (chatId.isBlank()) return Result.success()
        val session = SessionStore(applicationContext)
        val auth = AuthRepository(applicationContext, session)
        if (!auth.signedIn && !com.zoolchat.app.BuildConfig.DEV_AUTH_ENABLED) return Result.retry()
        val api = ApiClient(auth, session)
        val db = ZoolDatabase.get(applicationContext)
        val localCrypto = LocalStorageCrypto(applicationContext)
        return try {
            val chats = api.chats()
            val parsed = (0 until chats.length()).map { MessageSyncRepository.parseChat(chats.getJSONObject(it)) }
            db.withTransaction {
                db.chats().upsertAll(parsed.map { it.toEntity(localCrypto) })
                if (parsed.isEmpty()) db.chats().clearAll() else db.chats().deleteMissing(parsed.map { it.id })
            }
            val page = api.messages(chatId, limit = 50)
            val repo = MessageSyncRepository(applicationContext, api, db)
            val items = (0 until page.messages.length()).map { MessageSyncRepository.parseMessage(page.messages.getJSONObject(it)) }
            items.forEach { repo.onRemoteMessage(it) }
            Result.success()
        } catch (_: Throwable) {
            Result.retry()
        }
    }
}

private fun CachedChatEntity.toRemote(local: LocalStorageCrypto) = RemoteChat(id, type, title, peerUserId, local.reveal(lastMessage), lastMessageAt, unread)
private fun RemoteChat.toEntity(local: LocalStorageCrypto) = CachedChatEntity(id, type, title, peerUserId, local.protect(lastMessage), lastMessageAt, unread)
private fun reactionsFromJson(raw: String): List<RemoteReaction> = runCatching {
    val arr = JSONArray(raw)
    (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val emoji = o.optString("emoji").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        RemoteReaction(emoji, o.optInt("count", 0).coerceAtLeast(0), o.optBoolean("reactedByMe", false))
    }
}.getOrDefault(emptyList())
private fun reactionsToJson(items: List<RemoteReaction>): String = JSONArray().apply {
    items.forEach { r -> put(JSONObject().put("emoji", r.emoji).put("count", r.count).put("reactedByMe", r.reactedByMe)) }
}.toString()

private fun CachedMessageEntity.toRemote(local: LocalStorageCrypto) = RemoteMessage(id, chatId, senderId, type, local.reveal(body).orEmpty(), createdAt, editedAt, deleted, status, clientId, mediaUrl, mediaMime, local.reveal(mediaName), mediaSize, encrypted, cryptoVersion, decryptionFailed, reactionsFromJson(reactionsJson))
fun RemoteMessage.toEntity(local: LocalStorageCrypto) = CachedMessageEntity(id, clientId, chatId, senderId, type, local.protect(body).orEmpty(), createdAt, status, editedAt, deleted, mediaUrl, mediaMime, local.protect(mediaName), mediaSize, encrypted, cryptoVersion, decryptionFailed, reactionsToJson(reactions))
