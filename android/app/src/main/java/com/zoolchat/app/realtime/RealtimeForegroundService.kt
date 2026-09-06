package com.zoolchat.app.realtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.zoolchat.app.R
import com.zoolchat.app.auth.AuthRepository
import com.zoolchat.app.auth.SessionStore
import com.zoolchat.app.network.ApiClient
import com.zoolchat.app.notifications.NotificationHelper
import com.zoolchat.app.sync.MessageSyncRepository
import org.json.JSONObject

class RealtimeForegroundService : Service() {
    private var realtime: RealtimeClient? = null

    override fun onCreate() {
        super.onCreate()
        val nm=getSystemService(NotificationManager::class.java)
        if(Build.VERSION.SDK_INT>=26) nm.createNotificationChannel(NotificationChannel(CHANNEL,"اتصال ZoolChat",NotificationManager.IMPORTANCE_LOW))
        val n=NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_stat_zoolchat).setContentTitle("ZoolChat متصل").setContentText("استقبال الرسائل والمكالمات في الخلفية").setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW).build()
        startForeground(NOTIFICATION_ID,n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(realtime==null){
            val session=SessionStore(this); val auth=AuthRepository(this,session)
            if(!auth.signedIn){ stopSelf(); return START_NOT_STICKY }
            val api=ApiClient(auth,session)
            realtime=RealtimeClient(api.http,auth,session){event->handle(event)}.also{it.connect(api.baseUrl)}
        }
        return START_STICKY
    }

    private fun handle(event: JSONObject){
        RealtimeEventBus.emit(event)
        when(event.optString("type")){
            "message.new"->{
                val d=event.optJSONObject("data")?:return
                val chatId=d.optString("chatId"); if(chatId.isBlank())return
                MessageSyncRepository.scheduleIncomingSync(this,chatId)
                val sender=d.optString("senderName").ifBlank{"ZoolChat"}
                val body=if(d.optBoolean("encrypted",false)) "🔒 رسالة مشفرة" else if(d.optString("type")=="text")d.optString("body").ifBlank{"رسالة جديدة"} else "📎 مرفق جديد"
                NotificationHelper.showMessage(this,sender,body,chatId)
            }
            "call.invite"->{
                val d=event.optJSONObject("data")?:return
                NotificationHelper.showIncomingCall(this,d.optString("callId"),d.optString("callerName").ifBlank{"ZoolChat"},d.optString("mode")=="video")
            }
            "call.ended","call.declined"->{
                val id=event.optJSONObject("data")?.optString("callId").orEmpty(); if(id.isNotBlank()) getSystemService(NotificationManager::class.java).cancel(id.hashCode())
            }
        }
    }

    override fun onDestroy(){ realtime?.close(); realtime=null; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL="zoolchat_realtime"; private const val NOTIFICATION_ID=27018
        fun start(context: Context){
            val i=Intent(context,RealtimeForegroundService::class.java)
            if(Build.VERSION.SDK_INT>=26) context.startForegroundService(i) else context.startService(i)
        }
        fun stop(context: Context){ context.stopService(Intent(context,RealtimeForegroundService::class.java)) }
    }
}
