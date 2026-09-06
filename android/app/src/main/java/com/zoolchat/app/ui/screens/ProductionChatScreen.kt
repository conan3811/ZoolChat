package com.zoolchat.app.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zoolchat.app.crypto.E2eeManager
import com.zoolchat.app.data.RemoteMessage
import com.zoolchat.app.data.RemotePresence
import com.zoolchat.app.media.MediaRepository
import com.zoolchat.app.media.VoiceNoteRecorder
import com.zoolchat.app.network.ApiClient
import com.zoolchat.app.ui.components.Avatar
import com.zoolchat.app.ui.i18n.t
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

private val Blue = Color(0xFF53BDEB)
private val Bright = Color(0xFF25D366)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductionChatScreen(
    api: ApiClient,
    e2ee: E2eeManager,
    peerUserId: String?,
    chatId: String,
    title: String,
    myUserId: String,
    messages: List<RemoteMessage>,
    hasMore: Boolean,
    onBack: () -> Unit,
    onLoad: () -> Unit,
    onLoadOlder: () -> Unit,
    onSend: (String) -> Unit,
    onSendMedia: (Uri, String, String) -> Unit,
    onRetry: (String) -> Unit,
    onMarkRead: () -> Unit,
    peerPresence: RemotePresence?,
    peerTyping: Boolean,
    onRefreshPresence: () -> Unit,
    onTypingChanged: (Boolean) -> Unit,
    onVoiceCall: () -> Unit,
    onVideoCall: () -> Unit,
    isGroup: Boolean = false,
    onOpenGroupInfo: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mediaRepo = remember(api,e2ee,peerUserId) { MediaRepository(context.applicationContext, api, e2ee, peerUserId) }
    var text by remember { mutableStateOf("") }
    var showAttachments by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var searchMode by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    val recorder = remember { VoiceNoteRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    var showSecurity by remember { mutableStateOf(false) }
    var peerFingerprint by remember { mutableStateOf<String?>(null) }
    var typingSent by remember { mutableStateOf(false) }

    LaunchedEffect(peerUserId) {
        if (peerUserId != null) {
            while (true) { onRefreshPresence(); delay(15_000) }
        }
    }
    LaunchedEffect(text) {
        if (text.isBlank()) {
            if (typingSent) { onTypingChanged(false); typingSent = false }
        } else {
            delay(450)
            if (!typingSent) { onTypingChanged(true); typingSent = true }
            delay(2_500)
            if (typingSent) { onTypingChanged(false); typingSent = false }
        }
    }

    fun fileInfo(uri: Uri, fallbackMime: String): Pair<String, String> {
        var name = "attachment"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0) ?: name
        }
        val mime = context.contentResolver.getType(uri) ?: fallbackMime
        return name to mime
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { val (n,m)=fileInfo(it,"image/*"); onSendMedia(it,m,n) } }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { val (n,m)=fileInfo(it,"video/*"); onSendMedia(it,m,n) } }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { val (n,m)=fileInfo(it,"application/octet-stream"); onSendMedia(it,m,n) } }

    DisposableEffect(chatId) {
        com.zoolchat.app.notifications.ConversationVisibility.chatId = chatId
        com.zoolchat.app.notifications.NotificationHelper.clearMessage(context, chatId)
        onDispose { if (com.zoolchat.app.notifications.ConversationVisibility.chatId == chatId) com.zoolchat.app.notifications.ConversationVisibility.chatId = null }
    }
    LaunchedEffect(chatId) { onLoad() }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            list.animateScrollToItem(messages.lastIndex + if (hasMore) 1 else 0)
            onMarkRead()
        }
    }
    DisposableEffect(Unit) { onDispose { if (recorder.isRecording) recorder.cancel(); if (typingSent) onTypingChanged(false) } }

    if (showAttachments) {
        ModalBottomSheet(onDismissRequest = { showAttachments = false }, containerColor = MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                MediaOption(Icons.Rounded.Image, t("صورة")) { showAttachments=false; imagePicker.launch("image/*") }
                MediaOption(Icons.Rounded.Videocam, t("فيديو")) { showAttachments=false; videoPicker.launch("video/*") }
                MediaOption(Icons.Rounded.Description, t("ملف")) { showAttachments=false; documentPicker.launch("*/*") }
            }
            Spacer(Modifier.height(22.dp))
        }
    }

    if(showSecurity){
        LaunchedEffect(showSecurity,peerUserId){ if(peerUserId!=null) peerFingerprint=runCatching{e2ee.peerFingerprint(peerUserId)}.getOrNull() }
        AlertDialog(onDismissRequest={showSecurity=false},title={Text(t("التشفير ورمز الأمان"))},text={Column{Text(t("هذه المحادثة تستخدم E2EE beta مباشر بين الجهازين."));Spacer(Modifier.height(10.dp));Text(t("بصمة جهازك:"),fontWeight=FontWeight.Bold);Text(e2ee.localFingerprint(),style=MaterialTheme.typography.bodySmall);Spacer(Modifier.height(10.dp));Text(t("بصمة الطرف الآخر:"),fontWeight=FontWeight.Bold);Text(peerFingerprint?:t("جاري التحميل…"),style=MaterialTheme.typography.bodySmall);if(peerUserId!=null&&e2ee.peerKeyChanged(peerUserId)){Spacer(Modifier.height(10.dp));Text(t("⚠ تغيّر مفتاح الطرف الآخر. تحقق من البصمة قبل متابعة المحادثة الحساسة."),color=MaterialTheme.colorScheme.error)}}},confirmButton={TextButton(onClick={peerUserId?.let{e2ee.acknowledgePeerKey(it)};showSecurity=false}){Text(t("تم"))}})
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().height(60.dp).background(MaterialTheme.colorScheme.background).padding(horizontal=4.dp), verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onBack) { Icon(Icons.Rounded.ArrowBack,"رجوع",tint=MaterialTheme.colorScheme.onSurface) }
            Avatar(title,38.dp)
            Spacer(Modifier.width(8.dp))
            Column(modifier=Modifier.weight(1f)) {
                Text(title,color=MaterialTheme.colorScheme.onSurface,fontWeight=FontWeight.Bold,maxLines=1)
                val statusText = when {
                    peerTyping -> t("يكتب الآن…")
                    peerPresence?.online == true -> t("متصل الآن")
                    peerPresence?.lastSeenAt != null -> t("آخر ظهور ${peerPresence.lastSeenAt.substringAfter('T').take(5)}")
                    else -> ""
                }
                if (statusText.isNotBlank()) Text(statusText,color=if(peerTyping||peerPresence?.online==true) Bright else MaterialTheme.colorScheme.onSurface.copy(alpha=.68f),style=MaterialTheme.typography.labelSmall,maxLines=1)
            }
            if (isGroup) {
                IconButton(onClick=onOpenGroupInfo) { Icon(Icons.Rounded.Group,"معلومات المجموعة",tint=MaterialTheme.colorScheme.onSurface) }
            } else {
                IconButton(onClick=onVideoCall) { Icon(Icons.Rounded.Videocam,"فيديو",tint=MaterialTheme.colorScheme.onSurface) }
                IconButton(onClick=onVoiceCall) { Icon(Icons.Rounded.PhoneInTalk,"صوت",tint=MaterialTheme.colorScheme.onSurface) }
            }
            Box {
                IconButton(onClick={showMore=true}) { Icon(Icons.Rounded.MoreVert,"المزيد",tint=MaterialTheme.colorScheme.onSurface) }
                DropdownMenu(expanded=showMore,onDismissRequest={showMore=false},modifier=Modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
                    if (isGroup) ChatMenuItem(Icons.Rounded.Group,t("معلومات المجموعة")) { showMore=false; onOpenGroupInfo() }
                    ChatMenuItem(Icons.Rounded.Search,t("بحث")) { showMore=false; searchMode=true }
                    ChatMenuItem(Icons.Rounded.PermMedia,t("الوسائط والروابط والمستندات")) { showMore=false }
                    ChatMenuItem(Icons.Rounded.NotificationsOff,t("كتم الإشعارات")) { showMore=false }
                    ChatMenuItem(Icons.Rounded.Timer,t("الرسائل المختفية")) { showMore=false }
                    ChatMenuItem(Icons.Rounded.VerifiedUser,t("التشفير ورمز الأمان")) { showMore=false; showSecurity=true }
                    HorizontalDivider(color=MaterialTheme.colorScheme.onSurface.copy(alpha=.15f))
                    ChatMenuItem(Icons.Rounded.Report,t("إبلاغ")) { showMore=false }
                    ChatMenuItem(Icons.Rounded.Block,t("حظر")) { showMore=false }
                    ChatMenuItem(Icons.Rounded.DeleteSweep,t("مسح الدردشة")) { showMore=false }
                    ChatMenuItem(Icons.Rounded.Share,t("تصدير الدردشة")) { showMore=false }
                }
            }
        }

        if (searchMode) {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal=8.dp), verticalAlignment=Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search,null,tint=MaterialTheme.colorScheme.onSurface)
                TextField(
                    value=searchQuery,
                    onValueChange={searchQuery=it},
                    modifier=Modifier.weight(1f),
                    placeholder={Text(t("بحث داخل المحادثة"),color=MaterialTheme.colorScheme.onSurface)},
                    colors=TextFieldDefaults.colors(focusedTextColor=MaterialTheme.colorScheme.onSurface,unfocusedTextColor=MaterialTheme.colorScheme.onSurface,cursorColor=MaterialTheme.colorScheme.onSurface,focusedContainerColor=Color.Transparent,unfocusedContainerColor=Color.Transparent,focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent),
                    singleLine=true,
                )
                IconButton(onClick={searchMode=false;searchQuery=""}) { Icon(Icons.Rounded.Close,null,tint=MaterialTheme.colorScheme.onSurface) }
            }
        }

        val visibleMessages = remember(messages, searchQuery, searchMode) {
            if (!searchMode || searchQuery.isBlank()) messages else messages.filter { it.body.contains(searchQuery,true) || (it.mediaName?.contains(searchQuery,true)==true) }
        }
        LazyColumn(
            state=list,
            modifier=Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.background),
            contentPadding=PaddingValues(10.dp),
            verticalArrangement=Arrangement.spacedBy(5.dp),
        ) {
            if (hasMore && !searchMode) {
                item(key="load-older") {
                    Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
                        TextButton(onClick=onLoadOlder) { Text(t("تحميل رسائل أقدم"),color=MaterialTheme.colorScheme.onSurface) }
                    }
                }
            }
            items(visibleMessages,key={it.id}) { m ->
                val mine=m.senderId==myUserId
                Row(Modifier.fillMaxWidth(),horizontalArrangement=if(mine)Arrangement.Start else Arrangement.End) {
                    Surface(shape=RoundedCornerShape(14.dp),color=if(mine)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,modifier=Modifier.widthIn(max=310.dp)) {
                        Column(Modifier.padding(horizontal=10.dp,vertical=7.dp)) {
                            if (m.deleted) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.Block, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha=.75f), modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(t("تم حذف هذه الرسالة"), color = MaterialTheme.colorScheme.onSurface.copy(alpha=.78f), style = MaterialTheme.typography.bodyMedium)
                                }
                            } else if (m.type=="call") CallEventBubble(m, mine)
                            else if (m.type=="text") Text(m.body,color=if(m.decryptionFailed) Color(0xFFFF8A80) else MaterialTheme.colorScheme.onSurface)
                            else MediaBubble(m,mediaRepo)
                            Row(Modifier.align(Alignment.End),verticalAlignment=Alignment.CenterVertically) {
                                if (m.editedAt != null && !m.deleted) {
                                    Text(t("تم التعديل • "), color=MaterialTheme.colorScheme.onSurface.copy(alpha=.6f), style=MaterialTheme.typography.labelSmall)
                                }
                                Text(m.createdAt.substringAfter('T').take(5),color=MaterialTheme.colorScheme.onSurface.copy(alpha=.75f),style=MaterialTheme.typography.labelSmall)
                                if(mine){Spacer(Modifier.width(4.dp));Receipt(m.status)}
                            }
                            if (m.status == "failed") {
                                m.clientId?.takeIf { it.isNotBlank() }?.let { clientId ->
                                    TextButton(onClick = { onRetry(clientId) }, contentPadding = PaddingValues(0.dp)) {
                                        Text(t("إعادة المحاولة"), color = MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if(visibleMessages.isEmpty()) item { Box(Modifier.fillParentMaxSize(),contentAlignment=Alignment.Center) { Text(if(searchMode)t("لا توجد نتائج") else t("ابدأ المحادثة"),color=MaterialTheme.colorScheme.onSurface.copy(alpha=.7f)) } }
        }

        if(recording) Text(t("● جاري تسجيل رسالة صوتية — اضغط زر الميكروفون للإرسال"),color=Color.White,modifier=Modifier.fillMaxWidth().background(Color(0xFF7A1F1F)).padding(8.dp),style=MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().padding(7.dp).navigationBarsPadding(),verticalAlignment=Alignment.Bottom) {
            Surface(Modifier.weight(1f),shape=RoundedCornerShape(26.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick={}) { Icon(Icons.Rounded.Mood,null,tint=MaterialTheme.colorScheme.onSurface) }
                    TextField(
                        text,{text=it},Modifier.weight(1f),
                        placeholder={Text(t("رسالة"),color=MaterialTheme.colorScheme.onSurface)},
                        colors=TextFieldDefaults.colors(focusedTextColor=MaterialTheme.colorScheme.onSurface,unfocusedTextColor=MaterialTheme.colorScheme.onSurface,cursorColor=MaterialTheme.colorScheme.onSurface,focusedContainerColor=Color.Transparent,unfocusedContainerColor=Color.Transparent,focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent),
                        maxLines=4,
                    )
                    IconButton(onClick={showAttachments=true}) { Icon(Icons.Rounded.AttachFile,null,tint=MaterialTheme.colorScheme.onSurface) }
                    IconButton(onClick={imagePicker.launch("image/*")}) { Icon(Icons.Rounded.CameraAlt,null,tint=MaterialTheme.colorScheme.onSurface) }
                }
            }
            Spacer(Modifier.width(6.dp))
            FilledIconButton(
                onClick={
                    if(text.isNotBlank()){onSend(text);text="";if(typingSent){onTypingChanged(false);typingSent=false}}
                    else if(!recording){recorder.start().onSuccess{recording=true}}
                    else{recorder.stop().onSuccess{file->recording=false;onSendMedia(Uri.fromFile(file),"audio/mp4","رسالة صوتية.m4a")}.onFailure{recording=false}}
                },
                modifier=Modifier.size(50.dp),
                colors=IconButtonDefaults.filledIconButtonColors(containerColor=if(recording)Color(0xFFE53935) else Bright),
            ) { Icon(if(text.isBlank())if(recording)Icons.Rounded.Stop else Icons.Rounded.Mic else Icons.Rounded.Send,null,tint=Color.White) }
        }
    }
}

@Composable
private fun ChatMenuItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text={Text(label,color=MaterialTheme.colorScheme.onSurface)},
        onClick=onClick,
        leadingIcon={Icon(icon,null,tint=MaterialTheme.colorScheme.onSurface)},
    )
}

@Composable
private fun Receipt(status:String){
    when(status){
        "pending"->Icon(Icons.Rounded.Schedule,null,tint=MaterialTheme.colorScheme.onSurface.copy(.7f),modifier=Modifier.size(13.dp))
        "failed"->Icon(Icons.Rounded.ErrorOutline,null,tint=Color(0xFFFF6B6B),modifier=Modifier.size(13.dp))
        "read"->Text("✓✓",color=Blue,style=MaterialTheme.typography.labelSmall)
        "delivered"->Text("✓✓",color=MaterialTheme.colorScheme.onSurface.copy(.72f),style=MaterialTheme.typography.labelSmall)
        else->Text("✓",color=MaterialTheme.colorScheme.onSurface.copy(.72f),style=MaterialTheme.typography.labelSmall)
    }
}


@Composable
private fun CallEventBubble(m: RemoteMessage, mine: Boolean) {
    val o = remember(m.body) { runCatching { org.json.JSONObject(m.body) }.getOrDefault(org.json.JSONObject()) }
    val mode = o.optString("mode", "audio")
    val status = o.optString("status", "ended")
    val answered = o.optString("answeredAt").takeIf { it.isNotBlank() && it != "null" }
    val ended = o.optString("endedAt").takeIf { it.isNotBlank() && it != "null" }
    val title = when {
        status == "missed" && !mine -> t("مكالمة فائتة")
        status == "missed" -> t("لم يتم الرد")
        status == "declined" && mine -> t("تم رفض المكالمة")
        status == "declined" -> t("مكالمة مرفوضة")
        status == "ringing" -> if (mine) t("مكالمة صادرة") else t("مكالمة واردة")
        else -> if (mine) t("مكالمة صادرة") else t("مكالمة واردة")
    }
    val duration = remember(answered, ended) { callDurationText(answered, ended) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary.copy(alpha=.14f), modifier = Modifier.size(38.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(if (mode == "video") Icons.Rounded.Videocam else Icons.Rounded.PhoneInTalk, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
        }
        Spacer(Modifier.width(9.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold, color = if (status == "missed" && !mine) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Text(buildString { append(if (mode == "video") t("فيديو") else t("صوت")); if (duration.isNotBlank()) append(" • ").append(duration) }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun callDurationText(answered: String?, ended: String?): String {
    if (answered == null || ended == null) return ""
    return runCatching {
        val seconds = java.time.Duration.between(java.time.Instant.parse(answered), java.time.Instant.parse(ended)).seconds.coerceAtLeast(0)
        val m = seconds / 60; val s = seconds % 60
        if (m > 0) "${m}:${s.toString().padStart(2,'0')}" else "${s} ث"
    }.getOrDefault("")
}

@Composable
private fun MediaBubble(m:RemoteMessage, repo:MediaRepository){
    val scope=rememberCoroutineScope()
    var bitmap by remember(m.id,m.mediaUrl){mutableStateOf<android.graphics.Bitmap?>(null)}
    var loading by remember(m.id){mutableStateOf(m.type=="image")}
    LaunchedEffect(m.id,m.mediaUrl){if(m.type=="image"&&m.mediaUrl!=null){loading=true;bitmap=repo.loadBitmap(m);loading=false}}
    Column(Modifier.clickable(enabled=m.mediaUrl!=null){scope.launch{runCatching{repo.open(m)}}}) {
        if(m.type=="image" && bitmap!=null){
            Image(bitmap!!.asImageBitmap(),contentDescription=m.mediaName,contentScale=ContentScale.Crop,modifier=Modifier.fillMaxWidth().heightIn(min=120.dp,max=240.dp).clip(RoundedCornerShape(10.dp)))
            Spacer(Modifier.height(5.dp))
        } else {
            Row(verticalAlignment=Alignment.CenterVertically){
                Icon(when(m.type){"image"->Icons.Rounded.Image;"video"->Icons.Rounded.Movie;"audio"->Icons.Rounded.GraphicEq;else->Icons.Rounded.Description},null,tint=MaterialTheme.colorScheme.onSurface,modifier=Modifier.size(30.dp))
                Spacer(Modifier.width(8.dp))
                Column{
                    Text(m.mediaName?:m.body.ifBlank{t("مرفق")},color=MaterialTheme.colorScheme.onSurface,fontWeight=FontWeight.Medium)
                    Text(if(loading)t("جاري تحميل المعاينة…") else when(m.type){"audio"->t("رسالة صوتية");"image"->t("صورة");"video"->t("فيديو");else->m.mediaMime?:t("مستند")},color=MaterialTheme.colorScheme.onSurface.copy(.72f),style=MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun MediaOption(icon:androidx.compose.ui.graphics.vector.ImageVector,label:String,onClick:()->Unit){
    Column(horizontalAlignment=Alignment.CenterHorizontally){
        FilledTonalIconButton(onClick=onClick,modifier=Modifier.size(58.dp),colors=IconButtonDefaults.filledTonalIconButtonColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)){Icon(icon,label,tint=MaterialTheme.colorScheme.onSurface)}
        Text(label,color=MaterialTheme.colorScheme.onSurface,style=MaterialTheme.typography.labelMedium)
    }
}
