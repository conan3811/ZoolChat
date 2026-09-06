package com.zoolchat.app.data

/**
 * Legacy preview repository kept only so old, unreachable prototype composables continue to compile.
 * Production builds must never ship fabricated conversations, calls, groups or stories.
 */
object DemoRepository {
    val chats: List<ChatPreview> = emptyList()
    val messages: List<ChatMessage> = emptyList()
    val calls: List<CallHistoryItem> = emptyList()
    val stories: List<StoryItem> = emptyList()
}
