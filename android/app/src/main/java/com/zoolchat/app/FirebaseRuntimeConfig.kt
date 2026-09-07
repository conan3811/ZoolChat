package com.zoolchat.app

import android.app.Application
import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import java.util.Properties

/**
 * Initializes the default Firebase app from a private build-time/runtime asset.
 *
 * The asset `zoolchat-firebase.properties` is intentionally not stored in Git. Release packaging
 * injects it after the Android build so Firebase client identifiers never need to be committed to
 * the source repository. This also keeps FCM usable when the standard google-services Gradle secret
 * is unavailable in CI.
 */
object FirebaseRuntimeConfig {
    private const val assetName = "zoolchat-firebase.properties"

    @Synchronized
    fun initialize(context: Context): Boolean {
        if (FirebaseApp.getApps(context).isNotEmpty()) return true
        val p = Properties()
        return runCatching {
            context.assets.open(assetName).use(p::load)
            val applicationId = p.getProperty("application_id")?.trim().orEmpty()
            val apiKey = p.getProperty("api_key")?.trim().orEmpty()
            val projectId = p.getProperty("project_id")?.trim().orEmpty()
            val senderId = p.getProperty("gcm_sender_id")?.trim().orEmpty()
            require(applicationId.isNotBlank() && apiKey.isNotBlank() && projectId.isNotBlank() && senderId.isNotBlank())

            val builder = FirebaseOptions.Builder()
                .setApplicationId(applicationId)
                .setApiKey(apiKey)
                .setProjectId(projectId)
                .setGcmSenderId(senderId)
            p.getProperty("storage_bucket")?.trim()?.takeIf { it.isNotBlank() }?.let(builder::setStorageBucket)
            FirebaseApp.initializeApp(context, builder.build()) != null
        }.getOrDefault(false)
    }
}

class ZoolChatApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseRuntimeConfig.initialize(this)
    }
}
