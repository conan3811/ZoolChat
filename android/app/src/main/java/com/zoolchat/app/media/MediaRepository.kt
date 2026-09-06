package com.zoolchat.app.media

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import com.zoolchat.app.crypto.E2eeManager
import com.zoolchat.app.data.RemoteMessage
import com.zoolchat.app.network.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MediaRepository(
    private val context: Context,
    private val api: ApiClient,
    private val e2ee: E2eeManager,
    private val peerUserId: String?,
) {
    suspend fun download(message: RemoteMessage): File {
        val url = requireNotNull(message.mediaUrl) { "media_url_missing" }
        val raw = api.downloadMedia(url, if(message.encrypted) "blob.zce" else (message.mediaName ?: "attachment"))
        if(!message.encrypted) return raw
        val peer = peerUserId ?: error("peer_identity_missing")
        val clientId = message.clientId ?: error("message_client_id_missing")
        val safe=(message.mediaName ?: "attachment").replace(Regex("[^A-Za-z0-9._-]"),"_").takeLast(90).ifBlank{"attachment"}
        val cacheDir=File(context.cacheDir,"zool_media").apply{mkdirs()}
        val out=File(cacheDir,"zool_media_dec_${message.id.hashCode().toUInt()}_$safe")
        if(out.exists()&&out.length()>0){ raw.delete(); return out }
        try {
            e2ee.decryptMediaFile(message.chatId,peer,clientId,message.type,raw,out)
            return out
        } finally { raw.delete() }
    }

    suspend fun loadBitmap(message: RemoteMessage): Bitmap? = withContext(Dispatchers.IO) {
        if (message.type != "image") return@withContext null
        runCatching { BitmapFactory.decodeFile(download(message).absolutePath) }.getOrNull()
    }

    suspend fun open(message: RemoteMessage) {
        val file = download(message)
        withContext(Dispatchers.Main) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, message.mediaMime ?: "application/octet-stream")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(Intent.createChooser(intent, "فتح المرفق").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
