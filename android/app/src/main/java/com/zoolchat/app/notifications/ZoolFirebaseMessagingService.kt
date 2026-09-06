package com.zoolchat.app.notifications

import android.app.NotificationManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.zoolchat.app.auth.AuthRepository
import com.zoolchat.app.auth.SessionStore
import com.zoolchat.app.network.ApiClient
import com.zoolchat.app.sync.MessageSyncRepository

class ZoolFirebaseMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val d=message.data
        when(d["type"]){
            "meeting_invite"->NotificationHelper.showMeetingInvite(this,d["inviteId"].orEmpty(),d["title"]?:"ZoolMeet")
            "call_invite"->NotificationHelper.showIncomingCall(this,d["callId"].orEmpty(),d["callerName"]?:"ZoolChat",d["mode"]=="video")
            "call_cancelled"->{ val id=d["callId"].orEmpty(); if(id.isNotBlank()) getSystemService(NotificationManager::class.java).cancel(id.hashCode());if(d["reason"]=="missed")NotificationHelper.showMissedCall(this,id) }
            "message_new"->{
                val chatId=d["chatId"].orEmpty()
                MessageSyncRepository.scheduleIncomingSync(this,chatId)
                NotificationHelper.showMessage(this,d["senderName"]?:"ZoolChat",d["body"]?:"رسالة جديدة",chatId)
            }
            "chat_sync"->MessageSyncRepository.scheduleIncomingSync(this,d["chatId"].orEmpty())
        }
    }

    override fun onNewToken(token: String) {
        val request=OneTimeWorkRequestBuilder<FcmTokenRegisterWorker>()
            .setInputData(workDataOf("token" to token))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueue(request)
    }
}

class FcmTokenRegisterWorker(appContext: android.content.Context, params: WorkerParameters):CoroutineWorker(appContext,params){
    override suspend fun doWork():Result{
        val token=inputData.getString("token").orEmpty();if(token.isBlank())return Result.success()
        val session=SessionStore(applicationContext);val auth=AuthRepository(applicationContext,session)
        if(!auth.signedIn)return Result.retry()
        return runCatching{ApiClient(auth,session).registerFcm(token);Result.success()}.getOrElse{Result.retry()}
    }
}
