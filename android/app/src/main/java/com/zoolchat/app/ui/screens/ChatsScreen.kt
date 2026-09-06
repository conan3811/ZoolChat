package com.zoolchat.app.ui.screens
import androidx.compose.foundation.background

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zoolchat.app.data.DemoRepository
import com.zoolchat.app.ui.components.Avatar
import com.zoolchat.app.ui.theme.ZoolDark
import com.zoolchat.app.ui.theme.ZoolDimens
import com.zoolchat.app.ui.theme.ZoolGreen

private enum class ChatFilter(val label: String) { ALL("الكل"), UNREAD("غير مقروءة"), GROUPS("المجموعات"), FAVORITES("المفضلة") }

@Composable
fun ChatsScreen(
    onOpenChat: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(ChatFilter.ALL) }

    val chats = DemoRepository.chats.filter { chat ->
        val matchesQuery = query.isBlank() || chat.title.contains(query, ignoreCase = true) || chat.subtitle.contains(query, ignoreCase = true)
        val matchesFilter = when (filter) {
            ChatFilter.ALL -> true
            ChatFilter.UNREAD -> chat.unread > 0
            ChatFilter.GROUPS -> chat.isGroup
            ChatFilter.FAVORITES -> chat.id in setOf("moh", "family")
        }
        matchesQuery && matchesFilter
    }

    Scaffold(
        containerColor = ZoolDark,
        floatingActionButton = {
            FloatingActionButton(
                onClick = {},
                containerColor = ZoolGreen,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.size(54.dp),
            ) {
                Icon(Icons.Rounded.Edit, contentDescription = "رسالة جديدة", modifier = Modifier.size(21.dp))
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(ZoolDark).padding(padding),
            contentPadding = PaddingValues(bottom = 18.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = ZoolDimens.ToolbarHeight).padding(horizontal = ZoolDimens.ScreenPadding, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("ZoolChat", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    IconButton(onClick = {}, modifier = Modifier.size(ZoolDimens.TouchTarget)) {
                        Icon(Icons.Rounded.CameraAlt, contentDescription = "الكاميرا", modifier = Modifier.size(ZoolDimens.IconVisual))
                    }
                    Box(modifier = Modifier.size(ZoolDimens.TouchTarget).clickable(onClick = onOpenSettings), contentAlignment = Alignment.Center) {
                        Avatar("محمد", size = 34.dp, online = true)
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = ZoolDimens.ScreenPadding).heightIn(min = 48.dp),
                    placeholder = { Text("ابحث أو اسأل Zool AI", style = MaterialTheme.typography.bodyMedium) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                )
                Spacer(Modifier.height(8.dp))
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = ZoolDimens.ScreenPadding),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(ChatFilter.entries) { item ->
                        FilterChip(
                            selected = filter == item,
                            onClick = { filter = item },
                            label = { Text(item.label, style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            items(chats, key = { it.id }) { chat ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = ZoolDimens.ChatRowMinHeight)
                        .clickable { onOpenChat(chat.id) }
                        .padding(horizontal = ZoolDimens.ScreenPadding, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(chat.title, size = ZoolDimens.AvatarList, online = chat.online)
                    Spacer(Modifier.width(11.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                chat.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (chat.unread > 0) FontWeight.Bold else FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                chat.time,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (chat.unread > 0) ZoolGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                chat.subtitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (chat.unread > 0) {
                                Spacer(Modifier.width(8.dp))
                                Surface(shape = CircleShape, color = ZoolGreen) {
                                    Text(
                                        chat.unread.toString(),
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(start = 74.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
            }
            if (chats.isEmpty()) {
                item {
                    Box(Modifier.fillParentMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        Text("لا توجد نتائج", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
