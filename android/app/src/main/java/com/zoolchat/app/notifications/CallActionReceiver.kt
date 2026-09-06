package com.zoolchat.app.notifications

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.zoolchat.app.auth.AuthRepository
import com.zoolchat.app.auth.SessionStore
import com.zoolchat.app.network.ApiClient

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra("call_id") ?: return
        if (intent.getStringExtra("action") == "decline") {
            context.getSystemService(NotificationManager::class.java).cancel(callId.hashCode())
            val request = OneTimeWorkRequestBuilder<CallDeclineWorker>()
                .setInputData(workDataOf("callId" to callId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}

class CallDeclineWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val callId = inputData.getString("callId").orEmpty()
        if (callId.isBlank()) return Result.success()
        val session = SessionStore(applicationContext)
        val auth = AuthRepository(applicationContext, session)
        if (!auth.signedIn) return Result.retry()
        return runCatching { ApiClient(auth, session).declineCall(callId); Result.success() }
            .getOrElse { Result.retry() }
    }
}
