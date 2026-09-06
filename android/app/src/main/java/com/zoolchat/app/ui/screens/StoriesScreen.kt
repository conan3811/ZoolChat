package com.zoolchat.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zoolchat.app.ui.components.Avatar
import com.zoolchat.app.ui.components.ZoolHeader
import com.zoolchat.app.ui.i18n.t
import com.zoolchat.app.ui.theme.ZoolGreen

@Composable
fun StoriesScreen(onOpenSettings: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ZoolHeader(t("التحديثات"), t("القصص والقنوات"), onSettings = onOpenSettings)
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.BottomStart) {
                Avatar(t("قصتي"), size = 54.dp)
                Surface(shape = CircleShape, color = ZoolGreen, modifier = Modifier.size(20.dp)) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, null, modifier = Modifier.size(14.dp)) } }
            }
            Spacer(Modifier.width(12.dp)); Column { Text(t("قصتي"), style = MaterialTheme.typography.titleMedium); Text(t("لم تضف تحديثًا بعد"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        HorizontalDivider()
        Box(Modifier.fillMaxSize().padding(36.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Rounded.AutoStories, null, modifier = Modifier.size(58.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(12.dp)); Text(t("لا توجد تحديثات حقيقية بعد"), style = MaterialTheme.typography.titleMedium); Text(t("ستظهر هنا تحديثات جهات اتصالك عند توفرها"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
