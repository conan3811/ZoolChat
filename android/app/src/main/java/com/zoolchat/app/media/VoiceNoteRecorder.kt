package com.zoolchat.app.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

class VoiceNoteRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var output: File? = null
    val isRecording: Boolean get() = recorder != null

    fun start(): Result<File> = runCatching {
        stopSilently()
        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioEncodingBitRate(64_000)
        r.setAudioSamplingRate(44_100)
        r.setOutputFile(file.absolutePath)
        r.prepare()
        r.start()
        recorder = r
        output = file
        file
    }

    fun stop(): Result<File> = runCatching {
        val r = requireNotNull(recorder) { "not_recording" }
        val file = requireNotNull(output)
        r.stop()
        r.release()
        recorder = null
        output = null
        file
    }.onFailure { stopSilently() }

    fun cancel() {
        val f = output
        stopSilently()
        f?.delete()
    }

    private fun stopSilently() {
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        output = null
    }
}
