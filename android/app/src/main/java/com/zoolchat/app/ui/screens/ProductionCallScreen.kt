package com.zoolchat.app.ui.screens

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
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
import kotlin.math.roundToInt

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
    var remoteIsMain by remember { mutableStateOf(true) }
    var pipX by remember { mutableFloatStateOf(0f) }
    var pipY by remember { mutableFloatStateOf(0f) }
    val remoteTrack by engine.remoteVideoTrack.collectAsState()
    val localTrack by engine.localVideoTrack.collectAsState()
    val connected by engine.connected.collectAsState()
    val remotePresent by engine.remoteParticipantPresent.collectAsState()

    fun setSpeaker(enabled: Boolean) { engine.setSpeakerEnabled(enabled) }
    fun stopIncomingAlert() {
        val id = activeCallId ?: incomingCallId
        if (!id.isNullOrBlank()) context.getSystemService(NotificationManager::class.java).cancel(id.hashCode())
    }

    suspend fun connectAfterPermission() {
        if (connectionStarted || ended) return
        connectionStarted = true
        stopIncomingAlert()
        runCatching {
            val o = if (incomingCallId != null) api.acceptCall(incomingCallId) else api.createCall(requireNotNull(peerUserId), video)
            activeCallId = o.optString("callId").takeIf { it.isNotBlank() }
            val peerId = o.optString("peerUserId").takeIf { it.isNotBlank() } ?: peerUserId ?: error("peer_identity_missing")
            val callId = activeCallId ?: error("call_id_missing")
            val e2eeKey = runCatching { e2eeManager.callKey(callId, peerId) }.getOrNull()
            val rawServerUrl = o.getString("serverUrl").trim()
            val mediaServerUrl = when {
                rawServerUrl.startsWith("https://", true) -> "wss://" + rawServerUrl.substringAfter("://")
                rawServerUrl.startsWith("http://", true) -> "ws://" + rawServerUrl.substringAfter("://")
                else -> rawServerUrl
            }
            val cfg = ZoolCallConfig(
                roomName = callId,
                token = o.getString("participantToken"),
                serverUrl = mediaServerUrl,
                mode = if (o.optString("mode", if (video) "video" else "audio") == "video") ZoolMediaMode.VIDEO else ZoolMediaMode.AUDIO,
                e2eeKey = e2eeKey,
                startMicrophone = mic,
                startCamera = video && camera,
            )
            CallForegroundService.start(context, video)
            val firstConnect = engine.connect(cfg)
            if (firstConnect.isFailure) {
                engine.disconnect()
                delay(500)
                engine.connect(cfg).getOrThrow()
            }
            e2ee = !e2eeKey.isNullOrBlank()
            accepted = true
            state = if (incoming) "متصل" else "جاري الرنين…"
            setSpeaker(speaker)
            stopIncomingAlert()
        }.onFailure {
            engine.disconnect(); CallForegroundService.stop(context)
            activeCallId?.let { id -> runCatching { api.endCall(id) } }
            connectionStarted = false
            error = friendlyCallError(it)
            state = "تعذر الاتصال"
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val micGranted = grants[Manifest.permission.RECORD_AUDIO] == true
        val cameraGranted = !video || grants[Manifest.permission.CAMERA] == true
        // BLUETOOTH_CONNECT is optional for the call itself: when denied we still allow
        // microphone/camera calling and simply fall back to the normal Android audio route.
        if (micGranted && cameraGranted) scope.launch { connectAfterPermission() }
        else {
            connectionStarted = false
            error = "يلزم السماح بالميكروفون${if (video) " والكاميرا" else ""} لإجراء المكالمة"
        }
    }

    fun requestAndConnect() {
        error = null
        // Stop the incoming ringtone immediately when the user presses Answer; do not keep it
        // playing while Android is showing a microphone/camera permission dialog.
        if (incoming) stopIncomingAlert()
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (video) add(Manifest.permission.CAMERA)
            if (android.os.Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        permission.launch(permissions.toTypedArray())
    }

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
                "call.accepted" -> { stopIncomingAlert(); state = "متصل" }
                "call.declined" -> {
                    stopIncomingAlert(); ended = true; state = "تم رفض المكالمة"; engine.disconnect(); CallForegroundService.stop(context); setSpeaker(false); delay(650); onEnd()
                }
                "call.ended" -> {
                    stopIncomingAlert(); ended = true
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
                stopIncomingAlert(); engine.disconnect(); CallForegroundService.stop(context); setSpeaker(false); onEnd()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            stopIncomingAlert()
            CallForegroundService.stop(context)
            setSpeaker(false)
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                engine.disconnect()
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
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp).background(Color(0xFF16232B), RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center,
            ) {
                val mainTrack: VideoTrack? = if (remoteIsMain) remoteTrack ?: localTrack else localTrack ?: remoteTrack
                val pipTrack: VideoTrack? = if (remoteIsMain) localTrack else remoteTrack
                val mainIsLocal = mainTrack != null && mainTrack === localTrack
                val pipIsLocal = pipTrack != null && pipTrack === localTrack

                if (connected && mainTrack != null) {
                    VideoSurface(engine, mainTrack, mirror = mainIsLocal)
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Person, null, tint = Color.White.copy(.75f), modifier = Modifier.size(82.dp))
                        Spacer(Modifier.height(8.dp)); Text(if (connected) "بانتظار فيديو الطرف الآخر…" else "جاري إنشاء الاتصال…", color = Color.White.copy(.75f))
                    }
                }

                if (connected && pipTrack != null && mainTrack !== pipTrack) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .offset { IntOffset(pipX.roundToInt(), pipY.roundToInt()) }
                            .size(width = 118.dp, height = 170.dp)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    pipX += dragAmount.x
                                    pipY += dragAmount.y
                                }
                            }
                            .clickable {
                                remoteIsMain = !remoteIsMain
                                pipX = 0f
                                pipY = 0f
                            },
                        shape = RoundedCornerShape(18.dp),
                        shadowElevation = 8.dp,
                        color = Color.Black,
                    ) {
                        VideoSurface(engine, pipTrack, mirror = pipIsLocal)
                    }
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
                        stopIncomingAlert()
                        scope.launch {
                            ended = true
                            incomingCallId?.let { runCatching { api.declineCall(it) } }
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
                if (video && camera) CallCtl(Icons.Rounded.Cameraswitch) { engine.switchCamera() }
                CallCtl(Icons.Rounded.BluetoothAudio) { speaker = false; engine.useAutomaticAudioRoute() }
                CallCtl(if (speaker) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff) { speaker = !speaker; setSpeaker(speaker) }
                FilledIconButton(onClick = { scope.launch { ended = true; activeCallId?.let { runCatching { api.endCall(it) } }; stopIncomingAlert(); engine.disconnect(); CallForegroundService.stop(context); setSpeaker(false); onEnd() } }, modifier = Modifier.size(62.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFE53935))) { Icon(Icons.Rounded.CallEnd, null, tint = Color.White) }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

private fun friendlyCallError(t: Throwable): String {
    val raw = t.message.orEmpty()
    if (raw.contains("livekit_not_configured", true)) return "خدمة المكالمات غير متاحة حالياً"
    if (raw.contains("call_no_longer_available", true)) return "هذه المكالمة لم تعد متاحة"
    if (raw.contains("microphone_permission_required", true)) return "يلزم السماح بالميكروفون لإجراء المكالمة"
    if (raw.contains("microphone_publish_failed", true)) return "تعذر تشغيل الميكروفون. أغلق أي تطبيق يستخدمه وحاول مرة أخرى"
    if (raw.contains("camera_publish_failed", true)) return "تعذر تشغيل الكاميرا. أغلق أي تطبيق يستخدمها وحاول مرة أخرى"
    if (t is ApiException && t.status >= 500) return "تعذر الاتصال بخدمة المكالمات. حاول مرة أخرى"
    return "تعذر إنشاء الاتصال الصوتي. تحقق من الإنترنت وحاول مرة أخرى."
}

@Composable
private fun VideoSurface(engine: LiveKitCallEngine, track: VideoTrack?, mirror: Boolean) {
    val room = engine.currentRoom(); var bound by remember { mutableStateOf<VideoTrack?>(null) }; var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx -> SurfaceViewRenderer(ctx).also { view -> room?.initVideoRenderer(view); view.setMirror(mirror); renderer = view; track?.addRenderer(view); bound = track } },
        update = { view -> view.setMirror(mirror); if (bound !== track) { bound?.removeRenderer(view); track?.addRenderer(view); bound = track } },
    )
    DisposableEffect(Unit) { onDispose { renderer?.let { view -> bound?.removeRenderer(view); view.release() }; renderer = null; bound = null } }
}

@Composable
private fun CallCtl(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    FilledIconButton(onClick = onClick, modifier = Modifier.size(58.dp), shape = CircleShape, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF202C33))) { Icon(icon, null, tint = Color.White) }
}
