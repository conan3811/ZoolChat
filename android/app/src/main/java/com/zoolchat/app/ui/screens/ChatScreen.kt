package com.zoolchat.app.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zoolchat.app.data.DemoRepository
import com.zoolchat.app.ui.components.Avatar
import com.zoolchat.app.ui.theme.*

private data class AttachmentOption(val title: String, val icon: ImageVector, val tint: Color)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onStartCall: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var showAttachments by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var chatCleared by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }

    fun notify(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    fun exportChat() {
        val body = DemoRepository.messages.joinToString("\n") { "${it.time} - ${it.text}" }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "ZoolChat - محمد الكنزي")
            putExtra(Intent.EXTRA_TEXT, body)
        }
        context.startActivity(Intent.createChooser(intent, "تصدير الدردشة"))
    }

    if (showAttachments) {
        ModalBottomSheet(
            onDismissRequest = { showAttachments = false },
            containerColor = ChatTopBar,
            tonalElevation = 0.dp,
            dragHandle = { BottomSheetDefaults.DragHandle() },
        ) {
            AttachmentSheetContent(onOptionClick = { option ->
                showAttachments = false
                notify("$option — واجهة جاهزة للربط بالخدمة")
            })
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(ChatBackground)) {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar("محمد الكنزي", size = ZoolDimens.AvatarSmall, online = !blocked)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("محمد الكنزي", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ChatPrimaryText)
                        Text(
                            if (blocked) "محظور" else if (muted) "متصل الآن • مكتوم" else "متصل الآن",
                            style = MaterialTheme.typography.labelSmall,
                            color = ChatPrimaryText,
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack, modifier = Modifier.size(ZoolDimens.TouchTarget)) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "رجوع", modifier = Modifier.size(21.dp), tint = ChatPrimaryText)
                }
            },
            actions = {
                IconButton(onClick = { onStartCall(true) }, enabled = !blocked, modifier = Modifier.size(ZoolDimens.TouchTarget)) {
                    Icon(Icons.Rounded.Videocam, contentDescription = "مكالمة فيديو", modifier = Modifier.size(22.dp), tint = ChatPrimaryText)
                }
                IconButton(onClick = { onStartCall(false) }, enabled = !blocked, modifier = Modifier.size(ZoolDimens.TouchTarget)) {
                    Icon(Icons.Rounded.PhoneInTalk, contentDescription = "مكالمة صوتية", modifier = Modifier.size(21.dp), tint = ChatPrimaryText)
                }
                Box {
                    IconButton(onClick = { showMoreMenu = true }, modifier = Modifier.size(ZoolDimens.TouchTarget)) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "المزيد", modifier = Modifier.size(21.dp), tint = ChatPrimaryText)
                    }
                    DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }, modifier = Modifier.background(ChatBubbleIncoming)) {
                        CompactMenuItem(Icons.Rounded.Search, "بحث") { showMoreMenu = false; notify("بحث داخل المحادثة") }
                        CompactMenuItem(Icons.Rounded.PermMedia, "الوسائط والروابط والمستندات") { showMoreMenu = false; notify("مكتبة الوسائط") }
                        CompactMenuItem(if (muted) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsOff, if (muted) "إلغاء الكتم" else "كتم الإشعارات") {
                            muted = !muted; showMoreMenu = false
                        }
                        CompactMenuItem(Icons.Rounded.Timer, "الرسائل المختفية") { showMoreMenu = false; notify("إعداد الرسائل المختفية") }
                        HorizontalDivider(color = ChatPrimaryText.copy(alpha = .18f))
                        CompactMenuItem(Icons.Rounded.Report, "إبلاغ") { showMoreMenu = false; notify("تم فتح خيار الإبلاغ") }
                        CompactMenuItem(Icons.Rounded.Block, if (blocked) "إلغاء الحظر" else "حظر") {
                            blocked = !blocked; showMoreMenu = false
                        }
                        CompactMenuItem(Icons.Rounded.DeleteSweep, "مسح الدردشة") {
                            chatCleared = true; showMoreMenu = false
                        }
                        CompactMenuItem(Icons.Rounded.Share, "تصدير الدردشة") { showMoreMenu = false; exportChat() }
                        CompactMenuItem(Icons.Rounded.AddToHomeScreen, "إضافة اختصار") { showMoreMenu = false; notify("إضافة اختصار") }
                        CompactMenuItem(Icons.Rounded.PlaylistAdd, "إضافة إلى قائمة") { showMoreMenu = false; notify("إضافة إلى قائمة") }
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = ChatTopBar),
        )

        AnimatedVisibility(visible = blocked) {
            Surface(color = Color(0xFF3B1519), modifier = Modifier.fillMaxWidth()) {
                Text(
                    "هذه جهة اتصال محظورة. ألغِ الحظر للاتصال أو إرسال رسالة.",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = ChatPrimaryText,
                )
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().background(ChatBackground),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            item {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Surface(shape = RoundedCornerShape(12.dp), color = ChatBubbleIncoming) {
                        Text("اليوم", modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = ChatSecondaryText)
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            if (!chatCleared) {
                items(DemoRepository.messages, key = { it.id }) { msg ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (msg.mine) Arrangement.Start else Arrangement.End,
                    ) {
                        Surface(
                            shape = if (msg.mine) RoundedCornerShape(15.dp, 5.dp, 15.dp, 15.dp) else RoundedCornerShape(5.dp, 15.dp, 15.dp, 15.dp),
                            color = if (msg.mine) ChatBubbleOutgoing else ChatBubbleIncoming,
                            modifier = Modifier.widthIn(max = 300.dp),
                        ) {
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                                Text(msg.text, style = MaterialTheme.typography.bodyMedium, color = ChatPrimaryText)
                                Spacer(Modifier.height(2.dp))
                                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                                    Text(msg.time, style = MaterialTheme.typography.labelSmall, color = ChatSecondaryText)
                                    if (msg.mine) {
                                        Spacer(Modifier.width(3.dp))
                                        Text("✓✓", style = MaterialTheme.typography.labelSmall, color = ChatReadReceipt)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                item {
                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        Text("لا توجد رسائل", color = ChatSecondaryText, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 6.dp).navigationBarsPadding(),
            verticalAlignment = Alignment.Bottom,
        ) {
            Surface(
                modifier = Modifier.weight(1f).heightIn(min = ZoolDimens.ComposerHeight),
                shape = RoundedCornerShape(26.dp),
                color = ChatComposer,
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {}, modifier = Modifier.size(ZoolDimens.TouchTarget)) {
                        Icon(Icons.Rounded.Mood, contentDescription = "إيموجي", modifier = Modifier.size(20.dp), tint = ChatSecondaryText)
                    }
                    TextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("رسالة", style = MaterialTheme.typography.bodyMedium, color = ChatPrimaryText.copy(alpha = .72f)) },
                        maxLines = 4,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        enabled = !blocked,
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = ChatPrimaryText,
                            unfocusedTextColor = ChatPrimaryText,
                            disabledTextColor = ChatSecondaryText,
                            focusedPlaceholderColor = ChatPrimaryText.copy(alpha = .72f),
                            unfocusedPlaceholderColor = ChatPrimaryText.copy(alpha = .72f),
                            disabledPlaceholderColor = ChatSecondaryText,
                            cursorColor = ChatPrimaryText,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                        ),
                    )
                    IconButton(onClick = { showAttachments = true }, modifier = Modifier.size(ZoolDimens.TouchTarget)) {
                        Icon(Icons.Rounded.AttachFile, contentDescription = "إرفاق", modifier = Modifier.size(20.dp), tint = ChatSecondaryText)
                    }
                    IconButton(onClick = {}, modifier = Modifier.size(ZoolDimens.TouchTarget)) {
                        Icon(Icons.Rounded.CameraAlt, contentDescription = "الكاميرا", modifier = Modifier.size(20.dp), tint = ChatSecondaryText)
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            FilledIconButton(
                onClick = { if (text.isNotBlank()) text = "" else notify("اضغط مطولاً لتسجيل رسالة صوتية") },
                enabled = !blocked,
                modifier = Modifier.size(50.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = ZoolGreen),
            ) {
                Icon(if (text.isBlank()) Icons.Rounded.Mic else Icons.Rounded.Send, contentDescription = if (text.isBlank()) "رسالة صوتية" else "إرسال", modifier = Modifier.size(21.dp))
            }
        }
    }
}

@Composable
private fun CompactMenuItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.bodyMedium, color = ChatPrimaryText) },
        onClick = onClick,
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp), tint = ChatPrimaryText) },
        contentPadding = PaddingValues(horizontal = 12.dp),
    )
}

@Composable
private fun AttachmentSheetContent(onOptionClick: (String) -> Unit) {
    val options = listOf(
        AttachmentOption("المعرض", Icons.Rounded.Collections, Color(0xFF1D8CFF)),
        AttachmentOption("الكاميرا", Icons.Rounded.CameraAlt, Color(0xFFFF2D8A)),
        AttachmentOption("الموقع", Icons.Rounded.Place, Color(0xFF19D68C)),
        AttachmentOption("جهة اتصال", Icons.Rounded.Person, Color(0xFF21A4FF)),
        AttachmentOption("مستند", Icons.Rounded.Description, Color(0xFF8A6DFF)),
        AttachmentOption("استطلاع", Icons.Rounded.InsertChart, Color(0xFFFFC043)),
        AttachmentOption("حدث", Icons.Rounded.Event, Color(0xFFFF3A92)),
        AttachmentOption("صور AI", Icons.Rounded.AutoAwesome, Color(0xFF1D8CFF)),
    )

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp).navigationBarsPadding()) {
        options.chunked(4).forEach { rowItems ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowItems.forEach { item ->
                    AttachmentOptionItem(option = item, modifier = Modifier.weight(1f), onClick = { onOptionClick(item.title) })
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun AttachmentOptionItem(option: AttachmentOption, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(onClick = onClick, shape = RoundedCornerShape(22.dp), color = ChatBubbleIncoming, tonalElevation = 0.dp, modifier = Modifier.fillMaxWidth().height(66.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Surface(shape = CircleShape, color = option.tint.copy(alpha = .14f), modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(option.icon, contentDescription = option.title, tint = option.tint, modifier = Modifier.size(24.dp)) }
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(option.title, style = MaterialTheme.typography.labelSmall, color = ChatPrimaryText)
    }
}
