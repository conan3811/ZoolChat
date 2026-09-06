package com.zoolchat.app.notifications

/** Shared by realtime and FCM; a background Activity never suppresses notifications. */
object ConversationVisibility {
    @Volatile var foreground = false
    @Volatile var chatId: String? = null
    fun isOpen(id: String) = foreground && chatId == id
}
