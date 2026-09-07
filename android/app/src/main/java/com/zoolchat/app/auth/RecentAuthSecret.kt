package com.zoolchat.app.auth

/**
 * In-memory one-shot handoff used only immediately after successful password authentication so
 * the E2EE manager can restore or create the encrypted identity backup. Nothing is persisted.
 */
object RecentAuthSecret {
    @Volatile private var value: String? = null

    @Synchronized fun put(password: String) {
        value = password.takeIf { it.length in 8..128 }
    }

    @Synchronized fun consume(): String? {
        val current = value
        value = null
        return current
    }

    @Synchronized fun clear() { value = null }
}
