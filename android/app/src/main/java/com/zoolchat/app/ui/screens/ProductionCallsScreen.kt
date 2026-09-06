package com.zoolchat.app.ui.screens

import com.zoolchat.app.ui.i18n.t

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zoolchat.app.data.RemoteCallHistoryItem
import com.zoolchat.app.ui.components.Avatar
import com.zoolchat.app.ui.components.SectionTitle
import com.zoolchat.app.ui.components.ZoolHeader
import com.zoolchat.app.ui.theme.ZoolDimens

@Composable
fun ProductionCallsScreen(
    calls: List<RemoteCallHistoryItem>,
    onRefresh: () -> Unit,
    onStartWithPeer: (String, Boolean) -> Unit,
    onStartQuick: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    LazyColumn(modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=18.dp)) {
        item {
            ZoolHeader(t("المكالمات"),t("سجل المكالمات الحقيقي"),onSettings=onOpenSettings,extraAction={
                IconButton(onClick=onRefresh,modifier=Modifier.size(ZoolDimens.TouchTarget)){Icon(Icons.Rounded.Refresh,"تحديث")}
            })
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal=ZoolDimens.ScreenPadding),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick={onStartQuick(false)},modifier=Modifier.weight(1f)){Icon(Icons.Rounded.PhoneInTalk,null);Spacer(Modifier.width(6.dp));Text(t("صوت"))}
                Button(onClick={onStartQuick(true)},modifier=Modifier.weight(1f)){Icon(Icons.Rounded.Videocam,null);Spacer(Modifier.width(6.dp));Text(t("فيديو"))}
            }
        }
        item { SectionTitle(t("الأخيرة")) }
        if(calls.isEmpty()) item { Box(Modifier.fillParentMaxWidth().padding(36.dp),contentAlignment=Alignment.Center){Text(t("لا توجد مكالمات بعد"),color=MaterialTheme.colorScheme.onSurfaceVariant)} }
        items(calls,key={it.id}) { call ->
            val video=call.type=="video"
            Row(
                modifier=Modifier.fillMaxWidth().clickable(enabled=call.peerUserId!=null){call.peerUserId?.let{onStartWithPeer(it,video)}}.padding(horizontal=ZoolDimens.ScreenPadding,vertical=9.dp),
                verticalAlignment=Alignment.CenterVertically,
            ) {
                Avatar(call.peerName,46.dp)
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(call.peerName,fontWeight=FontWeight.SemiBold)
                    Text(callLabel(call),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(call.createdAt.replace('T',' ').take(16),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick={call.peerUserId?.let{onStartWithPeer(it,video)}},enabled=call.peerUserId!=null){Icon(if(video)Icons.Rounded.Videocam else Icons.Rounded.PhoneInTalk,"إعادة الاتصال")}
            }
        }
    }
}

private fun callLabel(call:RemoteCallHistoryItem):String {
    val base = when {
        call.status=="missed" && !call.initiatedByMe -> t("مكالمة فائتة")
        call.status=="missed" -> t("لم يتم الرد")
        call.status=="declined" && call.initiatedByMe -> t("تم رفض المكالمة")
        call.status=="declined" -> t("مكالمة مرفوضة")
        call.status=="ringing" -> if(call.initiatedByMe)t("جاري الرنين") else t("مكالمة واردة")
        call.status=="active" -> t("مكالمة جارية")
        call.status=="ended" && call.initiatedByMe -> t("مكالمة صادرة")
        call.status=="ended" -> t("مكالمة واردة")
        else -> if(call.type=="video") t("مكالمة فيديو") else t("مكالمة صوتية")
    }
    val duration = runCatching {
        if(call.answeredAt==null||call.endedAt==null) "" else {
            val sec=java.time.Duration.between(java.time.Instant.parse(call.answeredAt),java.time.Instant.parse(call.endedAt)).seconds.coerceAtLeast(0)
            val min=sec/60; val rem=sec%60; if(min>0) "${min}:${rem.toString().padStart(2,'0')}" else "${rem} ث"
        }
    }.getOrDefault("")
    return if(duration.isBlank()) base else "$base • $duration"
}
