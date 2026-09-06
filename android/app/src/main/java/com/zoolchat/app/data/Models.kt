package com.zoolchat.app.data

data class ZoolUser(
    val id: String,
    val name: String,
    val handle: String,
    val isOnline: Boolean = false,
    val avatarSeed: Int = 0,
)

data class ChatPreview(
    val id: String,
    val title: String,
    val subtitle: String,
    val time: String,
    val unread: Int = 0,
    val isGroup: Boolean = false,
    val online: Boolean = false,
)

data class ChatMessage(
    val id: String,
    val senderName: String,
    val text: String,
    val time: String,
    val mine: Boolean,
    val type: MessageType = MessageType.TEXT,
)

enum class MessageType { TEXT, VOICE, PHOTO, FILE, CALL }

data class CallHistoryItem(
    val id: String,
    val name: String,
    val label: String,
    val time: String,
    val video: Boolean,
    val missed: Boolean = false,
)

data class StoryItem(
    val id: String,
    val name: String,
    val caption: String,
    val viewed: Boolean,
    val mine: Boolean = false,
)
