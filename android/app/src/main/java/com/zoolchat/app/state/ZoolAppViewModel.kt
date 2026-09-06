package com.zoolchat.app.state

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.zoolchat.app.auth.AuthRepository
import com.zoolchat.app.auth.SessionStore
import com.zoolchat.app.contacts.DeviceContactsRepository
import com.zoolchat.app.crypto.E2eeManager
import com.zoolchat.app.data.RemoteCallHistoryItem
import com.zoolchat.app.data.CurrentUser
import com.zoolchat.app.data.RegisteredContact
import com.zoolchat.app.data.RemoteChat
import com.zoolchat.app.data.RemoteMessage
import com.zoolchat.app.data.RemotePresence
import com.zoolchat.app.network.ApiClient
import com.zoolchat.app.realtime.RealtimeClient
import com.zoolchat.app.realtime.RealtimeEventBus
import com.zoolchat.app.realtime.RealtimeForegroundService
import com.zoolchat.app.sync.MessageSyncRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

class ZoolAppViewModel(app: Application) : AndroidViewModel(app) {
    val session = SessionStore(app)
    val auth = AuthRepository(app, session)
    val api = ApiClient(auth, session)
    private val contactsReader = DeviceContactsRepository(app)
    private val sync = MessageSyncRepository(app, api)
    val e2ee = E2eeManager(app, api, session)

    private val _currentUser = MutableStateFlow<CurrentUser?>(null)
    val currentUser: StateFlow<CurrentUser?> = _currentUser
    private val _contacts = MutableStateFlow<List<RegisteredContact>>(emptyList())
    val contacts: StateFlow<List<RegisteredContact>> = _contacts
    private val _directorySearch = MutableStateFlow<List<RegisteredContact>>(emptyList())
    val directorySearch: StateFlow<List<RegisteredContact>> = _directorySearch
    private val _chats = MutableStateFlow<List<RemoteChat>>(emptyList())
    val chats: StateFlow<List<RemoteChat>> = _chats
    private val _callHistory = MutableStateFlow<List<RemoteCallHistoryItem>>(emptyList())
    val callHistory: StateFlow<List<RemoteCallHistoryItem>> = _callHistory
    private val _messages = MutableStateFlow<Map<String, List<RemoteMessage>>>(emptyMap())
    val messages: StateFlow<Map<String, List<RemoteMessage>>> = _messages
    private val _hasMoreMessages = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val hasMoreMessages: StateFlow<Map<String, Boolean>> = _hasMoreMessages
    private val _incomingRealtimeCall = MutableStateFlow<Pair<String, Boolean>?>(null)
    val incomingRealtimeCall: StateFlow<Pair<String, Boolean>?> = _incomingRealtimeCall
    private val _presence = MutableStateFlow<Map<String, RemotePresence>>(emptyMap())
    val presence: StateFlow<Map<String, RemotePresence>> = _presence
    private val _typing = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val typing: StateFlow<Map<String, Boolean>> = _typing
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val messageCollectors = mutableMapOf<String, Job>()

    val isAuthenticated: Boolean get() = auth.signedIn
    val firebaseConfigured: Boolean get() = auth.firebaseConfigured

    init {
        viewModelScope.launch { sync.observeChats().collect { _chats.value = it } }
        viewModelScope.launch { RealtimeEventBus.events.collect { handleRealtime(it) } }
    }

    fun bootstrap(displayName: String = session.localDisplayName.ifBlank { session.devDisplayName.ifBlank { "ZoolChat User" } }) {
        viewModelScope.launch {
            runCatching {
                val obj = api.bootstrap(displayName)
                val user = obj.getJSONObject("user")
                _currentUser.value = CurrentUser(
                    user.getString("id"), user.getString("displayName"),
                    user.optString("username").takeIf { it.isNotBlank() && it != "null" },
                    user.optString("phoneE164").takeIf { it.isNotBlank() },
                    user.optString("photoUrl").takeIf { it.isNotBlank() },
                )
                session.localDisplayName = user.getString("displayName")
                session.currentUserId = user.getString("id")
                runCatching { e2ee.registerLocalKey() }.onFailure { _error.value = "تعذر تسجيل مفتاح التشفير: ${it.message}" }
                RealtimeForegroundService.start(getApplication())
                sync.refreshChats()
                refreshCallHistoryInternal()
                MessageSyncRepository.scheduleSend(getApplication())
                registerFcmIfAvailable()
            }.onFailure { _error.value = it.message }
        }
    }

    fun updateProfileDisplayName(displayName: String) {
        val clean = displayName.trim().take(80)
        if (clean.isBlank()) return
        viewModelScope.launch {
            runCatching {
                val obj = api.bootstrap(clean, _currentUser.value?.username)
                val user = obj.getJSONObject("user")
                _currentUser.value = CurrentUser(
                    user.getString("id"), user.getString("displayName"),
                    user.optString("username").takeIf { it.isNotBlank() && it != "null" },
                    user.optString("phoneE164").takeIf { it.isNotBlank() },
                    user.optString("photoUrl").takeIf { it.isNotBlank() },
                )
                session.localDisplayName = user.getString("displayName")
                session.currentUserId = user.getString("id")
            }.onFailure { _error.value = it.message }
        }
    }

    fun refreshChats() {
        viewModelScope.launch { runCatching { sync.refreshChats() }.onFailure { _error.value = it.message } }
    }

    fun discoverContacts() {
        val app = getApplication<Application>()
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return
        viewModelScope.launch {
            runCatching {
                val local = contactsReader.readAll()
                val byHash = local.associateBy { it.discoveryHash }
                val arr = api.discoverContacts(local.map { it.discoveryHash })
                _contacts.value = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.getJSONObject(i)
                    val hash = o.optString("discoveryHash")
                    val localContact = byHash[hash] ?: return@mapNotNull null
                    RegisteredContact(
                        o.getString("userId"), localContact.e164, o.getString("displayName"),
                        o.optString("username").takeIf { it.isNotBlank() },
                        o.optString("photoUrl").takeIf { it.isNotBlank() },
                        localContact.localName,
                    )
                }
            }.onFailure { _error.value = it.message }
        }
    }

    private suspend fun refreshCallHistoryInternal() {
        val arr = api.callHistory()
        _callHistory.value = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RemoteCallHistoryItem(
                id=o.getString("id"), type=o.optString("type","audio"), status=o.optString("status"),
                participantStatus=o.optString("participantStatus"), initiatedByMe=o.optBoolean("initiatedByMe"),
                peerUserId=o.optString("peerUserId").takeIf{it.isNotBlank()}, peerName=o.optString("peerName","ZoolChat"),
                createdAt=o.getString("createdAt"), answeredAt=o.optString("answeredAt").takeIf{it.isNotBlank()}, endedAt=o.optString("endedAt").takeIf{it.isNotBlank()},
            )
        }
    }

    fun refreshCallHistory() {
        viewModelScope.launch { runCatching { refreshCallHistoryInternal() }.onFailure { _error.value=it.message } }
    }

    fun searchUsers(query: String) {
        val q = query.trim()
        if (q.length < 3) { _directorySearch.value = emptyList(); return }
        viewModelScope.launch {
            runCatching {
                val arr = api.searchUsers(q)
                _directorySearch.value = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    RegisteredContact(
                        userId = o.getString("userId"),
                        phoneE164 = o.optString("phoneE164"),
                        displayName = o.getString("displayName"),
                        username = o.optString("username").takeIf { it.isNotBlank() },
                        photoUrl = o.optString("photoUrl").takeIf { it.isNotBlank() },
                        localName = o.getString("displayName"),
                    )
                }
            }.onFailure { _error.value = it.message }
        }
    }

    fun openDirect(userId: String, onReady: (String) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val c = api.createDirectChat(userId)
                sync.refreshChats()
                onReady(c.getString("id"))
            }.onFailure { _error.value = it.message }
        }
    }

    fun loadMessages(chatId: String) {
        if (messageCollectors[chatId] == null) {
            messageCollectors[chatId] = viewModelScope.launch {
                sync.observeMessages(chatId).collect { list -> _messages.value = _messages.value + (chatId to list) }
            }
        }
        viewModelScope.launch {
            runCatching {
                val result = sync.refreshMessages(chatId)
                _hasMoreMessages.value = _hasMoreMessages.value + (chatId to result.hasMore)
            }.onFailure { _error.value = it.message }
        }
    }

    fun loadOlderMessages(chatId: String) {
        viewModelScope.launch {
            runCatching {
                val result = sync.loadOlder(chatId)
                _hasMoreMessages.value = _hasMoreMessages.value + (chatId to result.hasMore)
            }.onFailure { _error.value = it.message }
        }
    }

    fun refreshPresence(userId: String?) {
        if (userId.isNullOrBlank()) return
        viewModelScope.launch {
            runCatching {
                val o = api.presence(userId)
                _presence.value = _presence.value + (userId to RemotePresence(
                    online = if (o.isNull("online")) null else o.optBoolean("online"),
                    lastSeenAt = o.optString("lastSeenAt").takeIf { it.isNotBlank() },
                ))
            }
        }
    }

    fun setTyping(chatId: String, active: Boolean) {
        viewModelScope.launch { runCatching { api.setTyping(chatId, active) } }
    }

    fun sendMessage(chatId: String, text: String) {
        val sender = _currentUser.value?.id ?: return
        val trimmed = text.trim(); if (trimmed.isEmpty()) return
        viewModelScope.launch { runCatching { sync.enqueueText(chatId, sender, trimmed) }.onFailure { _error.value = it.message } }
    }

    fun sendMedia(chatId: String, uri: Uri, mime: String, displayName: String) {
        val sender = _currentUser.value?.id ?: return
        viewModelScope.launch { runCatching { sync.enqueueMedia(chatId, sender, uri, mime, displayName) }.onFailure { _error.value = it.message } }
    }

    fun retryMessage(clientId: String) {
        viewModelScope.launch { runCatching { sync.retry(clientId) }.onFailure { _error.value = it.message } }
    }

    fun editMessage(chatId: String, messageId: String, body: String) {
        viewModelScope.launch {
            runCatching { sync.editMessage(chatId, messageId, body) }.onFailure { _error.value = it.message }
        }
    }

    fun deleteMessage(chatId: String, messageId: String) {
        viewModelScope.launch {
            runCatching { api.deleteMessage(chatId, messageId); sync.onDeletedMessage(messageId) }
                .onFailure { _error.value = it.message }
        }
    }

    fun markRead(chatId: String) {
        viewModelScope.launch { sync.clearUnread(chatId) }
        val myId = _currentUser.value?.id ?: return
        val lastIncoming = _messages.value[chatId].orEmpty().lastOrNull { it.senderId != myId && !it.id.startsWith("local:") } ?: return
        viewModelScope.launch { runCatching { sync.markRead(chatId, lastIncoming.id); sync.refreshChats() } }
    }

    private fun handleRealtime(event: JSONObject) {
        when (event.optString("type")) {
            "message.new" -> {
                val m = MessageSyncRepository.parseMessage(event.getJSONObject("data"))
                viewModelScope.launch { runCatching { sync.refreshChats(); sync.onRemoteMessage(m) }.onFailure { _error.value = it.message } }
            }
            "message.edited" -> {
                val m = MessageSyncRepository.parseMessage(event.getJSONObject("data"))
                viewModelScope.launch { sync.onEditedMessage(m) }
            }
            "message.deleted" -> {
                val d = event.getJSONObject("data")
                viewModelScope.launch { sync.onDeletedMessage(d.getString("messageId")); sync.refreshChats() }
            }
            "receipt.update" -> {
                val d = event.getJSONObject("data")
                val prefs = getApplication<Application>().getSharedPreferences("zoolchat_user_settings", android.content.Context.MODE_PRIVATE)
                val incomingStatus = d.getString("status")
                val visibleStatus = if (incomingStatus == "read" && !prefs.getBoolean("read_receipts", true)) "delivered" else incomingStatus
                viewModelScope.launch { sync.onReceipt(d.getString("messageId"), visibleStatus) }
            }
            "typing.update" -> {
                val d = event.getJSONObject("data")
                val chatId = d.getString("chatId")
                _typing.value = _typing.value + (chatId to d.optBoolean("active", false))
            }
            "call.invite" -> {
                val d = event.getJSONObject("data")
                _incomingRealtimeCall.value = d.getString("callId") to (d.optString("mode") == "video")
                refreshCallHistory()
            }
            "call.accepted", "call.ended", "call.declined" -> refreshCallHistory()
            "group.added" -> viewModelScope.launch { runCatching { sync.refreshChats() } }
        }
    }

    fun consumeIncomingRealtimeCall() { _incomingRealtimeCall.value = null }

    private fun registerFcmIfAvailable() {
        if (!FirebaseApp.getApps(getApplication()).isNullOrEmpty()) {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                viewModelScope.launch { runCatching { api.registerFcm(token) } }
            }
        }
    }


    private suspend fun clearSignedInState(clearCryptoIdentity: Boolean) {
        RealtimeForegroundService.stop(getApplication())
        messageCollectors.values.forEach { it.cancel() }
        messageCollectors.clear()
        runCatching { sync.clearLocal() }
        if (clearCryptoIdentity) runCatching { e2ee.clearLocalAccountKeys() }
        _currentUser.value = null
        _contacts.value = emptyList()
        _directorySearch.value = emptyList()
        _chats.value = emptyList()
        _messages.value = emptyMap()
        _callHistory.value = emptyList()
        _hasMoreMessages.value = emptyMap()
        _incomingRealtimeCall.value = null
        _presence.value = emptyMap()
        _typing.value = emptyMap()
    }

    fun logout(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { auth.signOut() }
            clearSignedInState(clearCryptoIdentity = false)
            onDone()
        }
    }

    fun deleteAccount(onDone: () -> Unit) {
        viewModelScope.launch {
            val deleted = runCatching { api.deleteAccount() }
            if (deleted.isFailure) { _error.value = deleted.exceptionOrNull()?.message; return@launch }
            clearSignedInState(clearCryptoIdentity = true)
            runCatching { auth.signOut() }
            onDone()
        }
    }

    fun clearError() { _error.value = null }
    override fun onCleared() {
        messageCollectors.values.forEach { it.cancel() }
        super.onCleared()
    }
}
