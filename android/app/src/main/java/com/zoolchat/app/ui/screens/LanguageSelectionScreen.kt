package com.zoolchat.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zoolchat.app.ui.i18n.AppLanguage

@Composable
fun LanguageSelectionScreen(onSelected: (AppLanguage) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Rounded.Language, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(20.dp))
            Text("اختر اللغة", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Choose your language", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(30.dp))
            Button(onClick = { onSelected(AppLanguage.AR) }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("العربية") }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { onSelected(AppLanguage.EN) }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("English") }
        }
    }
}
