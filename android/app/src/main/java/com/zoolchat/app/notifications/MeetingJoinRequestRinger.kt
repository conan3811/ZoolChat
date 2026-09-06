package com.zoolchat.app.notifications

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper

/**
 * Short repeated notification tone for a host while one or more people are waiting to join.
 * This intentionally uses notification audio attributes rather than call/voice-call audio so it
 * does not take ownership of LiveKit's communication audio route.
 */
object MeetingJoinRequestRinger {
    private data class Active(val ringtone:Ringtone,val runnable:Runnable)
    private val handler=Handler(Looper.getMainLooper())
    private val active=mutableMapOf<String,Active>()

    @Synchronized
    fun start(context:Context,meetingId:String) {
        if(meetingId.isBlank()||active.containsKey(meetingId))return
        val app=context.applicationContext
        val uri=RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)?:return
        val ringtone=runCatching{RingtoneManager.getRingtone(app,uri)}.getOrNull()?:return
        runCatching{
            ringtone.audioAttributes=AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        }
        lateinit var runnable:Runnable
        runnable=Runnable {
            synchronized(this) {
                if(!active.containsKey(meetingId))return@Runnable
                runCatching{if(ringtone.isPlaying)ringtone.stop();ringtone.play()}
                handler.postDelayed(runnable,3_500L)
            }
        }
        active[meetingId]=Active(ringtone,runnable)
        handler.post(runnable)
    }

    @Synchronized
    fun stop(meetingId:String) {
        val item=active.remove(meetingId)?:return
        handler.removeCallbacks(item.runnable)
        runCatching{item.ringtone.stop()}
    }

    @Synchronized
    fun stopAll() {
        val ids=active.keys.toList()
        ids.forEach(::stop)
    }
}
