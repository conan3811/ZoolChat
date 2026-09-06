package com.zoolchat.app.data

data class CurrentUser(
    val id: String,
    val displayName: String,
    val username: String?,
    val phoneE164: String?,
    val photoUrl: String?,
)

data class RegisteredContact(
    val userId: String,
    val phoneE164: String,
    val displayName: String,
    val username: String?,
    val photoUrl: String?,
    val localName: String,
)

data class RemoteChat(
    val id: String,
    val type: String,
    val title: String,
    val peerUserId: String?,
    val lastMessage: String?,
    val lastMessageAt: String?,
    val unread: Int,
)

data class RemoteMessage(
    val id: String,
    val chatId: String,
    val senderId: String,
    val type: String,
    val body: String,
    val createdAt: String,
    val editedAt: String? = null,
    val deleted: Boolean = false,
    val status: String = "sent",
    val clientId: String? = null,
    val mediaUrl: String? = null,
    val mediaMime: String? = null,
    val mediaName: String? = null,
    val mediaSize: Long? = null,
    val encrypted: Boolean = false,
    val cryptoVersion: Int = 0,
    val decryptionFailed: Boolean = false,
)

data class CallConnection(
    val callId: String,
    val serverUrl: String,
    val participantToken: String,
    val mode: String,
    val e2eeKey: String? = null,
)

data class RemoteCallHistoryItem(
    val id: String,
    val type: String,
    val status: String,
    val participantStatus: String,
    val initiatedByMe: Boolean,
    val peerUserId: String?,
    val peerName: String,
    val createdAt: String,
    val answeredAt: String?,
    val endedAt: String?,
)

data class RemotePresence(
    val online: Boolean?,
    val lastSeenAt: String?,
)


data class RemoteGroupMember(
    val userId: String,
    val displayName: String,
    val username: String?,
    val photoUrl: String?,
    val role: String,
)

data class RemoteGroupInfo(
    val id: String,
    val title: String,
    val description: String,
    val inviteCode: String,
    val myRole: String,
    val onlyAdminsSend: Boolean,
    val onlyAdminsEdit: Boolean,
    val onlyAdminsAdd: Boolean,
    val approveNewMembers: Boolean,
    val disappearingSeconds: Int,
    val members: List<RemoteGroupMember>,
)

data class RemoteMeetingParticipant(
    val userId: String,
    val displayName: String,
    val username: String?,
    val role: String,
    val status: String,
)
