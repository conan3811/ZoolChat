package com.zoolchat.app.ui.screens

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.zoolchat.app.auth.SessionStore
import com.zoolchat.app.calling.CallForegroundService
import com.zoolchat.app.calling.LiveKitCallEngine
import com.zoolchat.app.calling.ZoolCallConfig
import com.zoolchat.app.calling.ZoolMediaMode
import com.zoolchat.app.crypto.E2eeManager
import com.zoolchat.app.network.ApiClient
import com.zoolchat.app.network.ApiException
import com.zoolchat.app.realtime.RealtimeEventBus
import io.livekit.android.renderer.SurfaceViewRenderer
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun ProductionCallScreen(
    api: ApiClient,
    peerUserId: String?,
    incomingCallId: String?,
    video: Boolean,
    autoAcceptIncoming: Boolean = false,
    onEnd: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { LiveKitCallEngine(context) }
    val e2eeManager = remember(api) { E2eeManager(context.applicationContext, api, SessionStore(context.applicationContext)) }
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val incoming = incomingCallId != null
    var state by remember { mutableStateOf(if (incoming) if (video) "مكالمة فيديو واردة" else "مكالمة صوتية واردة" else "جاري الاتصال…") }
    var mic by remember { mutableStateOf(true) }
    var camera by remember { mutableStateOf(video) }
    var speaker by remember { mutableStateOf(video) }
    var error by remember { mutableStateOf<String?>(null) }
    var e2ee by remember { mutableStateOf(false) }
    var activeCallId by remember { mutableStateOf<String?>(incomingCallId) }
    var ended by remember { mutableStateOf(false) }
    var accepted by remember { mutableStateOf(!incoming) }
    var connectionStarted by remember { mutableStateOf(false) }
    val remoteTrack by engine.remoteVideoTrack.collectAsState()
    val connected by engine.connected.collectAsState()
    val remotePresent by engine.remoteParticipantPresent.collectAsState()

    fun setSpeaker(enabled: Boolean) { engine.setSpeakerEnabled(enabled) }

    suspend fun connectAfterPermission() {
        if (connectionStarted || ended) return
        connectionStarted = true
        incomingCallId?.let { context.getSystemService(NotificationManager::class.java).cancel(it.hashCode()) }
        runCatching {
            val o = if (incomingCallId != null) api.acceptCall(incomingCallId) else api.createCall(requireNotNull(peerUserId), video)
            activeCallId = o.optString("callId").takeIf { it.isNotBlank() }
            val peerId = o.optString("peerUserId").takeIf { it.isNotBlank() } ?: peerUserId ?: error("peer_identity_missing")
            val callId = activeCallId ?: error("call_id_missing")
            val e2eeKey = e2eeManager.callKey(callId, peerId)
            val cfg = ZoolCallConfig(
                roomName = callId,
                token = o.getString("participantToken"),
                serverUrl = o.getString("serverUrl"),
                mode = if (o.optString("mode", if (video) "video" else "audio") == "video") ZoolMediaMode.VIDEO else ZoolMediaMode.AUDIO,
                e2eeKey = e2eeKey,
                startMicrophone = mic,
                startCamera = video && camera,
            )
            CallForegroundService.start(context, video)
            engine.connect(cfg).getOrThrow()
            e2ee = true
            accepted = true
            state = if (incoming) "متصل" else "جاري الرنين…"
            setSpeaker(speaker)
            activeCallId?.let { context.getSystemService(NotificationManager::class.java).cancel(it.hashCode()) }
        }.onFailure {
            engine.disconnect(); CallForegroundService.stop(context)
            activeCallId?.let { id -> runCatching { api.endCall(id) } }
            connectionStarted = false
            error = friendlyCallError(it)
            state = "تعذر الاتصال"
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) scope.launch { connectAfterPermission() }
        else {
            connectionStarted = false
            error = "يلزم السماح بالميكروفون${if (video) " والكاميرا" else ""} لإجراء المكالمة"
        }
    }

    fun requestAndConnect() {
        error = null
        permission.launch(if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) else arrayOf(Manifest.permission.RECORD_AUDIO))
    }

    // Outgoing calls may start immediately. Incoming calls do not start until the user explicitly
    // accepts from the in-app screen or presses the Answer action on the system notification.
    LaunchedEffect(Unit) {
        if (!incoming || autoAcceptIncoming) requestAndConnect()
    }

    LaunchedEffect(remotePresent, connected) {
        if (connected && remotePresent) state = "متصل"
        else if (connected && !incoming && !ended) state = "جاري الرنين…"
    }

    LaunchedEffect(Unit) {
        RealtimeEventBus.events.collect { event ->
            val type = event.optString("type")
            if (type !in setOf("call.accepted", "call.ended", "call.declined")) return@collect
            val data = event.optJSONObject("data") ?: JSONObject()
            val eventCallId = data.optString("callId")
            val currentCallId = activeCallId
            if (eventCallId.isBlank() || currentCallId.isNullOrBlank() || eventCallId != currentCallId || ended) return@collect
            when (type) {
                "call.accepted" -> state = "متصل"
                "call.declined" -> {
                    ended = true; state = "تم رفض المكالمة"; engine.disconnect(); CallForegroundService.stop(context); setSpeaker(false); delay(650); onEnd()
                }
                "call.ended" -> {
                    ended = true
                    state = if (data.optString("reason") == "missed") "لم يتم الرد" else "انتهت المكالمة"
                    engine.disconnect(); CallForegroundService.stop(context); setSpeaker(false); delay(650); onEnd()
                }
            }
        }
    }

    LaunchedEffect(activeCallId, remotePresent) {
        if (!incoming && activeCallId != null && !remotePresent && !ended) {
            delay(60_000)
            if (!remotePresent && !ended) {
                ended = true
                state = "لم يتم الرد"
                activeCallId?.let { runCatching { api.endCall(it) } }
                engine.disconnect(); CallForegroundService.stop(context); setSpeaker(false); onEnd()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            CallForegroundService.stop(context)
            setSpeaker(false)
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                engine.disconnect()
                // Do not convert an unanswered incoming invitation into an "ended" call merely
                // because its screen was closed. The backend timeout/decline path owns that state.
                if (!ended && accepted) activeCallId?.let { runCatching { api.endCall(it) } }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF071014)), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state, color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text(if (e2ee) "تشفير طرف-لطرف مباشر مفعّل" else "لن يبدأ الصوت أو الفيديو قبل قبول المكالمة", color = Color.White.copy(.72f), style = MaterialTheme.typography.labelSmall)
            }
            if (e2ee) Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF12362B)) { Text("🔒 مشفر", color = Color.White, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall) }
        }

        if (video && accepted) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp).background(Color(0xFF16232B), RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
                if (connected && remoteTrack != null) RemoteVideoSurface(engine, remoteTrack)
                else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Person, null, tint = Color.White.copy(.75f), modifier = Modifier.size(82.dp))
                    Spacer(Modifier.height(8.dp)); Text(if (connected) "بانتظار فيديو الطرف الآخر…" else "جاري إنشاء الاتصال…", color = Color.White.copy(.75f))
                }
            }
        } else {
            Spacer(Modifier.weight(1f)); Icon(if (video) Icons.Rounded.Videocam else Icons.Rounded.PhoneInTalk, null, modifier = Modifier.size(92.dp), tint = Color.White); Spacer(Modifier.height(18.dp)); Text(state, color = Color.White, style = MaterialTheme.typography.titleLarge); Spacer(Modifier.weight(1f))
        }

        if (error != null) Text(error!!, color = Color(0xFFFF6B6B), modifier = Modifier.padding(12.dp))

        if (incoming && !accepted) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 22.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledIconButton(onClick = {
                        scope.launch {
                            ended = true
                            incomingCallId?.let { runCatching { api.declineCall(it) } }
                            context.getSystemService(NotificationManager::class.java).cancel((incomingCallId ?: "").hashCode())
                            onEnd()
                        }
                    }, modifier = Modifier.size(68.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFE53935))) { Icon(Icons.Rounded.CallEnd, "رفض", tint = Color.White) }
                    Text("رفض", color = Color.White, modifier = Modifier.padding(top = 6.dp))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledIconButton(onClick = { requestAndConnect() }, modifier = Modifier.size(68.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF23A55A))) { Icon(if (video) Icons.Rounded.Videocam else Icons.Rounded.Call, "قبول", tint = Color.White) }
                    Text("قبول", color = Color.White, modifier = Modifier.padding(top = 6.dp))
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                CallCtl(if (mic) Icons.Rounded.Mic else Icons.Rounded.MicOff) { mic = !mic; scope.launch { engine.setMicrophoneEnabled(mic) } }
                if (video) CallCtl(if (camera) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff) { camera = !camera; scope.launch { engine.setCameraEnabled(camera) } }
                CallCtl(Icons.Rounded.BluetoothAudio) { engine.useAutomaticAudioRoute() }
                CallCtl(if (speaker) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff) { speaker = !speaker; setSpeaker(speaker) }
                FilledIconButton(onClick = { scope.launch { ended = true; activeCallId?.let { runCatching { api.endCall(it) } }; engine.disconnect(); CallForegroundService.stop(context); setSpeaker(false); onEnd() } }, modifier = Modifier.size(62.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFE53935))) { Icon(Icons.Rounded.CallEnd, null, tint = Color.White) }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

private fun friendlyCallError(t: Throwable): String {
    val raw = t.message.orEmpty()
    if (raw.contains("livekit_not_configured", true)) return "خدمة المكالمات غير متاحة حالياً"
    if (raw.contains("call_no_longer_available", true)) return "هذه المكالمة لم تعد متاحة"
    if (t is ApiException && t.status >= 500) return "تعذر الاتصال بخدمة المكالمات. حاول مرة أخرى"
    return "تعذر الاتصال. تحقق من صلاحية الميكروفون واتصال الإنترنت وحاول مجدداً."
}

@Composable
private fun RemoteVideoSurface(engine: LiveKitCallEngine, track: VideoTrack?) {
    val room = engine.currentRoom(); var bound by remember { mutableStateOf<VideoTrack?>(null) }; var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx -> SurfaceViewRenderer(ctx).also { view -> room?.initVideoRenderer(view); renderer = view; track?.addRenderer(view); bound = track } }, update = { view -> if (bound !== track) { bound?.removeRenderer(view); track?.addRenderer(view); bound = track } })
    DisposableEffect(Unit) { onDispose { renderer?.let { view -> bound?.removeRenderer(view); view.release() }; renderer = null; bound = null } }
}

@Composable
private fun CallCtl(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    FilledIconButton(onClick = onClick, modifier = Modifier.size(58.dp), shape = CircleShape, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF202C33))) { Icon(icon, null, tint = Color.White) }
}
