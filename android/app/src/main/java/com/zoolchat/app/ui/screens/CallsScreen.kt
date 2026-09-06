package com.zoolchat.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zoolchat.app.data.DemoRepository
import com.zoolchat.app.ui.components.Avatar
import com.zoolchat.app.ui.components.SectionTitle
import com.zoolchat.app.ui.components.ZoolHeader
import com.zoolchat.app.ui.theme.ZoolDimens
import com.zoolchat.app.ui.theme.ZoolRed

@Composable
fun CallsScreen(
    onStartCall: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 18.dp)) {
        item { ZoolHeader("المكالمات", "الصوت والفيديو في مكان واحد", onSettings = onOpenSettings) }
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = ZoolDimens.ScreenPadding),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(18.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(20.dp)) }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("إنشاء رابط مكالمة", style = MaterialTheme.typography.titleMedium)
                        Text("شارك الرابط وابدأ عندما ينضم الآخرون", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = ZoolDimens.ScreenPadding),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilledTonalButton(onClick = { onStartCall(false) }, modifier = Modifier.weight(1f).height(44.dp)) {
                    Icon(Icons.Rounded.PhoneInTalk, null, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(7.dp)); Text("صوت")
                }
                Button(onClick = { onStartCall(true) }, modifier = Modifier.weight(1f).height(44.dp)) {
                    Icon(Icons.Rounded.Videocam, null, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(7.dp)); Text("فيديو")
                }
            }
        }
        item { SectionTitle("الأخيرة") }
        items(DemoRepository.calls, key = { it.id }) { call ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 66.dp)
                    .clickable { onStartCall(call.video) }
                    .padding(horizontal = ZoolDimens.ScreenPadding, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(call.name, size = 46.dp)
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(call.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(call.label, style = MaterialTheme.typography.bodySmall, color = if (call.missed) ZoolRed else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(call.time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { onStartCall(call.video) }, modifier = Modifier.size(ZoolDimens.TouchTarget)) {
                    Icon(if (call.video) Icons.Rounded.Videocam else Icons.Rounded.PhoneInTalk, contentDescription = "إعادة الاتصال", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
