package com.zoolchat.app

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import com.zoolchat.app.auth.AuthRepository
import com.zoolchat.app.auth.SessionStore
import com.zoolchat.app.notifications.NotificationHelper
import com.zoolchat.app.ui.theme.ThemeMode
import com.zoolchat.app.ui.theme.ZoolChatTheme

data class IncomingCallNav(val callId: String, val video: Boolean, val autoAccept: Boolean = false)

class MainActivity : ComponentActivity() {
    private var incomingCall by mutableStateOf<IncomingCallNav?>(null)
    private var openChatId by mutableStateOf<String?>(null)
    private var sessionPrefs: SharedPreferences? = null

    private val sessionListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "current_user_id" || key == "access_token") maybeRequestContactsPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(); NotificationHelper.ensureChannels(this); consumeIntent(intent)
        sessionPrefs = getSharedPreferences("zoolchat_session", MODE_PRIVATE).also { it.registerOnSharedPreferenceChangeListener(sessionListener) }
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
        maybeRequestContactsPermission()
    }

    private fun maybeRequestContactsPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) return
        val session = SessionStore(this)
        if (!AuthRepository(this, session).signedIn) return

        // Use a versioned one-shot flag so existing v0.32 users also receive the permission request
        // after this update, even if an older contacts-explanation flag was already set.
        val prefs = getSharedPreferences("zoolchat_preferences", MODE_PRIVATE)
        if (prefs.getBoolean(CONTACTS_PERMISSION_V032_ASKED, false)) return
        prefs.edit().putBoolean(CONTACTS_PERMISSION_V032_ASKED, true).apply()
        session.contactsExplained = true
        requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_CONTACTS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CONTACTS && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && !isFinishing) {
            // Authenticated bootstrap calls discoverContacts(); recreate once so it runs immediately
            // with READ_CONTACTS already granted and populates matching ZoolChat users.
            recreate()
        }
    }

    override fun onResume() { super.onResume(); com.zoolchat.app.notifications.ConversationVisibility.foreground = true }
    override fun onPause() { com.zoolchat.app.notifications.ConversationVisibility.foreground = false; super.onPause() }

    override fun onDestroy() {
        sessionPrefs?.unregisterOnSharedPreferenceChangeListener(sessionListener)
        sessionPrefs = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); consumeIntent(intent) }
    private fun consumeIntent(intent: Intent?) {
        val id = intent?.getStringExtra("incoming_call_id")
        if (!id.isNullOrBlank()) incomingCall = IncomingCallNav(id, intent.getBooleanExtra("incoming_call_video", false), intent.getBooleanExtra("incoming_call_accept", false))
        if(intent?.getBooleanExtra("open_meet",false)==true)openChatId="__meet__"
        val chat = intent?.getStringExtra("open_chat_id"); if (!chat.isNullOrBlank()) openChatId = chat
    }

    private companion object {
        const val REQUEST_CONTACTS = 3201
        const val CONTACTS_PERMISSION_V032_ASKED = "contacts_permission_v032_asked"
    }
}
