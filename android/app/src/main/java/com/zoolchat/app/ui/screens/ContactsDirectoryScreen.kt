package com.zoolchat.app.ui.screens

import com.zoolchat.app.ui.i18n.t

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zoolchat.app.data.RegisteredContact
import com.zoolchat.app.ui.components.Avatar

@Composable
fun ContactsDirectoryScreen(
    contacts: List<RegisteredContact>,
    searchResults: List<RegisteredContact>,
    onSearch: (String) -> Unit,
    onBack: () -> Unit,
    onChat: (RegisteredContact) -> Unit,
    onCall: (RegisteredContact) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val shown = if (query.trim().length >= 3) searchResults else contacts
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "رجوع") }
            Text(t("جهات اتصال ZoolChat"), style = MaterialTheme.typography.titleLarge)
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; onSearch(it) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            placeholder = { Text(t("ابحث عن مستخدم باسم @username")) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            singleLine = true,
        )
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (query.trim().length >= 3) t("لا يوجد مستخدم مطابق") else t("لا توجد جهات اتصال مسجلة بعد. يمكنك البحث عن أي مستخدم عن طريق @username."),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else LazyColumn {
            items(shown, key = { it.userId }) { c ->
                Row(
                    Modifier.fillMaxWidth().clickable { onChat(c) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(c.localName, 48.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.localName)
                        Text(c.username?.let { "@$it" } ?: c.phoneE164, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onCall(c) }) { Icon(Icons.Rounded.Phone, "اتصال") }
                    IconButton(onClick = { onChat(c) }) { Icon(Icons.Rounded.Chat, "محادثة") }
                }
            }
        }
    }
}
