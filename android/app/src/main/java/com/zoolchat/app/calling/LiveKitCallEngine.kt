package com.zoolchat.app.calling

import android.content.Context
import android.content.Intent
import io.livekit.android.AudioOptions
import io.livekit.android.LiveKitOverrides
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.LiveKit
import io.livekit.android.e2ee.E2EEOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.room.track.screencapture.ScreenCaptureParams
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class LiveKitCallEngine(private val context: Context) : CallEngine {
    private var room: Room? = null
    private var audioHandler: AudioSwitchHandler? = null
    private var eventsJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected
    private val _remoteParticipantPresent = MutableStateFlow(false)
    val remoteParticipantPresent: StateFlow<Boolean> = _remoteParticipantPresent

    override suspend fun connect(config: ZoolCallConfig): Result<Unit> = runCatching {
        disconnect()
        if(config.startMicrophone) check(androidx.core.content.ContextCompat.checkSelfPermission(context,android.Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED) { "microphone_permission_required" }
        val handler=AudioSwitchHandler(context.applicationContext).apply {
            manageAudioFocus=true
            audioMode=android.media.AudioManager.MODE_IN_COMMUNICATION
            audioAttributeUsageType=android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION
            audioAttributeContentType=android.media.AudioAttributes.CONTENT_TYPE_SPEECH
            forceHandleAudioRouting=true
        }
        audioHandler=handler
        val newRoom = LiveKit.create(context.applicationContext, overrides=LiveKitOverrides(audioOptions=AudioOptions(audioHandler=handler)))
        if (!config.e2eeKey.isNullOrBlank()) {
            newRoom.e2eeOptions = E2EEOptions(config.e2eeKey)
        }
        room = newRoom
        eventsJob = scope.launch {
            newRoom.events.collect { event ->
                when (event) {
                    is RoomEvent.TrackSubscribed -> {
                        if (event.track is VideoTrack) _remoteVideoTrack.value = event.track as VideoTrack
                        if (event.track is RemoteAudioTrack) { (event.track as RemoteAudioTrack).enabled=true; (event.track as RemoteAudioTrack).setVolume(1.0) }
                    }
                    is RoomEvent.TrackUnsubscribed -> if (_remoteVideoTrack.value === event.track) _remoteVideoTrack.value = null
                    is RoomEvent.ParticipantConnected -> _remoteParticipantPresent.value = true
                    is RoomEvent.ParticipantDisconnected -> _remoteParticipantPresent.value = newRoom.remoteParticipants.isNotEmpty()
                    is RoomEvent.Disconnected -> { _connected.value = false; _remoteParticipantPresent.value = false; _remoteVideoTrack.value = null }
                    else -> Unit
                }
            }
        }
        newRoom.prepareConnection(config.serverUrl, config.token)
        newRoom.connect(config.serverUrl, config.token)
        check(newRoom.localParticipant.setMicrophoneEnabled(config.startMicrophone)) { "microphone_publish_failed" }
        newRoom.localParticipant.setCameraEnabled(config.startCamera)
        _connected.value = true
        _remoteParticipantPresent.value = newRoom.remoteParticipants.isNotEmpty()
    }

    fun setSpeakerEnabled(enabled:Boolean) {
        val handler=audioHandler ?: return
        val device=handler.availableAudioDevices.firstOrNull { it.javaClass.simpleName == if(enabled) "Speakerphone" else "Earpiece" }
        handler.selectDevice(device)
    }
    fun useAutomaticAudioRoute() { audioHandler?.selectDevice(null) }
    override suspend fun setMicrophoneEnabled(enabled: Boolean) { check(room?.localParticipant?.setMicrophoneEnabled(enabled)==true) { "microphone_publish_failed" } }
    override suspend fun setCameraEnabled(enabled: Boolean) { room?.localParticipant?.setCameraEnabled(enabled) }
    override suspend fun setScreenShareEnabled(enabled: Boolean, permissionData: Intent?) {
        val participant = room?.localParticipant ?: return
        if (enabled) {
            val data = permissionData ?: throw IllegalArgumentException("screen_share_permission_required")
            participant.setScreenShareEnabled(true, ScreenCaptureParams(data))
        } else participant.setScreenShareEnabled(false)
    }

    override suspend fun disconnect() {
        eventsJob?.cancel()
        eventsJob = null
        _remoteVideoTrack.value = null
        _connected.value = false
        _remoteParticipantPresent.value = false
        room?.disconnect()
        room?.release()
        room = null
        audioHandler?.stop(); audioHandler=null
    }

    fun currentRoom(): Room? = room
}
