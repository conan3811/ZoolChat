package com.zoolchat.app.notifications

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.zoolchat.app.MainActivity
import com.zoolchat.app.R

object NotificationHelper {
    // Versioned channel IDs migrate installations whose old channel had no sound.
    const val CHAT_CHANNEL="zoolchat_messages_v032"
    const val GROUP_CHANNEL="zoolchat_groups_v032"
    const val CALL_CHANNEL="zoolchat_calls_v032"
    const val MISSED_CHANNEL="zoolchat_missed_calls_v032"
    const val MEET_CHANNEL="zoolchat_meet_v032"
    private const val MESSAGE_GROUP="zoolchat_message_notifications"

    fun ensureChannels(context:Context) {
        val nm=context.getSystemService(NotificationManager::class.java)
        for ((id,name) in listOf(CHAT_CHANNEL to "Messages", GROUP_CHANNEL to "Group messages", MISSED_CHANNEL to "Missed calls", MEET_CHANNEL to "ZoolMeet invitations")) {
            nm.createNotificationChannel(NotificationChannel(id,name,NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true); vibrationPattern=longArrayOf(0,100)
                lockscreenVisibility=Notification.VISIBILITY_PRIVATE
                setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION).build())
            })
        }
        nm.createNotificationChannel(NotificationChannel(CALL_CHANNEL,"ZoolChat calls",NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true); lockscreenVisibility=Notification.VISIBILITY_PRIVATE;setSound(android.provider.Settings.System.DEFAULT_RINGTONE_URI,android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build()) })
    }

    fun clearMessage(context:Context,chatId:String) { context.getSystemService(NotificationManager::class.java).cancel(chatId.hashCode()) }

    fun showMessage(context:Context,title:String,body:String,chatId:String,isGroup:Boolean=false) {
        if(chatId.isBlank() || ConversationVisibility.isOpen(chatId)) { clearMessage(context,chatId); return }
        if(context.getSharedPreferences("zoolchat_user_settings",0).getLong("mute_until_$chatId",0)>System.currentTimeMillis())return
        ensureChannels(context)
        if(Build.VERSION.SDK_INT>=33&&ActivityCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        val intent=Intent(context,MainActivity::class.java).putExtra("open_chat_id",chatId)
        val pi=PendingIntent.getActivity(context,chatId.hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Transport payloads must never become visible previews.
        val preview=if(body.startsWith("zce") || body.startsWith("zlc")) "رسالة جديدة" else body
        val channel=if(isGroup) GROUP_CHANNEL else CHAT_CHANNEL
        val n=NotificationCompat.Builder(context,channel).setSmallIcon(R.drawable.ic_stat_zoolchat)
            .setContentTitle(title).setContentText(preview).setAutoCancel(true).setContentIntent(pi)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE).setGroup(MESSAGE_GROUP).build()
        // One stable notification per conversation prevents duplicate stacks while still alerting
        // for each new message. Android groups multiple active conversations under MESSAGE_GROUP.
        context.getSystemService(NotificationManager::class.java).notify(chatId.hashCode(),n)
    }

    fun showMeetingInvite(context:Context,id:String,title:String) {
        ensureChannels(context)
        if(Build.VERSION.SDK_INT>=33&&ActivityCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        val intent=Intent(context,MainActivity::class.java).putExtra("open_meet",true)
        val pi=PendingIntent.getActivity(context,id.hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(NotificationManager::class.java).notify(id.hashCode(),NotificationCompat.Builder(context,MEET_CHANNEL).setSmallIcon(R.drawable.ic_stat_zoolchat).setContentTitle("ZoolMeet").setContentText(title).setContentIntent(pi).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPriority(NotificationCompat.PRIORITY_HIGH).build())
    }

    fun showMissedCall(context:Context,id:String) {
        ensureChannels(context)
        if(Build.VERSION.SDK_INT>=33&&ActivityCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        context.getSystemService(NotificationManager::class.java).notify(id.hashCode(),NotificationCompat.Builder(context,MISSED_CHANNEL).setSmallIcon(R.drawable.ic_stat_zoolchat).setContentTitle("ZoolChat").setContentText("مكالمة فائتة").setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
    }

    fun showIncomingCall(context:Context,callId:String,caller:String,video:Boolean){
        ensureChannels(context)
        if(Build.VERSION.SDK_INT>=33&&ActivityCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        // Full-screen intent only opens the incoming-call screen. It must never accept the call.
        val screenIntent=Intent(context,MainActivity::class.java)
            .putExtra("incoming_call_id",callId)
            .putExtra("incoming_call_video",video)
            .putExtra("incoming_call_accept",false)
        val screen=PendingIntent.getActivity(context,(callId+"screen").hashCode(),screenIntent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // The Answer action is an explicit user approval, so only this intent may auto-accept.
        val answerIntent=Intent(context,MainActivity::class.java)
            .putExtra("incoming_call_id",callId)
            .putExtra("incoming_call_video",video)
            .putExtra("incoming_call_accept",true)
        val answer=PendingIntent.getActivity(context,(callId+"answer").hashCode(),answerIntent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val declineIntent=Intent(context,CallActionReceiver::class.java).putExtra("action","decline").putExtra("call_id",callId)
        val decline=PendingIntent.getBroadcast(context,(callId+"decline").hashCode(),declineIntent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val person=Person.Builder().setName(caller).build()
        val builder=NotificationCompat.Builder(context,CALL_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_zoolchat)
            .setContentTitle(caller)
            .setContentText(if(video)"مكالمة فيديو واردة" else "مكالمة صوتية واردة")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setTimeoutAfter(60_000)
            .setContentIntent(screen)
            .setFullScreenIntent(screen,true)
        if(Build.VERSION.SDK_INT>=31)builder.setStyle(NotificationCompat.CallStyle.forIncomingCall(person,decline,answer))
        else builder.addAction(0,"رفض",decline).addAction(0,"رد",answer)
        context.getSystemService(NotificationManager::class.java).notify(callId.hashCode(),builder.build().apply { flags=flags or Notification.FLAG_INSISTENT })
    }
}
