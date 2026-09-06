package com.zoolchat.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zoolchat.app.ui.components.ZoolHeader
import com.zoolchat.app.ui.i18n.t

@Composable
fun CommunitiesScreen(onOpenSettings: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ZoolHeader(t("المجتمعات"), t("إدارة عدة مجموعات في مكان واحد"), onSettings = onOpenSettings)
        Box(Modifier.fillMaxSize().padding(36.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Groups, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(14.dp)); Text(t("لا توجد مجتمعات بعد"), style = MaterialTheme.typography.titleLarge)
                Text(t("تمت إزالة المجتمعات التجريبية. عند إنشاء مجتمع حقيقي سيظهر هنا."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
