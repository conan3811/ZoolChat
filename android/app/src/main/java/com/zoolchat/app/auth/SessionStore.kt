package com.zoolchat.app.auth

import android.content.Context
import com.zoolchat.app.crypto.SecureValueStore

class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("zoolchat_session", Context.MODE_PRIVATE)
    private val secure = SecureValueStore(context.applicationContext)

    var onboardingComplete: Boolean
        get() = prefs.getBoolean("onboarding_complete", false)
        set(value) { prefs.edit().putBoolean("onboarding_complete", value).apply() }

    var contactsExplained: Boolean
        get() = prefs.getBoolean("contacts_explained", false)
        set(value) { prefs.edit().putBoolean("contacts_explained", value).apply() }

    var notificationsExplained: Boolean
        get() = prefs.getBoolean("notifications_explained", false)
        set(value) { prefs.edit().putBoolean("notifications_explained", value).apply() }

    /**
     * v0.32 no longer blocks a successful login/registration behind a mandatory profile step.
     * Keep the legacy preference write-compatible so upgrades are harmless, but never let an
     * old stored value redirect an authenticated user away from Chats. Profile editing remains
     * available from Settings.
     */
    var initialProfileSetupPending: Boolean
        get() = false
        set(@Suppress("UNUSED_PARAMETER") value) {
            prefs.edit().remove("initial_profile_setup_pending").apply()
        }

    var devUserId: String?
        get() = prefs.getString("dev_user_id", null)
        set(value) { prefs.edit().putString("dev_user_id", value).apply() }

    var devPhoneE164: String?
        get() = prefs.getString("dev_phone_e164", null)
        set(value) { prefs.edit().putString("dev_phone_e164", value).apply() }

    var devDisplayName: String
        get() = prefs.getString("dev_display_name", "") ?: ""
        set(value) { prefs.edit().putString("dev_display_name", value).apply() }

    var localDisplayName: String
        get() = prefs.getString("display_name", "") ?: ""
        set(value) { prefs.edit().putString("display_name", value).apply() }

    var apiBaseUrlOverride: String?
        get() = prefs.getString("api_base_url_override", null)
        set(value) {
            val normalized = value?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
            if (normalized == null) prefs.edit().remove("api_base_url_override").apply()
            else prefs.edit().putString("api_base_url_override", normalized).apply()
        }

    var accessToken: String?
        get() = secure.reveal(prefs.getString("access_token", null))
        set(value) { if (value == null) prefs.edit().remove("access_token").apply() else prefs.edit().putString("access_token", secure.protect(value)).apply() }

    var refreshToken: String?
        get() = secure.reveal(prefs.getString("refresh_token", null))
        set(value) { if (value == null) prefs.edit().remove("refresh_token").apply() else prefs.edit().putString("refresh_token", secure.protect(value)).apply() }

    var accessTokenExpiresAtMs: Long
        get() = prefs.getLong("access_token_expires_at_ms", 0L)
        set(value) { prefs.edit().putLong("access_token_expires_at_ms", value).apply() }

    var currentUserId: String?
        get() = prefs.getString("current_user_id", null)
        set(value) { if (value == null) prefs.edit().remove("current_user_id").apply() else prefs.edit().putString("current_user_id", value).apply() }

    var accountUsername: String
        get() = prefs.getString("account_username", "") ?: ""
        set(value) { prefs.edit().putString("account_username", value).apply() }

    fun clearFreeAuth() {
        prefs.edit()
            .remove("access_token")
            .remove("refresh_token")
            .remove("access_token_expires_at_ms")
            .remove("account_username")
            .remove("current_user_id")
            .remove("initial_profile_setup_pending")
            .apply()
    }

    fun clearDevIdentity() {
        prefs.edit().remove("dev_user_id").remove("dev_phone_e164").remove("dev_display_name").apply()
    }
}
