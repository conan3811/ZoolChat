package com.zoolchat.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.zoolchat.app.notifications.NotificationHelper
import com.zoolchat.app.ui.theme.ThemeMode
import com.zoolchat.app.ui.theme.ZoolChatTheme

data class IncomingCallNav(val callId: String, val video: Boolean, val autoAccept: Boolean = false)

class MainActivity : ComponentActivity() {
    private var incomingCall by mutableStateOf<IncomingCallNav?>(null)
    private var openChatId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(); NotificationHelper.ensureChannels(this); consumeIntent(intent)
        val prefs = getSharedPreferences("zoolchat_preferences", MODE_PRIVATE)
        setContent {
            val savedMode = runCatching { ThemeMode.valueOf(prefs.getString("theme_mode", ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name) }.getOrDefault(ThemeMode.SYSTEM)
            var themeMode by remember { mutableStateOf(savedMode) }
            ZoolChatTheme(themeMode) {
                Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                    ZoolChatApp(themeMode, { newMode -> themeMode = newMode; prefs.edit().putString("theme_mode", newMode.name).apply() }, incomingCall, { incomingCall = null }, openChatId) { openChatId = null }
                }
            }
        }
    }

    override fun onResume() { super.onResume(); com.zoolchat.app.notifications.ConversationVisibility.foreground = true }
    override fun onPause() { com.zoolchat.app.notifications.ConversationVisibility.foreground = false; super.onPause() }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); consumeIntent(intent) }
    private fun consumeIntent(intent: Intent?) {
        val id = intent?.getStringExtra("incoming_call_id")
        if (!id.isNullOrBlank()) incomingCall = IncomingCallNav(id, intent.getBooleanExtra("incoming_call_video", false), intent.getBooleanExtra("incoming_call_accept", false))
        val chat = intent?.getStringExtra("open_chat_id"); if (!chat.isNullOrBlank()) openChatId = chat
    }
}
