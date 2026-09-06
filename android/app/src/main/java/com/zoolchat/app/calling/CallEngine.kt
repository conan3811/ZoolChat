package com.zoolchat.app.calling

import android.content.Intent

enum class ZoolMediaMode { AUDIO, VIDEO }

data class ZoolCallConfig(
    val roomName: String,
    val token: String,
    val serverUrl: String,
    val mode: ZoolMediaMode,
    val e2eeKey: String? = null,
    val startMicrophone: Boolean = true,
    val startCamera: Boolean = mode == ZoolMediaMode.VIDEO,
)

interface CallEngine {
    suspend fun connect(config: ZoolCallConfig): Result<Unit>
    suspend fun setMicrophoneEnabled(enabled: Boolean)
    suspend fun setCameraEnabled(enabled: Boolean)
    suspend fun setScreenShareEnabled(enabled: Boolean, permissionData: Intent? = null)
    suspend fun disconnect()
}

class DemoCallEngine : CallEngine {
    override suspend fun connect(config: ZoolCallConfig) = Result.success(Unit)
    override suspend fun setMicrophoneEnabled(enabled: Boolean) = Unit
    override suspend fun setCameraEnabled(enabled: Boolean) = Unit
    override suspend fun setScreenShareEnabled(enabled: Boolean, permissionData: Intent?) = Unit
    override suspend fun disconnect() = Unit
}
