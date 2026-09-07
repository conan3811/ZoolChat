from pathlib import Path
import re
import textwrap

ROOT = Path(__file__).resolve().parents[1]


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


def write(rel: str, text: str) -> None:
    (ROOT / rel).write_text(text, encoding="utf-8")


def replace_once(rel: str, old: str, new: str) -> None:
    text = read(rel)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{rel}: expected one exact anchor, found {count}: {old[:120]!r}")
    write(rel, text.replace(old, new, 1))


def regex_once(rel: str, pattern: str, replacement: str, flags: int = re.S) -> None:
    text = read(rel)
    new_text, count = re.subn(pattern, replacement, text, count=1, flags=flags)
    if count != 1:
        raise SystemExit(f"{rel}: expected one regex anchor, found {count}: {pattern[:120]!r}")
    write(rel, new_text)


def append_once(rel: str, marker: str, block: str) -> None:
    text = read(rel)
    if marker in text:
        return
    write(rel, text.rstrip() + "\n\n" + block.strip() + "\n")


# ---------------------------------------------------------------------------
# Android data models + durable reaction cache
# ---------------------------------------------------------------------------
replace_once(
    "android/app/src/main/java/com/zoolchat/app/data/ProductionModels.kt",
    """data class RemoteMessage(\n""",
    """data class RemoteReaction(\n    val emoji: String,\n    val count: Int,\n    val reactedByMe: Boolean,\n)\n\ndata class RemoteMessage(\n""",
)
replace_once(
    "android/app/src/main/java/com/zoolchat/app/data/ProductionModels.kt",
    """    val decryptionFailed: Boolean = false,\n)\n""",
    """    val decryptionFailed: Boolean = false,\n    val reactions: List<RemoteReaction> = emptyList(),\n)\n""",
)

replace_once(
    "android/app/src/main/java/com/zoolchat/app/data/local/ZoolDatabase.kt",
    """    val decryptionFailed: Boolean = false,\n)\n""",
    """    val decryptionFailed: Boolean = false,\n    val reactionsJson: String = \"[]\",\n)\n""",
)
replace_once(
    "android/app/src/main/java/com/zoolchat/app/data/local/ZoolDatabase.kt",
    """    @Query(\"UPDATE cached_messages SET body='', deleted=1 WHERE id=:messageId\")\n    suspend fun markDeleted(messageId: String)\n""",
    """    @Query(\"UPDATE cached_messages SET body='', deleted=1 WHERE id=:messageId\")\n    suspend fun markDeleted(messageId: String)\n\n    @Query(\"UPDATE cached_messages SET reactionsJson=:reactionsJson WHERE id=:messageId\")\n    suspend fun updateReactions(messageId: String, reactionsJson: String)\n""",
)
replace_once(
    "android/app/src/main/java/com/zoolchat/app/data/local/ZoolDatabase.kt",
    """    version = 3,\n""",
    """    version = 4,\n""",
)
replace_once(
    "android/app/src/main/java/com/zoolchat/app/data/local/ZoolDatabase.kt",
    """        private val MIGRATION_2_3 = object : Migration(2, 3) {\n            override fun migrate(db: SupportSQLiteDatabase) {\n                db.execSQL(\"ALTER TABLE cached_messages ADD COLUMN encrypted INTEGER NOT NULL DEFAULT 0\")\n                db.execSQL(\"ALTER TABLE cached_messages ADD COLUMN cryptoVersion INTEGER NOT NULL DEFAULT 0\")\n                db.execSQL(\"ALTER TABLE cached_messages ADD COLUMN decryptionFailed INTEGER NOT NULL DEFAULT 0\")\n            }\n        }\n""",
    """        private val MIGRATION_2_3 = object : Migration(2, 3) {\n            override fun migrate(db: SupportSQLiteDatabase) {\n                db.execSQL(\"ALTER TABLE cached_messages ADD COLUMN encrypted INTEGER NOT NULL DEFAULT 0\")\n                db.execSQL(\"ALTER TABLE cached_messages ADD COLUMN cryptoVersion INTEGER NOT NULL DEFAULT 0\")\n                db.execSQL(\"ALTER TABLE cached_messages ADD COLUMN decryptionFailed INTEGER NOT NULL DEFAULT 0\")\n            }\n        }\n        private val MIGRATION_3_4 = object : Migration(3, 4) {\n            override fun migrate(db: SupportSQLiteDatabase) {\n                db.execSQL(\"ALTER TABLE cached_messages ADD COLUMN reactionsJson TEXT NOT NULL DEFAULT '[]'\")\n            }\n        }\n""",
)
replace_once(
    "android/app/src/main/java/com/zoolchat/app/data/local/ZoolDatabase.kt",
    """            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }\n""",
    """            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { INSTANCE = it }\n""",
)

# ---------------------------------------------------------------------------
# API: message reactions
# ---------------------------------------------------------------------------
replace_once(
    "android/app/src/main/java/com/zoolchat/app/network/ApiClient.kt",
    """    suspend fun deleteMessage(chatId: String, messageId: String) = delete(\"/api/chats/$chatId/messages/$messageId\")\n    suspend fun markDelivered(chatId: String, messageId: String) = post(\"/api/chats/$chatId/delivered\", JSONObject().put(\"messageId\", messageId))\n""",
    """    suspend fun deleteMessage(chatId: String, messageId: String) = delete(\"/api/chats/$chatId/messages/$messageId\")\n    suspend fun reactMessage(chatId: String, messageId: String, emoji: String): JSONObject = put(\n        \"/api/chats/$chatId/messages/$messageId/reaction\",\n        JSONObject().put(\"emoji\", emoji),\n    )\n    suspend fun removeReaction(chatId: String, messageId: String): JSONObject = delete(\"/api/chats/$chatId/messages/$messageId/reaction\")\n    suspend fun markDelivered(chatId: String, messageId: String) = post(\"/api/chats/$chatId/delivered\", JSONObject().put(\"messageId\", messageId))\n""",
)

# ---------------------------------------------------------------------------
# Sync: null-safe JSON parsing, no ciphertext/call JSON in chat list,
# detailed WhatsApp-like call/media previews, and reaction persistence.
# ---------------------------------------------------------------------------
replace_once(
    "android/app/src/main/java/com/zoolchat/app/sync/MessageSyncRepository.kt",
    """import com.zoolchat.app.data.RemoteMessage\n""",
    """import com.zoolchat.app.data.RemoteMessage\nimport com.zoolchat.app.data.RemoteReaction\n""",
)
replace_once(
    "android/app/src/main/java/com/zoolchat/app/sync/MessageSyncRepository.kt",
    """import org.json.JSONObject\n""",
    """import org.json.JSONArray\nimport org.json.JSONObject\n""",
)
replace_once(
    "android/app/src/main/java/com/zoolchat/app/sync/MessageSyncRepository.kt",
    """    private fun previewFor(message: RemoteMessage): String = when {\n        message.deleted -> \"تم حذف الرسالة\"\n        message.type == \"text\" -> message.body\n        else -> \"📎 ${message.mediaName ?: message.body.ifBlank { \"مرفق\" }}\"\n    }\n""",
    """    private fun previewFor(message: RemoteMessage, myUserId: String? = SessionStore(context).currentUserId): String = when {\n        message.deleted -> \"تم حذف هذه الرسالة\"\n        message.type == \"call\" -> callPreview(message, myUserId)\n        message.type == \"text\" -> if (message.decryptionFailed || message.body.startsWith(\"zce1:\")) \"رسالة\" else message.body\n        message.type == \"image\" -> \"📷 صورة\"\n        message.type == \"video\" -> \"🎥 فيديو\"\n        message.type == \"audio\" -> \"🎤 رسالة صوتية\"\n        message.type == \"sticker\" -> \"🖼️ ملصق\"\n        message.type == \"document\" -> \"📄 ${message.mediaName?.takeIf { it.isNotBlank() } ?: \"مستند\"}\"\n        else -> \"📎 ${message.mediaName ?: message.body.ifBlank { \"مرفق\" }}\"\n    }\n\n    private fun callPreview(message: RemoteMessage, myUserId: String?): String {\n        val o = runCatching { JSONObject(message.body) }.getOrNull() ?: return \"📞 مكالمة\"\n        val mode = o.optString(\"mode\", \"audio\")\n        val status = o.optString(\"status\", \"ended\")\n        val mine = !myUserId.isNullOrBlank() && message.senderId == myUserId\n        val icon = if (mode == \"video\") \"📹\" else \"📞\"\n        val kind = if (mode == \"video\") \"مكالمة فيديو\" else \"مكالمة صوتية\"\n        return when (status) {\n            \"missed\" -> if (mine) \"$icon لم يتم الرد\" else \"$icon مكالمة فائتة\"\n            \"declined\" -> if (mine) \"$icon تم رفض المكالمة\" else \"$icon مكالمة مرفوضة\"\n            else -> if (mine) \"$icon $kind صادرة\" else \"$icon $kind واردة\"\n        }\n    }\n""",
)
regex_once(
    "android/app/src/main/java/com/zoolchat/app/sync/MessageSyncRepository.kt",
    r"    suspend fun refreshChats\(\) \{.*?\n    \}\n\n    suspend fun refreshMessages",
    """    suspend fun refreshChats() {\n        val arr = api.chats()\n        val baseItems = (0 until arr.length()).map { i -> parseChat(arr.getJSONObject(i)) }\n\n        // Cache chat identity first so direct-message decryption has the peer id available even\n        // immediately after a reinstall when the local Room database starts empty.\n        db.withTransaction {\n            db.chats().upsertAll(baseItems.map { it.toEntity(localCrypto) })\n            if (baseItems.isEmpty()) db.chats().clearAll() else db.chats().deleteMissing(baseItems.map { it.id })\n        }\n\n        val myUserId = SessionStore(context).currentUserId\n        val items = baseItems.map { remote ->\n            val preview = remote.lastMessage.orEmpty()\n            val needsClientPreview = preview.isBlank() || preview == \"🔒 رسالة مشفرة\" || preview == \"📞 مكالمة\" || preview.startsWith(\"zce1:\") || preview.contains(\"callId\\\"\")\n            if (!needsClientPreview) return@map remote\n\n            val latest = runCatching { api.messages(remote.id, limit = 1) }.getOrNull()\n                ?.messages?.takeIf { it.length() > 0 }\n                ?.getJSONObject(0)\n                ?.let(::parseMessage)\n                ?.let { runCatching { decode(it) }.getOrElse { _ -> it.copy(body = \"رسالة\", decryptionFailed = true) } }\n            if (latest == null) remote.copy(lastMessage = null)\n            else {\n                db.messages().upsert(latest.toEntity(localCrypto))\n                remote.copy(lastMessage = previewFor(latest, myUserId), lastMessageAt = latest.createdAt)\n            }\n        }\n\n        db.withTransaction {\n            db.chats().upsertAll(items.map { it.toEntity(localCrypto) })\n            if (items.isEmpty()) db.chats().clearAll() else db.chats().deleteMissing(items.map { it.id })\n        }\n    }\n\n    suspend fun refreshMessages""",
)
replace_once(
    "android/app/src/main/java/com/zoolchat/app/sync/MessageSyncRepository.kt",
    """    suspend fun onDeletedMessage(messageId: String) {\n        db.messages().markDeleted(messageId)\n    }\n\n    suspend fun onReceipt(messageId: String, status: String) {\n""",
    """    suspend fun onDeletedMessage(messageId: String) {\n        db.messages().markDeleted(messageId)\n    }\n\n    suspend fun applyReactionSnapshot(messageId: String, reactions: JSONArray) {\n        db.messages().updateReactions(messageId, reactions.toString())\n    }\n\n    suspend fun setReaction(chatId: String, messageId: String, emoji: String?) {\n        val result = if (emoji.isNullOrBlank()) api.removeReaction(chatId, messageId) else api.reactMessage(chatId, messageId, emoji)\n        applyReactionSnapshot(messageId, result.optJSONArray(\"reactions\") ?: JSONArray())\n    }\n\n    suspend fun onReceipt(messageId: String, status: String) {\n""",
)
replace_once(
    "android/app/src/main/java/com/zoolchat/app/sync/MessageSyncRepository.kt",
    """        val decoded = decode(remote)\n        db.messages().upsert(decoded.toEntity(localCrypto))\n        return decoded\n""",
    """        val decoded = decode(remote)\n        val merged = if (decoded.reactions.isEmpty()) decoded.copy(reactions = local.toRemote(localCrypto).reactions) else decoded\n        db.messages().upsert(merged.toEntity(localCrypto))\n        return merged\n""",
)
regex_once(
    "android/app/src/main/java/com/zoolchat/app/sync/MessageSyncRepository.kt",
    r"        fun parseChat\(o: JSONObject\) = RemoteChat\(.*?\n        \)\n\n        fun parseMessage\(o: JSONObject\) = RemoteMessage\(.*?\n        \)\n",
    """        private fun jsonStringOrNull(o: JSONObject, key: String): String? =\n            if (!o.has(key) || o.isNull(key)) null else o.optString(key).takeIf { it.isNotBlank() && !it.equals(\"null\", true) }\n\n        private fun parseReactions(o: JSONObject): List<RemoteReaction> {\n            val arr = o.optJSONArray(\"reactions\") ?: return emptyList()\n            return (0 until arr.length()).mapNotNull { i ->\n                val r = arr.optJSONObject(i) ?: return@mapNotNull null\n                val emoji = r.optString(\"emoji\").takeIf { it.isNotBlank() } ?: return@mapNotNull null\n                RemoteReaction(emoji, r.optInt(\"count\", 0).coerceAtLeast(0), r.optBoolean(\"reactedByMe\", false))\n            }\n        }\n\n        fun parseChat(o: JSONObject) = RemoteChat(\n            o.getString(\"id\"),\n            jsonStringOrNull(o, \"type\") ?: \"direct\",\n            jsonStringOrNull(o, \"title\") ?: \"محادثة\",\n            jsonStringOrNull(o, \"peerUserId\"),\n            jsonStringOrNull(o, \"lastMessage\")?.takeUnless { it.startsWith(\"zce1:\") || it == \"🔒 رسالة مشفرة\" },\n            jsonStringOrNull(o, \"lastMessageAt\"),\n            o.optInt(\"unread\", 0),\n        )\n\n        fun parseMessage(o: JSONObject) = RemoteMessage(\n            id = o.getString(\"id\"),\n            chatId = o.getString(\"chatId\"),\n            senderId = o.getString(\"senderId\"),\n            type = jsonStringOrNull(o, \"type\") ?: \"text\",\n            body = jsonStringOrNull(o, \"body\") ?: \"\",\n            createdAt = o.getString(\"createdAt\"),\n            editedAt = jsonStringOrNull(o, \"editedAt\"),\n            deleted = o.optBoolean(\"deleted\", false),\n            status = jsonStringOrNull(o, \"status\") ?: \"sent\",\n            clientId = jsonStringOrNull(o, \"clientId\"),\n            mediaUrl = o.optJSONObject(\"media\")?.let { jsonStringOrNull(it, \"url\") },\n            mediaMime = o.optJSONObject(\"media\")?.let { jsonStringOrNull(it, \"mime\") },\n            mediaName = o.optJSONObject(\"media\")?.let { jsonStringOrNull(it, \"name\") },\n            mediaSize = o.optJSONObject(\"media\")?.optLong(\"size\")?.takeIf { it > 0 },\n            encrypted = o.optBoolean(\"encrypted\", false) || o.optString(\"body\").startsWith(\"zce1:\"),\n            cryptoVersion = o.optInt(\"cryptoVersion\", if (o.optString(\"body\").startsWith(\"zce1:\")) 1 else 0),\n            reactions = parseReactions(o),\n        )\n""",
)
replace_once(
    "android/app/src/main/java/com/zoolchat/app/sync/MessageSyncRepository.kt",
    """private fun CachedMessageEntity.toRemote(local: LocalStorageCrypto) = RemoteMessage(id, chatId, senderId, type, local.reveal(body).orEmpty(), createdAt, editedAt, deleted, status, clientId, mediaUrl, mediaMime, local.reveal(mediaName), mediaSize, encrypted, cryptoVersion, decryptionFailed)\nfun RemoteMessage.toEntity(local: LocalStorageCrypto) = CachedMessageEntity(id, clientId, chatId, senderId, type, local.protect(body).orEmpty(), createdAt, status, editedAt, deleted, mediaUrl, mediaMime, local.protect(mediaName), mediaSize, encrypted, cryptoVersion, decryptionFailed)\n""",
    """private fun reactionsFromJson(raw: String): List<RemoteReaction> = runCatching {\n    val arr = JSONArray(raw)\n    (0 until arr.length()).mapNotNull { i ->\n        val o = arr.optJSONObject(i) ?: return@mapNotNull null\n        val emoji = o.optString(\"emoji\").takeIf { it.isNotBlank() } ?: return@mapNotNull null\n        RemoteReaction(emoji, o.optInt(\"count\", 0).coerceAtLeast(0), o.optBoolean(\"reactedByMe\", false))\n    }\n}.getOrDefault(emptyList())\nprivate fun reactionsToJson(items: List<RemoteReaction>): String = JSONArray().apply {\n    items.forEach { r -> put(JSONObject().put(\"emoji\", r.emoji).put(\"count\", r.count).put(\"reactedByMe\", r.reactedByMe)) }\n}.toString()\n\nprivate fun CachedMessageEntity.toRemote(local: LocalStorageCrypto) = RemoteMessage(id, chatId, senderId, type, local.reveal(body).orEmpty(), createdAt, editedAt, deleted, status, clientId, mediaUrl, mediaMime, local.reveal(mediaName), mediaSize, encrypted, cryptoVersion, decryptionFailed, reactionsFromJson(reactionsJson))\nfun RemoteMessage.toEntity(local: LocalStorageCrypto) = CachedMessageEntity(id, clientId, chatId, senderId, type, local.protect(body).orEmpty(), createdAt, status, editedAt, deleted, mediaUrl, mediaMime, local.protect(mediaName), mediaSize, encrypted, cryptoVersion, decryptionFailed, reactionsToJson(reactions))\n""",
)

# ---------------------------------------------------------------------------
# ViewModel: in-process realtime + FCM for background (no persistent FGS card),
# and wire message edit/delete/reactions through repository.
# ---------------------------------------------------------------------------
replace_once(
    "android/app/src/main/java/com/zoolchat/app/state/ZoolAppViewModel.kt",
    """import com.zoolchat.app.realtime.RealtimeForegroundService\n""",
    """import com.zoolchat.app.realtime.RealtimeClient\n""",
)
replace_once(
    "android/app/src/main/java/com/zoolchat/app/state/ZoolAppViewModel.kt",
    """    private val messageCollectors = mutableMapOf<String, Job>()\n""",
    """    private val messageCollectors = mutableMapOf<String, Job>()\n    private var realtimeClient: RealtimeClient? = null\n""",
)
replace_once(
    "android/app/src/main/java/com/zoolchat/app/state/ZoolAppViewModel.kt",
    """                RealtimeForegroundService.start(getApplication())\n                sync.refreshChats()\n""",
    """                startRealtime()\n                sync.refreshChats()\n""",
)
replace_once(
    "android/app/src/main/java/com/zoolchat/app/state/ZoolAppViewModel.kt",
    """    fun updateProfileDisplayName(displayName: String) {\n""",
    """    private fun startRealtime() {\n        if (realtimeClient != null) return\n        realtimeClient = RealtimeClient(api.http, auth, session) { event -> RealtimeEventBus.emit(event) }.also {\n            it.connect(api.baseUrl)\n        }\n    }\n\n    fun updateProfileDisplayName(displayName: String) {\n""",
)
replace_once(
    "android/app/src/main/java/com/zoolchat/app/state/ZoolAppViewModel.kt",
    """    fun editMessage(chatId: String, messageId: String, body: String) {\n        viewModelScope.launch {\n            runCatching { sync.editMessage(chatId, messageId, body) }.onFailure { _error.value = it.message }\n        }\n    }\n\n    fun deleteMessage(chatId: String, messageId: String) {\n        viewModelScope.launch {\n            runCatching { api.deleteMessage(chatId, messageId); sync.onDeletedMessage(messageId) }\n                .onFailure { _error.value = it.message }\n        }\n    }\n""",
    """    fun editMessage(chatId: String, messageId: String, body: String) {\n        viewModelScope.launch {\n            runCatching { sync.editMessage(chatId, messageId, body); sync.refreshChats() }.onFailure { _error.value = it.message }\n        }\n    }\n\n    fun deleteMessage(chatId: String, messageId: String) {\n        viewModelScope.launch {\n            runCatching { api.deleteMessage(chatId, messageId); sync.onDeletedMessage(messageId); sync.refreshChats() }\n                .onFailure { _error.value = it.message }\n        }\n    }\n\n    fun reactMessage(chatId: String, messageId: String, emoji: String?) {\n        viewModelScope.launch {\n            runCatching { sync.setReaction(chatId, messageId, emoji) }.onFailure { _error.value = it.message }\n        }\n    }\n""",
)
replace_once(
    "android/app/src/main/java/com/zoolchat/app/state/ZoolAppViewModel.kt",
    """            \"message.deleted\" -> {\n                val d = event.getJSONObject(\"data\")\n                viewModelScope.launch { sync.onDeletedMessage(d.getString(\"messageId\")); sync.refreshChats() }\n            }\n            \"receipt.update\" -> {\n""",
    """            \"message.deleted\" -> {\n                val d = event.getJSONObject(\"data\")\n                viewModelScope.launch { sync.onDeletedMessage(d.getString(\"messageId\")); sync.refreshChats() }\n            }\n            \"message.reaction\" -> {\n                val d = event.getJSONObject(\"data\")\n                viewModelScope.launch { sync.applyReactionSnapshot(d.getString(\"messageId\"), d.optJSONArray(\"reactions\") ?: org.json.JSONArray()) }\n            }\n            \"receipt.update\" -> {\n""",
)
replace_once(
    "android/app/src/main/java/com/zoolchat/app/state/ZoolAppViewModel.kt",
    """        RealtimeForegroundService.stop(getApplication())\n        messageCollectors.values.forEach { it.cancel() }\n""",
    """        realtimeClient?.close()\n        realtimeClient = null\n        messageCollectors.values.forEach { it.cancel() }\n""",
)
replace_once(
    "android/app/src/main/java/com/zoolchat/app/state/ZoolAppViewModel.kt",
    """    override fun onCleared() {\n        messageCollectors.values.forEach { it.cancel() }\n        super.onCleared()\n    }\n""",
    """    override fun onCleared() {\n        realtimeClient?.close()\n        realtimeClient = null\n        messageCollectors.values.forEach { it.cancel() }\n        super.onCleared()\n    }\n""",
)

# ---------------------------------------------------------------------------
# Manifest: foreground call service remains; persistent messaging/signalling
# foreground service is removed because Android requires its notification.
# FCM is the background delivery mechanism.
# ---------------------------------------------------------------------------
replace_once(
    "android/app/src/main/AndroidManifest.xml",
    """    <uses-permission android:name=\"android.permission.FOREGROUND_SERVICE_SPECIAL_USE\" />\n""",
    """,
)
regex_once(
    "android/app/src/main/AndroidManifest.xml",
    r"\n\s*<service\n\s*android:name=\"\.realtime\.RealtimeForegroundService\".*?</service>\n",
    "\n",
)

# ---------------------------------------------------------------------------
# Chats list: correct settings icon size and defensive preview sanitation.
# ---------------------------------------------------------------------------
replace_once(
    "android/app/src/main/java/com/zoolchat/app/ui/screens/ProductionChatsScreen.kt",
    """import androidx.compose.material.icons.rounded.Search\n""",
    """import androidx.compose.material.icons.rounded.Search\nimport androidx.compose.material.icons.rounded.Settings\n""",
)
replace_once(
    "android/app/src/main/java/com/zoolchat/app/ui/screens/ProductionChatsScreen.kt",
    """            item{Row(Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically){Text(\"ZoolChat\",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Black,color=textColor,modifier=Modifier.weight(1f));TextButton(onClick=onSettings){Text(\"⚙\",color=textColor)}}}\n""",
    """            item{Row(Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically){Text(\"ZoolChat\",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Black,color=textColor,modifier=Modifier.weight(1f));IconButton(onClick=onSettings,modifier=Modifier.size(48.dp)){Icon(Icons.Rounded.Settings,t(\"الإعدادات\"),tint=textColor,modifier=Modifier.size(28.dp))}}}\n""",
)
replace_once(
    "android/app/src/main/java/com/zoolchat/app/ui/screens/ProductionChatsScreen.kt",
    """            items(filtered,key={it.id}){chat->Row(Modifier.fillMaxWidth().clickable{onOpenChat(chat)}.padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically){Avatar(chat.title,50.dp);Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Row{Text(chat.title,color=textColor,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis);Text(formatTime(chat.lastMessageAt),color=secondaryText,style=MaterialTheme.typography.labelSmall)};Spacer(Modifier.height(3.dp));Row(verticalAlignment=Alignment.CenterVertically){Text(chat.lastMessage?:t(\"ابدأ المحادثة\"),color=secondaryText,modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis);if(chat.unread>0)Surface(shape=CircleShape,color=ZoolGreen){Text(chat.unread.toString(),color=MaterialTheme.colorScheme.onPrimary,modifier=Modifier.padding(horizontal=7.dp,vertical=2.dp),style=MaterialTheme.typography.labelSmall)}}}};HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant,modifier=Modifier.padding(start=78.dp))}\n""",
    """            items(filtered,key={it.id}){chat->val safePreview=chat.lastMessage?.takeIf{it.isNotBlank()&&!it.equals(\"null\",true)&&!it.startsWith(\"zce1:\")&&!it.contains(\"callId\\\"\")&&!it.contains(\"رسالة مشفرة\")};Row(Modifier.fillMaxWidth().clickable{onOpenChat(chat)}.padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically){Avatar(chat.title,50.dp);Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Row{Text(chat.title,color=textColor,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis);Text(formatTime(chat.lastMessageAt),color=secondaryText,style=MaterialTheme.typography.labelSmall)};Spacer(Modifier.height(3.dp));Row(verticalAlignment=Alignment.CenterVertically){Text(safePreview?:t(\"ابدأ المحادثة\"),color=secondaryText,modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis);if(chat.unread>0)Surface(shape=CircleShape,color=ZoolGreen){Text(chat.unread.toString(),color=MaterialTheme.colorScheme.onPrimary,modifier=Modifier.padding(horizontal=7.dp,vertical=2.dp),style=MaterialTheme.typography.labelSmall)}}}};HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant,modifier=Modifier.padding(start=78.dp))}\n""",
)

# ---------------------------------------------------------------------------
# Chat UI: WhatsApp-like long-press action sheet, one reaction per user,
# edit only when actually edited, 15-minute edit window, two-day delete window.
# ---------------------------------------------------------------------------
replace_once(
    "android/app/src/main/java/com/zoolchat/app/ui/screens/ProductionChatScreen.kt",
    """import androidx.compose.foundation.clickable\n""",
    """import androidx.compose.foundation.clickable\nimport androidx.compose.foundation.combinedClickable\n""",
)
replace_once(
    "android/app/src/main/java/com/zoolchat/app/ui/screens/ProductionChatScreen.kt",
    """import kotlinx.coroutines.launch\n""",
    """import kotlinx.coroutines.launch\nimport java.time.Duration\nimport java.time.Instant\n""",
)
replace_once(
    "android/app/src/main/java/com/zoolchat/app/ui/screens/ProductionChatScreen.kt",
    """    onRetry: (String) -> Unit,\n    onMarkRead: () -> Unit,\n""",
    """    onRetry: (String) -> Unit,\n    onEditMessage: (String, String) -> Unit,\n    onDeleteForEveryone: (String) -> Unit,\n    onReact: (String, String?) -> Unit,\n    onMarkRead: () -> Unit,\n""",
)
replace_once(
    "android/app/src/main/java/com/zoolchat/app/ui/screens/ProductionChatScreen.kt",
    """    var typingSent by remember { mutableStateOf(false) }\n""",
    """    var typingSent by remember { mutableStateOf(false) }\n    var selectedMessage by remember { mutableStateOf<RemoteMessage?>(null) }\n    var editingMessage by remember { mutableStateOf<RemoteMessage?>(null) }\n    var deleteCandidate by remember { mutableStateOf<RemoteMessage?>(null) }\n    var canAdminDelete by remember(chatId) { mutableStateOf(false) }\n""",
)
replace_once(
    "android/app/src/main/java/com/zoolchat/app/ui/screens/ProductionChatScreen.kt",
    """    LaunchedEffect(peerUserId) {\n        if (peerUserId != null) {\n            while (true) { onRefreshPresence(); delay(15_000) }\n        }\n    }\n""",
    """    LaunchedEffect(peerUserId) {\n        if (peerUserId != null) {\n            while (true) { onRefreshPresence(); delay(15_000) }\n        }\n    }\n    LaunchedEffect(isGroup, chatId) {\n        canAdminDelete = if (!isGroup) false else runCatching {\n            api.groupInfo(chatId).optString(\"myRole\") in setOf(\"owner\", \"admin\")\n        }.getOrDefault(false)\n    }\n""",
)
replace_once(
    "android/app/src/main/java/com/zoolchat/app/ui/screens/ProductionChatScreen.kt",
    """    if(showSecurity && !isGroup){\n""",
    """    selectedMessage?.let { selected ->\n        val mine = selected.senderId == myUserId\n        val age = runCatching { Duration.between(Instant.parse(selected.createdAt), Instant.now()) }.getOrDefault(Duration.ofDays(999))\n        val canEdit = mine && selected.type == \"text\" && !selected.deleted && !selected.decryptionFailed && age <= Duration.ofMinutes(15)\n        val canDelete = !selected.deleted && age <= Duration.ofDays(2) && (mine || canAdminDelete)\n        val myReaction = selected.reactions.firstOrNull { it.reactedByMe }?.emoji\n        ModalBottomSheet(onDismissRequest = { selectedMessage = null }) {\n            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {\n                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {\n                    listOf(\"👍\",\"❤️\",\"😂\",\"😮\",\"😢\",\"🙏\").forEach { emoji ->\n                        TextButton(onClick = { onReact(selected.id, if (myReaction == emoji) null else emoji); selectedMessage = null }, contentPadding = PaddingValues(4.dp), modifier = Modifier.size(48.dp)) {\n                            Text(emoji, fontSize = 25.sp)\n                        }\n                    }\n                }\n                HorizontalDivider()\n                if (canEdit) {\n                    ListItem(\n                        headlineContent = { Text(t(\"تعديل\")) },\n                        leadingContent = { Icon(Icons.Rounded.Edit, null) },\n                        modifier = Modifier.clickable { editingMessage = selected; text = selected.body; selectedMessage = null },\n                    )\n                }\n                if (canDelete) {\n                    ListItem(\n                        headlineContent = { Text(t(\"حذف لدى الجميع\"), color = MaterialTheme.colorScheme.error) },\n                        supportingContent = { if (!mine) Text(t(\"متاح لمشرفي المجموعة\")) },\n                        leadingContent = { Icon(Icons.Rounded.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },\n                        modifier = Modifier.clickable { deleteCandidate = selected; selectedMessage = null },\n                    )\n                }\n                if (!canEdit && mine && selected.type == \"text\" && age > Duration.ofMinutes(15)) {\n                    Text(t(\"انتهت مهلة تعديل الرسالة\"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))\n                }\n                Spacer(Modifier.height(12.dp))\n            }\n        }\n    }\n\n    deleteCandidate?.let { candidate ->\n        AlertDialog(\n            onDismissRequest = { deleteCandidate = null },\n            title = { Text(t(\"حذف الرسالة؟\")) },\n            text = { Text(t(\"سيتم استبدال الرسالة لدى جميع المشاركين بعبارة تفيد بأنها حُذفت.\")) },\n            confirmButton = { TextButton(onClick = { onDeleteForEveryone(candidate.id); deleteCandidate = null }) { Text(t(\"حذف لدى الجميع\"), color = MaterialTheme.colorScheme.error) } },\n            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text(t(\"إلغاء\")) } },\n        )\n    }\n\n    if(showSecurity && !isGroup){\n""",
)
replace_once(
    "android/app/src/main/java/com/zoolchat/app/ui/screens/ProductionChatScreen.kt",
    """                    Surface(shape=RoundedCornerShape(14.dp),color=if(mine)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,modifier=Modifier.widthIn(max=310.dp)) {\n""",
    """                    Surface(shape=RoundedCornerShape(14.dp),color=if(mine)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,modifier=Modifier.widthIn(max=310.dp).combinedClickable(onClick = {}, onLongClick = { if (!m.deleted && !m.id.startsWith(\"local:\")) selectedMessage = m })) {\n""",
)
replace_once(
    "android/app/src/main/java/com/zoolchat/app/ui/screens/ProductionChatScreen.kt",
    """                            Row(Modifier.align(Alignment.End),verticalAlignment=Alignment.CenterVertically) {\n""",
    """                            if (m.reactions.isNotEmpty() && !m.deleted) {\n                                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {\n                                    m.reactions.take(5).forEach { reaction ->\n                                        Surface(\n                                            shape = RoundedCornerShape(12.dp),\n                                            color = if (reaction.reactedByMe) MaterialTheme.colorScheme.primary.copy(alpha=.18f) else MaterialTheme.colorScheme.surface,\n                                            modifier = Modifier.clickable { onReact(m.id, if (reaction.reactedByMe) null else reaction.emoji) },\n                                        ) {\n                                            Text(\"${reaction.emoji} ${reaction.count}\", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)\n                                        }\n                                    }\n                                }\n                            }\n                            Row(Modifier.align(Alignment.End),verticalAlignment=Alignment.CenterVertically) {\n""",
)
replace_once(
    "android/app/src/main/java/com/zoolchat/app/ui/screens/ProductionChatScreen.kt",
    """        if(recording) Text(t(\"● جاري تسجيل رسالة صوتية — اضغط زر الميكروفون للإرسال\"),color=Color.White,modifier=Modifier.fillMaxWidth().background(Color(0xFF7A1F1F)).padding(8.dp),style=MaterialTheme.typography.bodySmall)\n        Row(Modifier.fillMaxWidth().padding(7.dp).navigationBarsPadding(),verticalAlignment=Alignment.Bottom) {\n""",
    """        if(recording) Text(t(\"● جاري تسجيل رسالة صوتية — اضغط زر الميكروفون للإرسال\"),color=Color.White,modifier=Modifier.fillMaxWidth().background(Color(0xFF7A1F1F)).padding(8.dp),style=MaterialTheme.typography.bodySmall)\n        editingMessage?.let { editing ->\n            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {\n                Icon(Icons.Rounded.Edit, null, modifier = Modifier.size(18.dp))\n                Spacer(Modifier.width(6.dp))\n                Column(Modifier.weight(1f)) { Text(t(\"تعديل الرسالة\"), fontWeight = FontWeight.SemiBold); Text(editing.body, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }\n                IconButton(onClick = { editingMessage = null; text = \"\" }) { Icon(Icons.Rounded.Close, t(\"إلغاء\")) }\n            }\n        }\n        Row(Modifier.fillMaxWidth().padding(7.dp).navigationBarsPadding(),verticalAlignment=Alignment.Bottom) {\n""",
)
replace_once(
    "android/app/src/main/java/com/zoolchat/app/ui/screens/ProductionChatScreen.kt",
    """                    if(text.isNotBlank()){onSend(text);text=\"\";if(typingSent){onTypingChanged(false);typingSent=false}}\n""",
    """                    if(text.isNotBlank()){val clean=text.trim();val editing=editingMessage;if(editing!=null){onEditMessage(editing.id,clean);editingMessage=null}else onSend(clean);text=\"\";if(typingSent){onTypingChanged(false);typingSent=false}}\n""",
)

# ---------------------------------------------------------------------------
# App navigation: automatic E2EE backup/restore uses the password already typed
# at login. Remove the broken second password dialog entirely and wire new chat
# actions.
# ---------------------------------------------------------------------------
replace_once(
    "android/app/src/main/java/com/zoolchat/app/ZoolChatApp.kt",
    """import androidx.compose.ui.text.input.PasswordVisualTransformation\n""",
    """,
)
replace_once(
    "android/app/src/main/java/com/zoolchat/app/ZoolChatApp.kt",
    """        var cryptoRecoveryWarning by remember { mutableStateOf(false) }\n""",
    """,
)
replace_once(
    "android/app/src/main/java/com/zoolchat/app/ZoolChatApp.kt",
    """                        val recovered=runCatching { vm.e2ee.prepareIdentityAfterPasswordAuth(password) }.getOrDefault(false)\n                        cryptoRecoveryWarning=!recovered\n                        authenticated = vm.isAuthenticated\n""",
    """                        runCatching { vm.e2ee.prepareIdentityAfterPasswordAuth(password) }\n                        authenticated = vm.isAuthenticated\n""",
)
regex_once(
    "android/app/src/main/java/com/zoolchat/app/ZoolChatApp.kt",
    r"\n\s*if\(cryptoRecoveryWarning\)\{.*?\n\s*\}\n\s*\}\n\}\n\n@Composable\nprivate fun ProductionNav",
    "\n    }\n}\n\n@Composable\nprivate fun ProductionNav",
)
replace_once(
    "android/app/src/main/java/com/zoolchat/app/ZoolChatApp.kt",
    """    var showBackupPrompt by remember { mutableStateOf(false) }\n    var backupPromptChecked by remember { mutableStateOf(false) }\n    var backupPassword by remember { mutableStateOf(\"\") }\n    var backupBusy by remember { mutableStateOf(false) }\n    var backupError by remember { mutableStateOf<String?>(null) }\n""",
    """,
)
regex_once(
    "android/app/src/main/java/com/zoolchat/app/ZoolChatApp.kt",
    r"\n\s*LaunchedEffect\(me\?\.id\)\{.*?\n\s*\}\n\s*LaunchedEffect\(incomingCall\)",
    "\n    LaunchedEffect(incomingCall)",
)
regex_once(
    "android/app/src/main/java/com/zoolchat/app/ZoolChatApp.kt",
    r"\n\s*if\(showBackupPrompt\)\{.*?\n\s*\}\n\n\s*memberId\?\.let",
    "\n\n    memberId?.let",
)
replace_once(
    "android/app/src/main/java/com/zoolchat/app/ZoolChatApp.kt",
    """                    onRetry={vm.retryMessage(it)},\n                    onMarkRead={vm.markRead(id)},\n""",
    """                    onRetry={vm.retryMessage(it)},\n                    onEditMessage={messageId,body->vm.editMessage(id,messageId,body)},\n                    onDeleteForEveryone={messageId->vm.deleteMessage(id,messageId)},\n                    onReact={messageId,emoji->vm.reactMessage(id,messageId,emoji)},\n                    onMarkRead={vm.markRead(id)},\n""",
)

# ---------------------------------------------------------------------------
# E2EE manager: automatic encrypted identity backup/restore after the existing
# login password is available; retry transient upload failures. The password is
# not persisted and there is no secondary prompt.
# ---------------------------------------------------------------------------
replace_once(
    "android/app/src/main/java/com/zoolchat/app/crypto/E2eeManager.kt",
    """import kotlinx.coroutines.sync.Mutex\n""",
    """import kotlinx.coroutines.delay\nimport kotlinx.coroutines.sync.Mutex\n""",
)
replace_once(
    "android/app/src/main/java/com/zoolchat/app/crypto/E2eeManager.kt",
    """    suspend fun backupCurrentIdentityWithVerifiedPassword(password:String) {\n        api.post(\"/api/me/password/verify\",JSONObject().put(\"password\",password))\n        val kp=loadIdentityOrNull() ?: error(\"local_e2ee_identity_missing\")\n        api.put(\"/api/e2ee/backup\",encryptedBackupBody(kp,password))\n    }\n""",
    """    private suspend fun uploadBackupWithRetry(kp: KeyPair, password: String): Boolean {\n        repeat(3) { attempt ->\n            val ok = runCatching { api.put(\"/api/e2ee/backup\", encryptedBackupBody(kp, password)); true }.getOrDefault(false)\n            if (ok) return true\n            delay(250L * (attempt + 1))\n        }\n        return false\n    }\n\n    suspend fun backupCurrentIdentityWithVerifiedPassword(password:String) {\n        api.post(\"/api/me/password/verify\",JSONObject().put(\"password\",password))\n        val kp=loadIdentityOrNull() ?: error(\"local_e2ee_identity_missing\")\n        check(uploadBackupWithRetry(kp,password)){\"e2ee_backup_upload_failed\"}\n    }\n""",
)
replace_once(
    "android/app/src/main/java/com/zoolchat/app/crypto/E2eeManager.kt",
    """        loadIdentityOrNull()?.let { local ->\n            return runCatching { api.put(\"/api/e2ee/backup\",encryptedBackupBody(local,password));true }.getOrDefault(false)\n        }\n""",
    """        loadIdentityOrNull()?.let { local ->\n            return uploadBackupWithRetry(local,password)\n        }\n""",
)
replace_once(
    "android/app/src/main/java/com/zoolchat/app/crypto/E2eeManager.kt",
    """        val fresh=generateIdentity()\n        return runCatching { api.put(\"/api/e2ee/backup\",encryptedBackupBody(fresh,password));true }.getOrDefault(false)\n""",
    """        val fresh=generateIdentity()\n        return uploadBackupWithRetry(fresh,password)\n""",
)

# ---------------------------------------------------------------------------
# Server: E2EE backup endpoints were missing despite migration 015. Add them,
# add reactions, and enforce WhatsApp-like edit/delete time windows server-side.
# ---------------------------------------------------------------------------
server = "server/src/index.ts"
replace_once(
    server,
    """function messageJson(r: any) {\n  return {\n""",
    """async function messageReactionMap(messageIds: string[], viewerId: string) {\n  const out = new Map<string, Array<{emoji:string,count:number,reactedByMe:boolean}>>();\n  if (!messageIds.length) return out;\n  const r = await pool.query(`SELECT message_id,emoji,count(*)::int count,bool_or(user_id=$2) reacted_by_me FROM message_reactions WHERE message_id=ANY($1::uuid[]) GROUP BY message_id,emoji ORDER BY message_id,emoji`, [messageIds, viewerId]);\n  for (const row of r.rows) {\n    const id = String(row.message_id); const list = out.get(id) ?? [];\n    list.push({emoji:String(row.emoji),count:Number(row.count),reactedByMe:Boolean(row.reacted_by_me)}); out.set(id,list);\n  }\n  return out;\n}\n\nfunction messageJson(r: any) {\n  return {\n""",
)
replace_once(
    server,
    """    cryptoVersion: Number(r.crypto_version ?? 0),\n    media: mediaJson(r),\n""",
    """    cryptoVersion: Number(r.crypto_version ?? 0),\n    reactions: Array.isArray(r.reactions) ? r.reactions : [],\n    media: mediaJson(r),\n""",
)
replace_once(
    server,
    """app.get('/api/users/:id/e2ee-key', requireAuth, async (req,res)=>{\n  const id=z.string().uuid().parse(req.params.id);\n  if(id===req.zoolUser!.id) return res.status(400).json({error:'self_key_not_required'});\n  const r=await pool.query(`SELECT u.id,u.display_name,k.algorithm,k.public_key_b64,k.fingerprint_sha256,k.updated_at\n    FROM users u LEFT JOIN user_crypto_keys k ON k.user_id=u.id WHERE u.id=$1`,[id]);\n  if(!r.rowCount) return res.status(404).json({error:'user_not_found'});\n  if(!r.rows[0].public_key_b64) return res.status(409).json({error:'peer_e2ee_key_missing'});\n  const x=r.rows[0];res.json({userId:x.id,displayName:x.display_name,algorithm:x.algorithm,publicKeyB64:x.public_key_b64,fingerprintSha256:x.fingerprint_sha256,updatedAt:new Date(x.updated_at).toISOString()});\n});\n""",
    """app.get('/api/users/:id/e2ee-key', requireAuth, async (req,res)=>{\n  const id=z.string().uuid().parse(req.params.id);\n  if(id===req.zoolUser!.id) return res.status(400).json({error:'self_key_not_required'});\n  const r=await pool.query(`SELECT u.id,u.display_name,k.algorithm,k.public_key_b64,k.fingerprint_sha256,k.updated_at\n    FROM users u LEFT JOIN user_crypto_keys k ON k.user_id=u.id WHERE u.id=$1`,[id]);\n  if(!r.rowCount) return res.status(404).json({error:'user_not_found'});\n  if(!r.rows[0].public_key_b64) return res.status(409).json({error:'peer_e2ee_key_missing'});\n  const x=r.rows[0];res.json({userId:x.id,displayName:x.display_name,algorithm:x.algorithm,publicKeyB64:x.public_key_b64,fingerprintSha256:x.fingerprint_sha256,updatedAt:new Date(x.updated_at).toISOString()});\n});\n\napp.get('/api/e2ee/self-key', requireAuth, async (req,res)=>{\n  const r=await pool.query(`SELECT algorithm,public_key_b64,fingerprint_sha256,updated_at FROM user_crypto_keys WHERE user_id=$1`,[req.zoolUser!.id]);\n  if(!r.rowCount)return res.status(404).json({error:'e2ee_key_not_found'});\n  const x=r.rows[0];res.json({algorithm:x.algorithm,publicKeyB64:x.public_key_b64,fingerprintSha256:x.fingerprint_sha256,updatedAt:new Date(x.updated_at).toISOString()});\n});\napp.get('/api/e2ee/backup', requireAuth, async (req,res)=>{\n  const r=await pool.query(`SELECT * FROM e2ee_identity_backups WHERE user_id=$1`,[req.zoolUser!.id]);\n  if(!r.rowCount)return res.status(404).json({error:'e2ee_backup_not_found'});\n  const x=r.rows[0];res.json({backup:{version:Number(x.version),publicKeyB64:x.public_key_b64,fingerprintSha256:x.fingerprint_sha256,saltB64:x.salt_b64,ivB64:x.iv_b64,ciphertextB64:x.ciphertext_b64,updatedAt:new Date(x.updated_at).toISOString()}});\n});\napp.put('/api/e2ee/backup', requireAuth, async (req,res)=>{\n  const b=z.object({version:z.literal(1),publicKeyB64:z.string().min(80).max(4096),fingerprintSha256:z.string().min(32).max(200),saltB64:z.string().min(16).max(256),ivB64:z.string().min(12).max(128),ciphertextB64:z.string().min(64).max(8192)}).parse(req.body);\n  await tx(async c=>{\n    await c.query(`INSERT INTO user_crypto_keys(user_id,algorithm,public_key_b64,fingerprint_sha256) VALUES($1,'P256-ECDH',$2,$3) ON CONFLICT(user_id) DO UPDATE SET public_key_b64=EXCLUDED.public_key_b64,fingerprint_sha256=EXCLUDED.fingerprint_sha256,updated_at=now()`,[req.zoolUser!.id,b.publicKeyB64,b.fingerprintSha256]);\n    await c.query(`INSERT INTO e2ee_identity_backups(user_id,version,public_key_b64,fingerprint_sha256,salt_b64,iv_b64,ciphertext_b64) VALUES($1,$2,$3,$4,$5,$6,$7) ON CONFLICT(user_id) DO UPDATE SET version=EXCLUDED.version,public_key_b64=EXCLUDED.public_key_b64,fingerprint_sha256=EXCLUDED.fingerprint_sha256,salt_b64=EXCLUDED.salt_b64,iv_b64=EXCLUDED.iv_b64,ciphertext_b64=EXCLUDED.ciphertext_b64,updated_at=now()`,[req.zoolUser!.id,b.version,b.publicKeyB64,b.fingerprintSha256,b.saltB64,b.ivB64,b.ciphertextB64]);\n  });\n  res.json({ok:true});\n});\napp.post('/api/me/password/verify', requireAuth, async (req,res)=>{\n  const password=z.object({password:z.string().min(8).max(128)}).parse(req.body).password;\n  const r=await pool.query(`SELECT password_hash FROM user_credentials WHERE user_id=$1`,[req.zoolUser!.id]);\n  if(!r.rowCount||!(await verifyPassword(password,r.rows[0].password_hash)))return res.status(401).json({error:'invalid_credentials'});\n  res.json({ok:true});\n});\n""",
)
replace_once(
    server,
    """      (SELECT CASE WHEN m.encrypted THEN '🔒 رسالة مشفرة' WHEN m.type='text' THEN m.body WHEN m.type='call' THEN '📞 مكالمة' ELSE '📎 '||COALESCE(ma.original_name,m.type) END FROM messages m LEFT JOIN media_assets ma ON ma.id=m.media_id WHERE m.chat_id=c.id AND m.deleted_at IS NULL ORDER BY m.created_at DESC LIMIT 1) last_message,\n""",
    """      (SELECT CASE WHEN m.encrypted THEN NULL WHEN m.type='text' THEN m.body WHEN m.type='call' THEN '📞 مكالمة' WHEN m.type='image' THEN '📷 صورة' WHEN m.type='video' THEN '🎥 فيديو' WHEN m.type='audio' THEN '🎤 رسالة صوتية' WHEN m.type='sticker' THEN '🖼️ ملصق' WHEN m.type='document' THEN '📄 '||COALESCE(ma.original_name,'مستند') ELSE '📎 مرفق' END FROM messages m LEFT JOIN media_assets ma ON ma.id=m.media_id WHERE m.chat_id=c.id AND m.deleted_at IS NULL ORDER BY m.created_at DESC LIMIT 1) last_message,\n""",
)
replace_once(
    server,
    """  const messages = pageRows.map(messageJson);\n""",
    """  const reactionMap = await messageReactionMap(pageRows.map(x=>String(x.id)), uid);\n  const messages = pageRows.map(row=>messageJson({...row,reactions:reactionMap.get(String(row.id))??[]}));\n""",
)
replace_once(
    server,
    """type: z.enum(['text','image','video','audio','document']).default('text')""",
    """type: z.enum(['text','image','video','audio','document','sticker']).default('text')""",
)
regex_once(
    server,
    r"app\.put\('/api/chats/:id/messages/:messageId', requireAuth, async \(req,res\)=>\{.*?\n\}\);\napp\.delete\('/api/chats/:id/messages/:messageId', requireAuth, async \(req,res\)=>\{.*?\n\}\);",
    """app.put('/api/chats/:id/messages/:messageId', requireAuth, async (req,res)=>{\n  const chatId=String(req.params.id),uid=req.zoolUser!.id;if(!(await membership(chatId,uid)))return res.status(403).json({error:'not_member'});\n  const b=z.object({body:z.string().min(1).max(20000),encrypted:z.boolean().default(false),cryptoVersion:z.number().int().min(0).max(10).default(0)}).parse(req.body);\n  const current=await pool.query(`SELECT * FROM messages WHERE id=$1 AND chat_id=$2 AND sender_id=$3 AND type='text' AND deleted_at IS NULL`,[req.params.messageId,chatId,uid]);\n  if(!current.rowCount)return res.status(404).json({error:'message_not_found'});\n  if(Date.now()-new Date(current.rows[0].created_at).getTime()>15*60*1000)return res.status(409).json({error:'edit_window_expired'});\n  const r=await pool.query(`UPDATE messages SET body=$4,encrypted=$5,crypto_version=$6,edited_at=now() WHERE id=$1 AND chat_id=$2 AND sender_id=$3 RETURNING *`,[req.params.messageId,chatId,uid,b.body,b.encrypted,b.cryptoVersion]);\n  const reactions=await messageReactionMap([String(r.rows[0].id)],uid);\n  const payload=messageJson({...r.rows[0],status:'sent',reactions:reactions.get(String(r.rows[0].id))??[]});\n  const peers=await pool.query(`SELECT user_id FROM chat_members WHERE chat_id=$1 AND user_id<>$2`,[chatId,uid]);for(const p of peers.rows)emitTo(p.user_id,{type:'message.edited',data:payload});\n  const editTokens=await fcmTokens(peers.rows.map(x=>x.user_id)); await sendData(editTokens,{type:'chat_sync',chatId},false);\n  res.json({message:payload});\n});\napp.delete('/api/chats/:id/messages/:messageId', requireAuth, async (req,res)=>{\n  const chatId=String(req.params.id),uid=req.zoolUser!.id;if(!(await membership(chatId,uid)))return res.status(403).json({error:'not_member'});\n  const current=await pool.query(`SELECT m.*,c.type,cm.role FROM messages m JOIN chats c ON c.id=m.chat_id JOIN chat_members cm ON cm.chat_id=m.chat_id AND cm.user_id=$3 WHERE m.id=$1 AND m.chat_id=$2 AND m.deleted_at IS NULL`,[req.params.messageId,chatId,uid]);\n  if(!current.rowCount)return res.status(404).json({error:'message_not_found'});\n  const m=current.rows[0];\n  const adminCanDelete=m.type==='group'&&['owner','admin'].includes(String(m.role));\n  if(m.sender_id!==uid&&!adminCanDelete)return res.status(403).json({error:'delete_not_allowed'});\n  if(Date.now()-new Date(m.created_at).getTime()>2*24*60*60*1000)return res.status(409).json({error:'delete_window_expired'});\n  const r=await pool.query(`UPDATE messages SET body='',deleted_at=now() WHERE id=$1 AND chat_id=$2 RETURNING *`,[req.params.messageId,chatId]);\n  const peers=await pool.query(`SELECT user_id FROM chat_members WHERE chat_id=$1 AND user_id<>$2`,[chatId,uid]);for(const p of peers.rows)emitTo(p.user_id,{type:'message.deleted',data:{chatId,messageId:req.params.messageId,deletedBy:uid,admin:m.sender_id!==uid}});\n  const deleteTokens=await fcmTokens(peers.rows.map(x=>x.user_id)); await sendData(deleteTokens,{type:'chat_sync',chatId},false);\n  res.json({ok:true,message:messageJson({...r.rows[0],status:'sent'})});\n});\n\napp.put('/api/chats/:id/messages/:messageId/reaction', requireAuth, async (req,res)=>{\n  const chatId=String(req.params.id),uid=req.zoolUser!.id;if(!(await membership(chatId,uid)))return res.status(403).json({error:'not_member'});\n  const emoji=z.object({emoji:z.string().trim().min(1).max(16)}).parse(req.body).emoji;\n  const exists=await pool.query(`SELECT 1 FROM messages WHERE id=$1 AND chat_id=$2 AND deleted_at IS NULL`,[req.params.messageId,chatId]);if(!exists.rowCount)return res.status(404).json({error:'message_not_found'});\n  await pool.query(`INSERT INTO message_reactions(message_id,user_id,emoji) VALUES($1,$2,$3) ON CONFLICT(message_id,user_id) DO UPDATE SET emoji=EXCLUDED.emoji,created_at=now()`,[req.params.messageId,uid,emoji]);\n  const reactions=(await messageReactionMap([String(req.params.messageId)],uid)).get(String(req.params.messageId))??[];\n  const members=await pool.query(`SELECT user_id FROM chat_members WHERE chat_id=$1`,[chatId]);for(const p of members.rows)emitTo(p.user_id,{type:'message.reaction',data:{chatId,messageId:req.params.messageId,reactions}});\n  const tokens=await fcmTokens(members.rows.filter(x=>x.user_id!==uid).map(x=>x.user_id));await sendData(tokens,{type:'chat_sync',chatId},false);\n  res.json({ok:true,reactions});\n});\napp.delete('/api/chats/:id/messages/:messageId/reaction', requireAuth, async (req,res)=>{\n  const chatId=String(req.params.id),uid=req.zoolUser!.id;if(!(await membership(chatId,uid)))return res.status(403).json({error:'not_member'});\n  await pool.query(`DELETE FROM message_reactions WHERE message_id=$1 AND user_id=$2`,[req.params.messageId,uid]);\n  const reactions=(await messageReactionMap([String(req.params.messageId)],uid)).get(String(req.params.messageId))??[];\n  const members=await pool.query(`SELECT user_id FROM chat_members WHERE chat_id=$1`,[chatId]);for(const p of members.rows)emitTo(p.user_id,{type:'message.reaction',data:{chatId,messageId:req.params.messageId,reactions}});\n  const tokens=await fcmTokens(members.rows.filter(x=>x.user_id!==uid).map(x=>x.user_id));await sendData(tokens,{type:'chat_sync',chatId},false);\n  res.json({ok:true,reactions});\n});""",
)

# Migration for one reaction per user per message, matching WhatsApp's current
# semantics while still aggregating counts for display.
migration = ROOT / "server/migrations/016_v032_message_reactions.sql"
if not migration.exists():
    migration.write_text(textwrap.dedent("""\
        CREATE TABLE IF NOT EXISTS message_reactions (\n          message_id uuid NOT NULL REFERENCES messages(id) ON DELETE CASCADE,\n          user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,\n          emoji varchar(16) NOT NULL CHECK (length(trim(emoji)) BETWEEN 1 AND 16),\n          created_at timestamptz NOT NULL DEFAULT now(),\n          PRIMARY KEY(message_id,user_id)\n        );\n        CREATE INDEX IF NOT EXISTS message_reactions_message_idx ON message_reactions(message_id);\n    """), encoding="utf-8")

# ---------------------------------------------------------------------------
# Integration test for E2EE backup endpoints, reactions, edit, and deletion.
# ---------------------------------------------------------------------------
test_path = ROOT / "server/tests/message-interactions.mjs"
if not test_path.exists():
    test_path.write_text(textwrap.dedent("""\
        import assert from 'node:assert/strict';\n        import { randomUUID, randomInt } from 'node:crypto';\n        import { pool } from '../dist/db.js';\n\n        assert.equal(new URL(process.env.DATABASE_URL).pathname,'/zoolchat_test');\n        const base='http://127.0.0.1:8080';\n        const post=(path,body,token)=>fetch(base+path,{method:'POST',headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{})},body:JSON.stringify(body)});\n        const put=(path,body,token)=>fetch(base+path,{method:'PUT',headers:{'Content-Type':'application/json',Authorization:`Bearer ${token}`},body:JSON.stringify(body)});\n        const del=(path,token)=>fetch(base+path,{method:'DELETE',headers:{Authorization:`Bearer ${token}`}});\n        const get=(path,token)=>fetch(base+path,{headers:{Authorization:`Bearer ${token}`}});\n        const mk=async(label)=>{\n          const email=`${label}.${randomUUID()}@example.invalid`;\n          const phone=`+1555${String(randomInt(10_000_000,99_999_999))}`;\n          const password=`Zc!${randomUUID()}`;\n          const r=await post('/api/auth/register',{email,phoneE164:phone,password,deviceName:`${label} test`});\n          assert.equal(r.status,201);const j=await r.json();return {id:j.user.id,token:j.accessToken,password};\n        };\n        const users=[];\n        try{\n          const a=await mk('interaction-a'),b=await mk('interaction-b');users.push(a.id,b.id);\n\n          const verify=await post('/api/me/password/verify',{password:a.password},a.token);assert.equal(verify.status,200,'current password verification endpoint must work');\n          const backup={version:1,publicKeyB64:'A'.repeat(120),fingerprintSha256:'ab'.repeat(32),saltB64:'c'.repeat(24),ivB64:'d'.repeat(16),ciphertextB64:'e'.repeat(96)};\n          assert.equal((await put('/api/e2ee/backup',backup,a.token)).status,200,'encrypted identity backup must upload');\n          assert.equal((await get('/api/e2ee/self-key',a.token)).status,200,'self identity metadata must exist after backup upload');\n          const restored=await get('/api/e2ee/backup',a.token);assert.equal(restored.status,200);const rb=(await restored.json()).backup;assert.equal(rb.publicKeyB64,backup.publicKeyB64);assert.equal(rb.ciphertextB64,backup.ciphertextB64);\n\n          const direct=await post('/api/chats/direct',{userId:b.id},a.token);assert.equal(direct.status,200);const chatId=(await direct.json()).chat.id;\n          const sent=await post(`/api/chats/${chatId}/messages`,{clientId:randomUUID(),type:'text',body:'hello interaction'},a.token);assert.equal(sent.status,201);const messageId=(await sent.json()).message.id;\n\n          const reaction=await put(`/api/chats/${chatId}/messages/${messageId}/reaction`,{emoji:'👍'},b.token);assert.equal(reaction.status,200);assert.equal((await reaction.json()).reactions[0].count,1);\n          const page=await get(`/api/chats/${chatId}/messages?limit=10`,b.token);assert.equal(page.status,200);const pm=(await page.json()).messages.find(x=>x.id===messageId);assert.equal(pm.reactions[0].emoji,'👍');assert.equal(pm.reactions[0].reactedByMe,true);\n\n          const edit=await put(`/api/chats/${chatId}/messages/${messageId}`,{body:'edited interaction',encrypted:false,cryptoVersion:0},a.token);assert.equal(edit.status,200);assert.ok((await edit.json()).message.editedAt,'edited message must carry editedAt');\n\n          const deleted=await del(`/api/chats/${chatId}/messages/${messageId}`,a.token);assert.equal(deleted.status,200);assert.equal((await deleted.json()).message.deleted,true);\n          console.log('PASS: automatic E2EE backup endpoints, message reactions, edit and delete-for-everyone lifecycle');\n        }finally{\n          if(users.length)await pool.query('DELETE FROM users WHERE id=ANY($1::uuid[])',[users]);\n          await pool.end();\n        }\n    """), encoding="utf-8")

replace_once(
    ".github/workflows/validate-v032.yml",
    """          node tests/profile-meeting.mjs\n""",
    """          node tests/profile-meeting.mjs\n          node tests/message-interactions.mjs\n""",
)

append_once(
    "docs/ZOOLCHAT_CURRENT_STATE.md",
    "v0.32 user-feedback round 2",
    """
## v0.32 user-feedback round 2
- Removed the broken second password prompt for encrypted-history protection. The password already entered during successful account authentication is used transiently to create/restore an encrypted E2EE identity backup; it is not persisted.
- Added the previously missing `/api/e2ee/backup`, `/api/e2ee/self-key`, and password-verification backend handlers over migration 015 so automatic identity restore can actually work after reinstall for accounts whose identity backup exists.
- Chat-list previews no longer expose ciphertext, `رسالة مشفرة`, raw call JSON, or stringified JSON nulls. Latest call/media events are rendered as user-facing descriptions (incoming/outgoing/missed/declined voice/video call, photo, video, voice note, document, sticker).
- Fixed JSON-null parsing that made `editedAt` become the literal string `null`, which caused `تم التعديل` to appear on messages that were never edited.
- Added long-press message actions inspired by WhatsApp: emoji reaction, edit for the sender within 15 minutes, and delete for everyone within two days. Group owners/admins may delete member messages within the same server-enforced two-day window.
- Added durable one-reaction-per-user storage and aggregated reaction counts with realtime/FCM synchronization.
- Replaced the tiny text settings glyph on the Chats header with the standard Material Settings icon at normal 48dp touch-target / 28dp glyph sizing.
- Removed the persistent realtime foreground-service notification. Normal background message/call delivery now relies on Firebase Cloud Messaging; the WebSocket client stays process-scoped for realtime foreground/in-process updates. The call foreground service remains for active calls where Android requires it.
- Validation now includes migration 016 and message-interaction/E2EE-backup integration coverage in addition to Android assemble/lint and the existing v0.32 test suite.
- Security limitation remains explicit: ciphertext whose original identity private key was already destroyed before any encrypted backup existed cannot be mathematically recovered. This change prevents that loss for future reinstalls once the automatic backup has been created during password authentication, without weakening E2EE by giving the server a plaintext private key.
""",
)

print("PASS: applied ZoolChat v0.32 feedback round 2 patches")
