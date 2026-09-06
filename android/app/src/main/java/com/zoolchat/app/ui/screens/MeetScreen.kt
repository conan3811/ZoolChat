package com.zoolchat.app.ui.screens

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zoolchat.app.calling.CallForegroundService
import com.zoolchat.app.calling.LiveKitCallEngine
import com.zoolchat.app.calling.ZoolCallConfig
import com.zoolchat.app.calling.ZoolMediaMode
import com.zoolchat.app.network.ApiClient
import com.zoolchat.app.realtime.RealtimeEventBus
import com.zoolchat.app.ui.components.ZoolHeader
import com.zoolchat.app.ui.i18n.t
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

private enum class MeetStage { HOME, PREJOIN, WAITING, ROOM, CREATED_FOR_LATER }

@Composable
fun MeetScreen(
    api: ApiClient,
    myUserId: String,
    myDisplayName: String,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { LiveKitCallEngine(context.applicationContext) }
    var showInvites by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf(MeetStage.HOME) }
    var code by remember { mutableStateOf("") }
    var createdCode by remember { mutableStateOf("") }
    var meetingId by remember { mutableStateOf<String?>(null) }
    var meetingTitle by remember { mutableStateOf("Zool Meet") }
    var hostUserId by remember { mutableStateOf<String?>(null) }
    var mic by remember { mutableStateOf(true) }
    var camera by remember { mutableStateOf(true) }
    var screenSharing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var participants by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var pendingJoinCode by remember { mutableStateOf<String?>(null) }
    val connected by engine.connected.collectAsState()
    val remotePresent by engine.remoteParticipantPresent.collectAsState()

    suspend fun connectFromResponse(o: JSONObject) {
        val id = o.getString("meetingId")
        meetingId = id
        createdCode = o.optString("code", createdCode)
        meetingTitle = o.optString("title", meetingTitle)
        hostUserId = o.optString("hostUserId").takeIf { it.isNotBlank() }
        val cfg = ZoolCallConfig(
            roomName = id,
            token = o.getString("participantToken"),
            serverUrl = o.getString("serverUrl"),
            mode = ZoolMediaMode.VIDEO,
            e2eeKey = null,
            startMicrophone = mic,
            startCamera = camera,
        )
        CallForegroundService.start(context, true)
        try { engine.connect(cfg).getOrThrow() } catch(e:Exception) { engine.disconnect();CallForegroundService.stop(context);throw e }
        stage = MeetStage.ROOM
    }

    suspend fun actuallyJoin(joinCode: String) {
        busy = true; error = null
        runCatching { api.joinMeeting(joinCode) }
            .onSuccess { o ->
                meetingId = o.optString("meetingId").takeIf { it.isNotBlank() }
                createdCode = o.optString("code", joinCode)
                meetingTitle = o.optString("title", "Zool Meet")
                hostUserId = o.optString("hostUserId").takeIf { it.isNotBlank() }
                if (o.optString("status") == "waiting") stage = MeetStage.WAITING else connectFromResponse(o)
            }
            .onFailure { error = friendlyMeetError(it) }
        busy = false
    }

    val mediaPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val denied = grants.any { !it.value }
        if (denied) { error = t("يمكنك إيقاف الكاميرا أو الميكروفون ثم المحاولة، أو منح الأذونات المطلوبة"); return@rememberLauncherForActivityResult }
        pendingJoinCode?.let { pending -> pendingJoinCode = null; scope.launch { actuallyJoin(pending) } }
    }

    fun requestJoin(joinCode: String) {
        val clean = joinCode.trim().lowercase(); if (clean.isBlank()) return
        pendingJoinCode = clean
        val permissions = buildList { if (mic) add(Manifest.permission.RECORD_AUDIO); if (camera) add(Manifest.permission.CAMERA) }.toTypedArray()
        if (permissions.isEmpty()) scope.launch { pendingJoinCode = null; actuallyJoin(clean) } else mediaPermission.launch(permissions)
    }

    val screenShareLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            scope.launch {
                runCatching { engine.setScreenShareEnabled(true, result.data) }
                    .onSuccess { screenSharing = true }
                    .onFailure { error = t("تعذر بدء مشاركة الشاشة") }
            }
        }
    }

    fun startScreenShare() {
        if (screenSharing) { scope.launch { runCatching { engine.setScreenShareEnabled(false) }; screenSharing = false }; return }
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenShareLauncher.launch(manager.createScreenCaptureIntent())
    }

    LaunchedEffect(stage, meetingId) {
        if (stage == MeetStage.WAITING) {
            while (stage == MeetStage.WAITING && meetingId != null) {
                delay(2_000)
                val id = meetingId ?: break
                runCatching { api.meetingJoinStatus(id) }.onSuccess { o ->
                    when (o.optString("status")) {
                        "admitted" -> runCatching { connectFromResponse(o) }.onFailure { error = friendlyMeetError(it) }
                        "denied" -> { error = t("رفض المضيف طلب الانضمام"); stage = MeetStage.PREJOIN }
                        "ended" -> { error = t("انتهى الاجتماع"); stage = MeetStage.HOME }
                    }
                }
            }
        }
    }

    LaunchedEffect(stage, meetingId) {
        if (stage == MeetStage.ROOM) {
            while (stage == MeetStage.ROOM && meetingId != null) {
                val id = meetingId ?: break
                runCatching { api.meetingInfo(id) }.onSuccess { info ->
                    val arr = info.optJSONArray("participants") ?: org.json.JSONArray()
                    participants = (0 until arr.length()).map { arr.getJSONObject(it) }
                    hostUserId = info.optString("hostUserId").takeIf { it.isNotBlank() }
                    meetingTitle = info.optString("title", meetingTitle)
                    createdCode = info.optString("code", createdCode)
                    if (info.optString("status") == "ended") { scope.launch { engine.disconnect() }; CallForegroundService.stop(context); stage = MeetStage.HOME; error = t("أنهى المضيف الاجتماع") }
                }
                delay(3_000)
            }
        }
    }

    LaunchedEffect(Unit) {
        RealtimeEventBus.events.collect { e ->
            val type = e.optString("type"); val id = e.optJSONObject("data")?.optString("meetingId")
            if (id.isNullOrBlank() || id != meetingId) return@collect
            if (type == "meeting.ended") { engine.disconnect(); CallForegroundService.stop(context); stage = MeetStage.HOME; error = t("أنهى المضيف الاجتماع") }
        }
    }

    DisposableEffect(Unit) { onDispose { CallForegroundService.stop(context); scope.launch { engine.disconnect() } } }

    if(showInvites) MeetInvitations(api,meetingId?.takeIf { hostUserId==myUserId },createdCode,{showInvites=false},{joinCode->showInvites=false;code=joinCode;stage=MeetStage.PREJOIN})
    Column(Modifier.fillMaxSize()) {
    TextButton(onClick={showInvites=true}) { Text(t("دعوات ZoolMeet")) }
    Box(Modifier.weight(1f)) {
    when (stage) {
        MeetStage.HOME -> MeetHome(
            code = code,
            onCodeChange = { code = it },
            busy = busy,
            error = error,
            onSettings = onOpenSettings,
            onJoin = { if (code.isNotBlank()) { error = null; stage = MeetStage.PREJOIN } },
            onInstant = {
                busy = true; error = null
                scope.launch {
                    runCatching { api.createMeeting("Zool Meet", true) }
                        .onSuccess { m -> code = m.getString("code"); createdCode = code; meetingId = m.getString("id"); hostUserId = m.getString("hostUserId"); stage = MeetStage.PREJOIN }
                        .onFailure { error = friendlyMeetError(it) }
                    busy = false
                }
            },
            onCreateLater = {
                busy = true; error = null
                scope.launch {
                    runCatching { api.createMeeting("Zool Meet", true) }
                        .onSuccess { m -> createdCode = m.getString("code"); meetingId = m.getString("id"); hostUserId = m.getString("hostUserId"); stage = MeetStage.CREATED_FOR_LATER }
                        .onFailure { error = friendlyMeetError(it) }
                    busy = false
                }
            },
        )
        MeetStage.PREJOIN -> MeetPreJoin(code.ifBlank { createdCode }, mic, camera, error, busy, { mic = !mic }, { camera = !camera }, { stage = MeetStage.HOME }, { requestJoin(code.ifBlank { createdCode }) })
        MeetStage.WAITING -> MeetWaiting(meetingTitle, { scope.launch { meetingId?.let { runCatching { api.leaveMeeting(it) } }; stage = MeetStage.HOME } })
        MeetStage.CREATED_FOR_LATER -> MeetCreatedLater(createdCode, context, { stage = MeetStage.HOME }, { code = createdCode; stage = MeetStage.PREJOIN })
        MeetStage.ROOM -> MeetRoom(
            title = meetingTitle,
            code = createdCode,
            mic = mic,
            camera = camera,
            screenSharing = screenSharing,
            participants = participants,
            isHost = hostUserId == myUserId,
            connected = connected,
            remotePresent = remotePresent,
            onMic = { mic = !mic; scope.launch { engine.setMicrophoneEnabled(mic) } },
            onCamera = { camera = !camera; scope.launch { engine.setCameraEnabled(camera) } },
            onScreenShare = ::startScreenShare,
            onAdmit = { uid -> scope.launch { meetingId?.let { api.admitMeetingParticipant(it, uid) } } },
            onDeny = { uid -> scope.launch { meetingId?.let { api.denyMeetingParticipant(it, uid) } } },
            onLeave = { endAll -> scope.launch { meetingId?.let { if (endAll && hostUserId == myUserId) runCatching { api.endMeeting(it) } else runCatching { api.leaveMeeting(it) } }; engine.disconnect(); CallForegroundService.stop(context); stage = MeetStage.HOME } },
        )
    }
}
    }
    }

@Composable private fun MeetHome(code:String,onCodeChange:(String)->Unit,busy:Boolean,error:String?,onSettings:()->Unit,onJoin:()->Unit,onInstant:()->Unit,onCreateLater:()->Unit){
    Column(Modifier.fillMaxSize()){
        ZoolHeader("ZoolMeet",t("اجتماعات فيديو مع غرفة انتظار"),onSettings=onSettings)
        Card(Modifier.fillMaxWidth().padding(16.dp),shape=RoundedCornerShape(24.dp)){Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text(t("ابدأ اجتماعًا أو انضم برمز"),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
            Button(onClick=onInstant,enabled=!busy,modifier=Modifier.fillMaxWidth()){Icon(Icons.Rounded.VideoCall,null);Spacer(Modifier.width(8.dp));Text(t("بدء اجتماع فوري"))}
            OutlinedButton(onClick=onCreateLater,enabled=!busy,modifier=Modifier.fillMaxWidth()){Icon(Icons.Rounded.Link,null);Spacer(Modifier.width(8.dp));Text(t("إنشاء اجتماع لاستخدامه لاحقًا"))}
            HorizontalDivider()
            OutlinedTextField(code,onCodeChange,Modifier.fillMaxWidth(),placeholder={Text(t("أدخل رمز الاجتماع مثل abc-defg-hij"))},leadingIcon={Icon(Icons.Rounded.Keyboard,null)},singleLine=true)
            Button(onClick=onJoin,enabled=code.isNotBlank()&&!busy,modifier=Modifier.fillMaxWidth()){Text(t("انضمام"))}
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth());if(error!=null)Text(error,color=MaterialTheme.colorScheme.error)
        }}
        Spacer(Modifier.weight(1f));Text(t("قبل دخول أي اجتماع يمكنك اختيار الميكروفون والكاميرا. وإذا فعّل المضيف غرفة الانتظار فلن تدخل قبل موافقته."),modifier=Modifier.padding(20.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun MeetPreJoin(code:String,mic:Boolean,camera:Boolean,error:String?,busy:Boolean,onMic:()->Unit,onCamera:()->Unit,onBack:()->Unit,onJoin:()->Unit){
    Column(Modifier.fillMaxSize().padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){IconButton(onClick=onBack){Icon(Icons.Rounded.ArrowBack,t("رجوع"))};Text(t("جاهز للانضمام؟"),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)}
        Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF172126),RoundedCornerShape(24.dp)),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally){Icon(if(camera)Icons.Rounded.Videocam else Icons.Rounded.VideocamOff,null,tint=Color.White,modifier=Modifier.size(68.dp));Text(if(camera)t("الكاميرا ستبدأ عند الانضمام") else t("الكاميرا متوقفة"),color=Color.White.copy(.8f))}}
        Text(code,style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(12.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(14.dp)){FilledTonalButton(onClick=onMic){Icon(if(mic)Icons.Rounded.Mic else Icons.Rounded.MicOff,null);Spacer(Modifier.width(6.dp));Text(if(mic)t("الميكروفون يعمل") else t("الميكروفون متوقف"))};FilledTonalButton(onClick=onCamera){Icon(if(camera)Icons.Rounded.Videocam else Icons.Rounded.VideocamOff,null)}}
        if(error!=null)Text(error,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(8.dp))
        Button(onClick=onJoin,enabled=!busy,modifier=Modifier.fillMaxWidth().padding(top=12.dp).height(52.dp)){if(busy)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)else Text(t("طلب الانضمام"))}
    }
}

@Composable private fun MeetWaiting(title:String,onCancel:()->Unit){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally){CircularProgressIndicator();Spacer(Modifier.height(18.dp));Text(title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text(t("في انتظار موافقة المضيف…"),color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(8.dp));OutlinedButton(onClick=onCancel){Text(t("إلغاء"))}}}}

@Composable private fun MeetCreatedLater(code:String,context:Context,onDone:()->Unit,onJoin:()->Unit){Column(Modifier.fillMaxSize().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Icon(Icons.Rounded.CheckCircle,null,modifier=Modifier.size(70.dp),tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.height(16.dp));Text(t("تم إنشاء الاجتماع"),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text(code,style=MaterialTheme.typography.headlineMedium,modifier=Modifier.padding(18.dp));Button(onClick={val cm=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager;cm.setPrimaryClip(ClipData.newPlainText("Zool Meet",code))}){Icon(Icons.Rounded.ContentCopy,null);Spacer(Modifier.width(6.dp));Text(t("نسخ الرمز"))};TextButton(onClick=onJoin){Text(t("بدء الاجتماع الآن"))};TextButton(onClick=onDone){Text(t("تم"))}}}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun MeetRoom(title:String,code:String,mic:Boolean,camera:Boolean,screenSharing:Boolean,participants:List<JSONObject>,isHost:Boolean,connected:Boolean,remotePresent:Boolean,onMic:()->Unit,onCamera:()->Unit,onScreenShare:()->Unit,onAdmit:(String)->Unit,onDeny:(String)->Unit,onLeave:(Boolean)->Unit){
    var showPeople by remember{mutableStateOf(false)};var showLeave by remember{mutableStateOf(false)}
    val waiting=participants.filter{it.optString("status")=="waiting"};val admitted=participants.filter{it.optString("status")=="admitted"}
    if(showLeave)AlertDialog(onDismissRequest={showLeave=false},title={Text(t("مغادرة الاجتماع"))},text={Text(if(isHost)t("يمكنك المغادرة فقط أو إنهاء الاجتماع للجميع") else t("هل تريد مغادرة الاجتماع؟"))},confirmButton={if(isHost)TextButton(onClick={showLeave=false;onLeave(true)}){Text(t("إنهاء للجميع"),color=MaterialTheme.colorScheme.error)}else TextButton(onClick={showLeave=false;onLeave(false)}){Text(t("مغادرة"))}},dismissButton={if(isHost)TextButton(onClick={showLeave=false;onLeave(false)}){Text(t("مغادرة فقط"))}else TextButton(onClick={showLeave=false}){Text(t("إلغاء"))}})
    if(showPeople)ModalBottomSheet(onDismissRequest={showPeople=false}){LazyColumn(Modifier.fillMaxWidth().heightIn(max=520.dp),contentPadding=PaddingValues(16.dp)){item{Text("${t("المشاركون")} (${admitted.size})",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)};if(isHost&&waiting.isNotEmpty()){item{Text(t("في غرفة الانتظار"),fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=14.dp))};items(waiting,key={it.optString("userId")}){p->Row(Modifier.fillMaxWidth().padding(vertical=7.dp),verticalAlignment=Alignment.CenterVertically){Text(p.optString("displayName"),Modifier.weight(1f));TextButton(onClick={onDeny(p.getString("userId"))}){Text(t("رفض"),color=MaterialTheme.colorScheme.error)};Button(onClick={onAdmit(p.getString("userId"))}){Text(t("قبول"))}}}};items(admitted,key={it.optString("userId")}){p->ListItem(headlineContent={Text(p.optString("displayName"))},supportingContent={Text(if(p.optString("role")=="host")t("المضيف") else t("مشارك"))},leadingContent={Icon(Icons.Rounded.AccountCircle,null)})};item{Spacer(Modifier.height(20.dp))}}}
    Column(Modifier.fillMaxSize().background(Color(0xFF0B141A))){
        Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(title,color=Color.White,fontWeight=FontWeight.Bold);Text("$code • ${if(connected)t("متصل") else t("جاري الاتصال")}",color=Color.White.copy(.7f),style=MaterialTheme.typography.labelSmall)};Badge{Text(admitted.size.toString())};IconButton(onClick={showPeople=true}){Icon(Icons.Rounded.People,null,tint=Color.White)}}
        Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp).background(Color(0xFF172126),RoundedCornerShape(24.dp)),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally){Icon(if(remotePresent)Icons.Rounded.Groups else Icons.Rounded.Person,null,tint=Color.White.copy(.85f),modifier=Modifier.size(86.dp));Text(if(remotePresent)t("المشاركون متصلون") else t("بانتظار المشاركين"),color=Color.White.copy(.75f));if(screenSharing)Text(t("أنت تشارك الشاشة الآن"),color=Color(0xFF66D9A5),modifier=Modifier.padding(8.dp))}}
        if(isHost&&waiting.isNotEmpty())AssistChip(onClick={showPeople=true},label={Text("${waiting.size} ${t("بانتظار الموافقة")}")},leadingIcon={Icon(Icons.Rounded.PersonAdd,null)},modifier=Modifier.align(Alignment.CenterHorizontally))
        Row(Modifier.fillMaxWidth().padding(18.dp).navigationBarsPadding(),horizontalArrangement=Arrangement.SpaceEvenly){MeetCtl(if(mic)Icons.Rounded.Mic else Icons.Rounded.MicOff,onMic);MeetCtl(if(camera)Icons.Rounded.Videocam else Icons.Rounded.VideocamOff,onCamera);MeetCtl(if(screenSharing)Icons.Rounded.StopScreenShare else Icons.Rounded.ScreenShare,onScreenShare);FilledIconButton(onClick={showLeave=true},modifier=Modifier.size(60.dp),colors=IconButtonDefaults.filledIconButtonColors(containerColor=Color(0xFFE53935))){Icon(Icons.Rounded.CallEnd,null,tint=Color.White)}}
    }
}

@Composable private fun MeetCtl(icon:androidx.compose.ui.graphics.vector.ImageVector,onClick:()->Unit){FilledIconButton(onClick=onClick,modifier=Modifier.size(56.dp),colors=IconButtonDefaults.filledIconButtonColors(containerColor=Color(0xFF202C33))){Icon(icon,null,tint=Color.White)}}
private fun friendlyMeetError(t:Throwable):String{val raw=t.message.orEmpty();return when{raw.contains("livekit_not_configured",true)->"خدمة الاجتماعات غير مهيأة على الخادم";raw.contains("meeting_not_found",true)->"رمز الاجتماع غير صحيح";raw.contains("meeting_ended",true)->"انتهى هذا الاجتماع";else->if(raw.isBlank())"تعذر الاتصال بالاجتماع" else raw.take(180)}}
