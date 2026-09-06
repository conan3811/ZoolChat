package com.zoolchat.app.ui.screens

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zoolchat.app.ui.components.Avatar
import com.zoolchat.app.ui.theme.ZoolCallBackground
import com.zoolchat.app.ui.theme.ZoolGreen
import com.zoolchat.app.ui.theme.ZoolRed

@Composable
fun CallRoomScreen(video: Boolean, group: Boolean, onEnd: () -> Unit) {
    var micOn by remember { mutableStateOf(true) }
    var cameraOn by remember { mutableStateOf(video) }
    var speakerOn by remember { mutableStateOf(true) }
    var sharing by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().background(ZoolCallBackground).statusBarsPadding().navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(if (group) "مكالمة جماعية" else "محمد الكنزي", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(if (group) "4 مشاركين • جودة تكيفية" else "00:18 • HD", color = Color.White.copy(alpha = .62f), style = MaterialTheme.typography.bodySmall)
            }
            Surface(shape = RoundedCornerShape(12.dp), color = ZoolGreen.copy(alpha = .16f)) {
                Text("🔒 مشفر", color = ZoolGreen, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall)
            }
        }

        if (cameraOn) {
            if (group) {
                Column(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        VideoTile("محمد", Modifier.weight(1f).fillMaxHeight(), true)
                        VideoTile("سارة", Modifier.weight(1f).fillMaxHeight(), true)
                    }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        VideoTile("مايا", Modifier.weight(1f).fillMaxHeight(), false)
                        VideoTile("خالد", Modifier.weight(1f).fillMaxHeight(), true)
                    }
                }
            } else {
                Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp).background(Color(0xFF16232B), RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
                    Avatar("محمد الكنزي", size = 104.dp, online = true)
                    Surface(modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).size(width = 94.dp, height = 132.dp), shape = RoundedCornerShape(18.dp), color = Color(0xFF263942)) {
                        Box(contentAlignment = Alignment.Center) { Text("أنت", color = Color.White, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        } else {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(if (group) "قروب الأهل" else "محمد الكنزي", size = 118.dp, online = true)
                    Spacer(Modifier.height(14.dp))
                    Text(if (group) "قروب الأهل ❤️" else "محمد الكنزي", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("مكالمة صوتية", color = Color.White.copy(alpha = .6f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (sharing) {
            Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF15352F), modifier = Modifier.padding(top = 8.dp)) {
                Text("تتم مشاركة الشاشة الآن", color = Color.White, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
            }
        }

        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Color(0xFF111B21),
            modifier = Modifier.fillMaxWidth().padding(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CallButton(if (micOn) Icons.Rounded.Mic else Icons.Rounded.MicOff, "كتم", micOn) { micOn = !micOn }
                CallButton(if (cameraOn) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff, "كاميرا", cameraOn) { cameraOn = !cameraOn }
                CallButton(Icons.Rounded.ScreenShare, "مشاركة", sharing) { sharing = !sharing }
                CallButton(if (speakerOn) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff, "سماعة", speakerOn) { speakerOn = !speakerOn }
                FilledIconButton(onClick = onEnd, colors = IconButtonDefaults.filledIconButtonColors(containerColor = ZoolRed), modifier = Modifier.size(50.dp)) {
                    Icon(Icons.Rounded.CallEnd, contentDescription = "إنهاء", tint = Color.White, modifier = Modifier.size(21.dp))
                }
            }
        }
    }
}

@Composable
private fun VideoTile(name: String, modifier: Modifier, cameraOn: Boolean) {
    Box(modifier = modifier.background(Color(0xFF16232B), RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
        Avatar(name, size = 72.dp, online = true)
        Text(name, color = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp), style = MaterialTheme.typography.labelLarge)
        if (!cameraOn) Icon(Icons.Rounded.VideocamOff, null, tint = Color.White.copy(alpha = .72f), modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(20.dp))
    }
}

@Composable
private fun CallButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(
            onClick = onClick,
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = if (active) Color.White.copy(alpha = .16f) else Color.White.copy(alpha = .07f)),
        ) { Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(20.dp)) }
        Text(label, color = Color.White.copy(alpha = .72f), style = MaterialTheme.typography.labelSmall)
    }
}
