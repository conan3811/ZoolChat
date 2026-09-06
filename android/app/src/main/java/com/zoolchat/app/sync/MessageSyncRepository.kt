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
import com.zoolchat.app.data.local.CachedChatEntity
import com.zoolchat.app.data.local.CachedMessageEntity
import com.zoolchat.app.data.local.PendingMessageEntity
import com.zoolchat.app.data.local.ZoolDatabase
import com.zoolchat.app.network.ApiClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
        if ((!message.encrypted && !message.body.startsWith("zce1:")) || message.deleted) return message
        val chat = db.chats().byId(message.chatId) ?: return message.copy(body = "🔒 رسالة مشفرة", decryptionFailed = true)
        val peer = chat.peerUserId ?: return message.copy(body = "🔒 رسالة مشفرة", decryptionFailed = true)
        val clientId = message.clientId ?: return message.copy(body = "🔒 تعذر فك التشفير", decryptionFailed = true)
        return runCatching {
            val plain = crypto.decryptMessage(message.chatId, peer, message.senderId, clientId, message.type, message.body)
            if (message.type == "text") message.copy(body = plain, decryptionFailed = false)
            else {
                val meta = JSONObject(plain)
                val name = meta.optString("name").ifBlank { "مرفق" }
                message.copy(
                    body = name,
                    mediaName = name,
                    mediaMime = meta.optString("mime").takeIf { it.isNotBlank() } ?: message.mediaMime,
                    mediaSize = meta.optLong("size").takeIf { it > 0 } ?: message.mediaSize,
                    decryptionFailed = false,
                )
            }
        }.getOrElse { message.copy(body = "🔒 تعذر فك تشفير هذه الرسالة", decryptionFailed = true) }
    }

    private suspend fun decodeAll(items: List<RemoteMessage>): List<RemoteMessage> = items.map { decode(it) }

    fun observeChats(): Flow<List<RemoteChat>> = db.chats().observeAll().map { list -> list.map { it.toRemote(localCrypto) } }
    fun observeMessages(chatId: String): Flow<List<RemoteMessage>> = db.messages().observeChat(chatId).map { list -> list.map { it.toRemote(localCrypto) } }

    private suspend fun updatePreview(chatId: String, message: String, at: String) =
        db.chats().updateLastMessage(chatId, localCrypto.protect(message) ?: "", at)

    private fun previewFor(message: RemoteMessage): String = when {
        message.deleted -> "تم حذف الرسالة"
        message.type == "text" -> message.body
        else -> "📎 ${message.mediaName ?: message.body.ifBlank { "مرفق" }}"
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
        val items = (0 until arr.length()).map { i ->
            val remote = parseChat(arr.getJSONObject(i))
            if (remote.lastMessage == "🔒 رسالة مشفرة") {
                val local = db.messages().latestForChat(remote.id)
                val preview = local?.let {
                    val body = localCrypto.reveal(it.body).orEmpty()
                    val name = localCrypto.reveal(it.mediaName)
                    if (it.type == "text") body else "📎 ${name ?: body}"
                }
                if (!preview.isNullOrBlank()) remote.copy(lastMessage = preview) else remote
            } else remote
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
        db.messages().upsert(decoded.toEntity(localCrypto))
        updatePreview(decoded.chatId, previewFor(decoded), decoded.createdAt)
    }

    suspend fun onDeletedMessage(messageId: String) {
        db.messages().markDeleted(messageId)
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
            val source=File(requireNotNull(uri.path))
            source.inputStream().use { input -> localCrypto.protectStream(input, localFile) }
            if (source.absolutePath.startsWith(File(context.filesDir,"voice_notes").absolutePath)) runCatching { source.delete() }
        } else {
            context.contentResolver.openInputStream(uri).use { input -> requireNotNull(input) { "cannot_open_media" }; localCrypto.protectStream(input, localFile) }
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
        val peer = chat.peerUserId ?: error("e2ee_direct_only")
        val clientId = local.clientId ?: error("message_client_id_missing")
        val envelope = crypto.encryptMessage(chatId, peer, local.senderId, clientId, "text", newBody)
        val remote = parseMessage(api.editMessage(chatId, messageId, envelope.body, envelope.encrypted, envelope.cryptoVersion))
        val decoded = decode(remote)
        db.messages().upsert(decoded.toEntity(localCrypto))
        return decoded
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

    suspend fun clearUnread(chatId: String) { db.chats().clearUnread(chatId); com.zoolchat.app.notifications.NotificationHelper.clearMessage(context, chatId) }

    suspend fun markRead(chatId: String, messageId: String) { clearUnread(chatId); api.markRead(chatId, messageId) }

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

        fun parseChat(o: JSONObject)=RemoteChat(o.getString("id"),o.getString("type"),o.getString("title"),o.optString("peerUserId").takeIf{it.isNotBlank()},o.optString("lastMessage").takeIf{it.isNotBlank()},o.optString("lastMessageAt").takeIf{it.isNotBlank()},o.optInt("unread",0))
        fun parseMessage(o: JSONObject)=RemoteMessage(
            id=o.getString("id"), chatId=o.getString("chatId"), senderId=o.getString("senderId"),
            type=o.optString("type","text"), body=o.optString("body"), createdAt=o.getString("createdAt"),
            editedAt=o.optString("editedAt").takeIf{it.isNotBlank()}, deleted=o.optBoolean("deleted",false),
            status=o.optString("status","sent"), clientId=o.optString("clientId").takeIf{it.isNotBlank()},
            mediaUrl=o.optJSONObject("media")?.optString("url")?.takeIf{it.isNotBlank()},
            mediaMime=o.optJSONObject("media")?.optString("mime")?.takeIf{it.isNotBlank()},
            mediaName=o.optJSONObject("media")?.optString("name")?.takeIf{it.isNotBlank()},
            mediaSize=o.optJSONObject("media")?.optLong("size")?.takeIf{it>0},
            encrypted=o.optBoolean("encrypted",false) || o.optString("body").startsWith("zce1:"), cryptoVersion=o.optInt("cryptoVersion",if(o.optString("body").startsWith("zce1:")) 1 else 0),
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
            try {
                val chat = db.chats().byId(item.chatId) ?: error("chat_not_cached")
                val peer = chat.peerUserId ?: error("e2ee_direct_only")
                var mediaId: String? = null
                val pendingBody = localCrypto.reveal(item.body).orEmpty()
                val pendingMediaName = localCrypto.reveal(item.mediaName)
                var plainBody = pendingBody
                var encryptedTemp: File? = null
                if (item.localUri != null) {
                    val sourceUri = Uri.parse(item.localUri)
                    val protectedSource = if(sourceUri.scheme=="file") File(requireNotNull(sourceUri.path)) else error("pending_media_must_be_local_file")
                    val sourceFile = File.createTempFile("zool_pending_plain_", ".tmp", applicationContext.cacheDir)
                    localCrypto.revealFile(protectedSource, sourceFile)
                    encryptedTemp = File.createTempFile("zool_media_", ".zce", applicationContext.cacheDir)
                    try {
                        crypto.encryptMediaFile(item.chatId,peer,item.clientId,item.type,sourceFile,encryptedTemp)
                        val media = api.uploadMedia(Uri.fromFile(encryptedTemp), "application/octet-stream", "zool-media.zce")
                        mediaId = media.getString("id")
                        plainBody = JSONObject()
                            .put("name", pendingMediaName ?: pendingBody.ifBlank { "مرفق" })
                            .put("mime", item.mediaMime ?: "application/octet-stream")
                            .put("size", sourceFile.length())
                            .put("mediaEncrypted", true)
                            .toString()
                    } finally { sourceFile.delete() }
                }
                val envelope = crypto.encryptMessage(item.chatId,peer,item.senderId,item.clientId,item.type,plainBody)
                val remoteRaw = MessageSyncRepository.parseMessage(api.sendMessage(item.chatId,item.clientId,item.type,envelope.body,mediaId,envelope.encrypted,envelope.cryptoVersion))
                encryptedTemp?.delete()
                val remote = remoteRaw.copy(body=pendingBody,mediaName=pendingMediaName,mediaMime=item.mediaMime,encrypted=true,cryptoVersion=1,decryptionFailed=false)
                db.withTransaction {
                    db.messages().deleteById("local:${item.clientId}")
                    db.messages().upsert(remote.toEntity(localCrypto))
                    db.pending().delete(item.clientId)
                }
                item.localUri?.let { uriText -> runCatching { if (Uri.parse(uriText).scheme == "file") File(requireNotNull(Uri.parse(uriText).path)).delete() } }
            } catch (_: Throwable) {
                db.pending().incrementAttempts(item.clientId)
                if (item.attempts >= 4) {
                    db.messages().updateStatusByClientId(item.clientId, "failed")
                    continue
                }
                return Result.retry()
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
            val repo = MessageSyncRepository(applicationContext,api,db)
            val items = (0 until page.messages.length()).map { MessageSyncRepository.parseMessage(page.messages.getJSONObject(it)) }
            items.forEach { repo.onRemoteMessage(it) }
            Result.success()
        } catch (_: Throwable) {
            Result.retry()
        }
    }
}

private fun CachedChatEntity.toRemote(local: LocalStorageCrypto)=RemoteChat(id,type,title,peerUserId,local.reveal(lastMessage),lastMessageAt,unread)
private fun RemoteChat.toEntity(local: LocalStorageCrypto)=CachedChatEntity(id,type,title,peerUserId,local.protect(lastMessage),lastMessageAt,unread)
private fun CachedMessageEntity.toRemote(local: LocalStorageCrypto)=RemoteMessage(id,chatId,senderId,type,local.reveal(body).orEmpty(),createdAt,editedAt,deleted,status,clientId,mediaUrl,mediaMime,local.reveal(mediaName),mediaSize,encrypted,cryptoVersion,decryptionFailed)
fun RemoteMessage.toEntity(local: LocalStorageCrypto)=CachedMessageEntity(id,clientId,chatId,senderId,type,local.protect(body).orEmpty(),createdAt,status,editedAt,deleted,mediaUrl,mediaMime,local.protect(mediaName),mediaSize,encrypted,cryptoVersion,decryptionFailed)
