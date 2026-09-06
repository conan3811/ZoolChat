package com.zoolchat.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zoolchat.app.ui.theme.ZoolDimens

@Composable
fun ZoolHeader(
    title: String,
    subtitle: String? = null,
    onSettings: (() -> Unit)? = null,
    extraAction: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ZoolDimens.ToolbarHeight)
            .padding(horizontal = ZoolDimens.ScreenPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (extraAction != null) extraAction()
        if (onSettings != null) {
            IconButton(onClick = onSettings, modifier = Modifier.size(ZoolDimens.TouchTarget)) {
                Icon(
                    Icons.Rounded.Settings,
                    contentDescription = "الإعدادات",
                    modifier = Modifier.size(ZoolDimens.IconVisual),
                )
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(horizontal = ZoolDimens.ScreenPadding, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
}
