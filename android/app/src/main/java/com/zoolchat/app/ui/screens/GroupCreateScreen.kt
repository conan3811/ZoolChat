package com.zoolchat.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zoolchat.app.data.RegisteredContact
import com.zoolchat.app.network.ApiClient
import com.zoolchat.app.ui.components.Avatar
import com.zoolchat.app.ui.i18n.t
import kotlinx.coroutines.launch

@Composable
fun GroupCreateScreen(
    api: ApiClient,
    contacts: List<RegisteredContact>,
    searchResults: List<RegisteredContact>,
    onSearch: (String) -> Unit,
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val all = remember(contacts, searchResults, query) {
        (contacts + searchResults).distinctBy { it.userId }.filter { query.isBlank() || it.displayName.contains(query, true) || (it.username?.contains(query, true) == true) || it.phoneE164.contains(query) }
    }

    Scaffold(
        topBar = {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, t("رجوع")) }
                Column(Modifier.weight(1f)) { Text(t("مجموعة جديدة"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("${selected.size} ${t("محدد")}", style = MaterialTheme.typography.labelSmall) }
                Button(onClick = {
                    if (title.trim().isBlank()) { error = t("اكتب اسم المجموعة"); return@Button }
                    busy = true; error = null
                    scope.launch {
                        runCatching { api.createGroup(title.trim(), description.trim(), selected.toList()) }
                            .onSuccess { busy = false; onCreated(it.getString("id")) }
                            .onFailure { busy = false; error = it.message ?: t("تعذر إنشاء المجموعة") }
                    }
                }, enabled = !busy && title.isNotBlank()) { if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Check, null); Spacer(Modifier.width(4.dp)); Text(t("إنشاء")) }
            }
        }
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Card(shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.GroupAdd, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp)); Text(t("معلومات المجموعة"), fontWeight = FontWeight.Bold) }
                        OutlinedTextField(title, { title = it.take(100) }, Modifier.fillMaxWidth(), label = { Text(t("اسم المجموعة")) }, singleLine = true)
                        OutlinedTextField(description, { description = it.take(512) }, Modifier.fillMaxWidth(), label = { Text(t("وصف المجموعة (اختياري)")) }, minLines = 2, maxLines = 4)
                    }
                }
            }
            item {
                OutlinedTextField(query, { query = it; if (it.trim().length >= 3) onSearch(it.trim()) }, Modifier.fillMaxWidth(), placeholder = { Text(t("ابحث عن أعضاء")) }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = RoundedCornerShape(20.dp))
            }
            if (error != null) item { Text(error!!, color = MaterialTheme.colorScheme.error) }
            item { Text(t("الأعضاء"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
            if (all.isEmpty()) item { Text(t("لا توجد جهات اتصال مسجلة بعد"), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp)) }
            items(all, key = { it.userId }) { c ->
                val checked = c.userId in selected
                Row(Modifier.fillMaxWidth().clickable { selected = if (checked) selected - c.userId else selected + c.userId }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(c.displayName, 44.dp); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(c.localName.ifBlank { c.displayName }, fontWeight = FontWeight.SemiBold); Text(c.username?.let { "@$it" } ?: c.phoneE164, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Checkbox(checked, onCheckedChange = { selected = if (checked) selected - c.userId else selected + c.userId })
                }
            }
        }
    }
}
