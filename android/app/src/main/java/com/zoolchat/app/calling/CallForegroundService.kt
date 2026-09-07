package com.zoolchat.app.calling

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zoolchat.app.MainActivity
import com.zoolchat.app.R
import com.zoolchat.app.notifications.NotificationHelper

class CallForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationHelper.ensureChannels(this)
        val video = intent?.getBooleanExtra("video", false) == true
        val open = android.app.PendingIntent.getActivity(
            this,
            7001,
            Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, NotificationHelper.CALL_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_zoolchat)
            .setContentTitle("ZoolChat")
            .setContentText(if (video) "مكالمة فيديو جارية" else "مكالمة صوتية جارية")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val typeMask = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    (if (video) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
                startForeground(7001, notification, typeMask)
            } else {
                startForeground(7001, notification)
            }
            START_NOT_STICKY
        } catch (t: Throwable) {
            // A foreground-service policy failure must not tear down an otherwise valid call that
            // is already visible in the foreground activity. The media engine can continue while
            // this service stops; background persistence simply will not be available this time.
            Log.w("ZoolCallService", "Unable to promote active call service", t)
            stopSelf(startId)
            START_NOT_STICKY
        }
    }

    companion object {
        fun start(context: Context, video: Boolean) {
            val intent = Intent(context, CallForegroundService::class.java).putExtra("video", video)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w("ZoolCallService", "Unable to start active call service", it) }
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, CallForegroundService::class.java))
        }
    }
}
