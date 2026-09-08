package com.zoolchat.app.ui.screens
import androidx.compose.foundation.background
import kotlinx.coroutines.launch

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zoolchat.app.BuildConfig
import com.zoolchat.app.ui.components.Avatar
import com.zoolchat.app.network.ApiClient
import com.zoolchat.app.ui.theme.ThemeMode
import com.zoolchat.app.ui.i18n.AppLanguage
import com.zoolchat.app.ui.i18n.t
import com.zoolchat.app.ui.theme.ZoolDimens

@Composable
fun ProfileScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    appLanguage: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    accountDisplayName: String? = null,
    accountUsername: String? = null,
    onBack: () -> Unit,
    onEditProfile: () -> Unit,
    onPrivacy: () -> Unit,
    onAccount: () -> Unit,
    onAbout: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("zoolchat_user_settings", Context.MODE_PRIVATE) }
    val profileName = accountDisplayName?.takeIf { it.isNotBlank() }
        ?: accountUsername?.takeIf { it.isNotBlank() }
        ?: "ZoolChat"
    var photoUri by remember { mutableStateOf(prefs.getString("profile_photo", "") ?: "") }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var settingsQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        photoUri = prefs.getString("profile_photo", "") ?: ""
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CompactTopBar(t("الإعدادات"), onBack)
        OutlinedTextField(
            value = settingsQuery,
            onValueChange = { settingsQuery = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = ZoolDimens.ScreenPadding, vertical = 8.dp),
            placeholder = { Text(t("البحث في الإعدادات")) },
            leadingIcon = { Icon(Icons.Rounded.Search, null, modifier = Modifier.size(19.dp)) },
            singleLine = true,
            shape = RoundedCornerShape(22.dp),
            colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
        )

        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = ZoolDimens.ScreenPadding, vertical = 4.dp).clickable(onClick = onEditProfile),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(18.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                ProfilePhoto(name = profileName, photoUri = photoUri, size = 56.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(profileName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(t("متاح") + (accountUsername?.takeIf { it.isNotBlank() }?.let { " • @$it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if(!accountUsername.isNullOrBlank()) IconButton(onClick={
                    val link="zoolchat://profile/${Uri.encode(accountUsername)}"
                    val send=Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,link)
                    context.startActivity(Intent.createChooser(send,t("مشاركة رابط الملف الشخصي")))
                }) { Icon(Icons.Rounded.Share, contentDescription=t("مشاركة رابط الملف الشخصي"), modifier=Modifier.size(22.dp), tint=MaterialTheme.colorScheme.primary) }
            }
        }

        SectionLabel(t("الحساب"))
        SettingRow(Icons.Rounded.Key, t("الحساب"), t("الأجهزة، تصدير البيانات، تسجيل الخروج، حذف الحساب"), onClick = onAccount)
        SettingRow(Icons.Rounded.Security, t("الخصوصية"), t("آخر ظهور، متصل الآن، مؤشرات القراءة"), onClick = onPrivacy)
        SettingRow(Icons.Rounded.Chat, t("الدردشات"), t("الخلفية، النسخ الاحتياطي، سجل الدردشة"))
        SettingRow(Icons.Rounded.Notifications, t("الإشعارات"), t("الرسائل والمجموعات والمكالمات"))
        SettingRow(Icons.Rounded.DataSaverOn, t("التخزين والبيانات"), t("إدارة التخزين وجودة الوسائط"))
        SettingRow(
            icon = when (themeMode) { ThemeMode.DARK -> Icons.Rounded.DarkMode; ThemeMode.LIGHT -> Icons.Rounded.LightMode; ThemeMode.SYSTEM -> Icons.Rounded.BrightnessAuto },
            title = t("المظهر"),
            subtitle = when (themeMode) { ThemeMode.DARK -> t("الوضع الداكن"); ThemeMode.LIGHT -> t("الوضع الفاتح"); ThemeMode.SYSTEM -> t("حسب إعداد الجهاز") },
            onClick = { showThemeDialog = true },
        )
        SectionLabel(t("الدعم"))
        SettingRow(Icons.Rounded.Language, t("لغة التطبيق"), if (appLanguage == AppLanguage.AR) "العربية" else "English") { showLanguageDialog = true }
        SettingRow(Icons.Rounded.HelpOutline, t("المساعدة"), t("مركز المساعدة وسياسة الخصوصية"))
        SettingRow(Icons.Rounded.Info, t("معلومات عن التطبيق"), "ZoolChat • v${BuildConfig.VERSION_NAME}", onClick = onAbout)
    }

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text(t("المظهر")) },
            text = {
                Column {
                    ThemeChoice(t("فاتح"), Icons.Rounded.LightMode, themeMode == ThemeMode.LIGHT) { onThemeModeChange(ThemeMode.LIGHT); showThemeDialog = false }
                    ThemeChoice(t("داكن"), Icons.Rounded.DarkMode, themeMode == ThemeMode.DARK) { onThemeModeChange(ThemeMode.DARK); showThemeDialog = false }
                    ThemeChoice(t("حسب الجهاز"), Icons.Rounded.BrightnessAuto, themeMode == ThemeMode.SYSTEM) { onThemeModeChange(ThemeMode.SYSTEM); showThemeDialog = false }
                }
            },
            confirmButton = { TextButton(onClick = { showThemeDialog = false }) { Text(t("إغلاق")) } },
        )
    }

    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(t("لغة التطبيق")) },
            text = {
                Column {
                    ThemeChoice("العربية", Icons.Rounded.Language, appLanguage == AppLanguage.AR) { onLanguageChange(AppLanguage.AR); showLanguageDialog = false }
                    ThemeChoice("English", Icons.Rounded.Language, appLanguage == AppLanguage.EN) { onLanguageChange(AppLanguage.EN); showLanguageDialog = false }
                }
            },
            confirmButton = { TextButton(onClick = { showLanguageDialog = false }) { Text(t("إغلاق")) } },
        )
    }
}

@Composable
fun EditProfileScreen(
    accountDisplayName: String? = null,
    accountUsername: String? = null,
    accountPhoneE164: String? = null,
    onDisplayNameChange: (String) -> Unit = {},
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("zoolchat_user_settings", Context.MODE_PRIVATE) }
    val accountFallbackName = accountUsername?.takeIf { it.isNotBlank() } ?: "ZoolChat"
    var name by remember(accountDisplayName, accountUsername) { mutableStateOf(accountDisplayName?.takeIf { it.isNotBlank() } ?: accountFallbackName) }
    var about by remember { mutableStateOf(prefs.getString("profile_about", "متاح") ?: "متاح") }
    var photoUri by remember { mutableStateOf(prefs.getString("profile_photo", "") ?: "") }
    var editField by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }

    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            photoUri = uri.toString()
            prefs.edit().putString("profile_photo", photoUri).apply()
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CompactTopBar(t("الملف الشخصي"), onBack)
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(contentAlignment = Alignment.BottomEnd) {
                ProfilePhoto(name, photoUri, 92.dp)
                FilledIconButton(onClick = { photoLauncher.launch(arrayOf("image/*")) }, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Rounded.CameraAlt, contentDescription = "تغيير الصورة", modifier = Modifier.size(18.dp))
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        EditableSetting(Icons.Rounded.Person, t("الاسم"), name) { editField = "name"; draft = name }
        EditableSetting(Icons.Rounded.Info, t("النبذة"), about) { editField = "about"; draft = about }
        SettingRow(Icons.Rounded.Phone, t("رقم الهاتف"), accountPhoneE164?.takeIf { it.isNotBlank() } ?: "—")
        SettingRow(Icons.Rounded.AlternateEmail, t("اسم المستخدم"), accountUsername?.takeIf { it.isNotBlank() }?.let { "@$it" } ?: "—")
    }

    if (editField != null) {
        AlertDialog(
            onDismissRequest = { editField = null },
            title = { Text(if (editField == "name") "تعديل الاسم" else "تعديل النبذة") },
            text = { OutlinedTextField(value = draft, onValueChange = { draft = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    if (editField == "name") {
                        val updated = draft.trim().ifBlank { accountFallbackName }
                        name = updated
                        onDisplayNameChange(updated)
                    } else {
                        about = draft.trim().ifBlank { "متاح" }; prefs.edit().putString("profile_about", about).apply()
                    }
                    editField = null
                }) { Text(t("حفظ")) }
            },
            dismissButton = { TextButton(onClick = { editField = null }) { Text(t("إلغاء")) } },
        )
    }
}

@Composable
fun PrivacyScreen(api: ApiClient, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("zoolchat_user_settings", Context.MODE_PRIVATE) }
    var lastSeen by remember { mutableStateOf(prefs.getString("last_seen", "الجميع") ?: "الجميع") }
    var online by remember { mutableStateOf(prefs.getString("online_visibility", "نفس إعداد آخر ظهور") ?: "نفس إعداد آخر ظهور") }
    var profilePhoto by remember { mutableStateOf(prefs.getString("profile_photo_visibility", "جهات اتصالي") ?: "جهات اتصالي") }
    var aboutVisibility by remember { mutableStateOf(prefs.getString("about_visibility", "جهات اتصالي") ?: "جهات اتصالي") }
    var readReceipts by remember { mutableStateOf(prefs.getBoolean("read_receipts", true)) }
    var selector by remember { mutableStateOf<String?>(null) }
    var privacyError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        runCatching { api.getPrivacy() }.onSuccess { remote ->
            readReceipts = remote.optBoolean("readReceipts", readReceipts)
            val serverLastSeen = remote.optBoolean("showLastSeen", true)
            val serverOnline = remote.optBoolean("showOnline", true)
            if (!serverLastSeen) lastSeen = "لا أحد"
            if (!serverOnline) online = "نفس إعداد آخر ظهور"
            prefs.edit()
                .putBoolean("read_receipts", readReceipts)
                .putString("last_seen", lastSeen)
                .putString("online_visibility", online)
                .apply()
        }.onFailure { privacyError = t("تعذر تحديث إعدادات الخصوصية") }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CompactTopBar(t("الخصوصية"), onBack)
        SectionLabel(t("من يمكنه رؤية معلوماتي الشخصية"))
        SettingRow(Icons.Rounded.Schedule, t("آخر ظهور ومتصل الآن"), "${t(lastSeen)} • ${t(online)}") { selector = "lastSeen" }
        SettingRow(Icons.Rounded.AccountCircle, t("صورة الملف الشخصي"), t(profilePhoto)) { selector = "photo" }
        SettingRow(Icons.Rounded.Info, t("النبذة"), t(aboutVisibility)) { selector = "about" }
        ToggleSetting(
            icon = Icons.Rounded.DoneAll,
            title = t("مؤشرات قراءة الرسائل"),
            subtitle = t("عند إيقافها لن ترسل أو تستقبل علامتي القراءة في الدردشات الفردية."),
            checked = readReceipts,
            onCheckedChange = {
                readReceipts = it
                prefs.edit().putBoolean("read_receipts", it).apply()
                scope.launch {
                    runCatching { api.updatePrivacy(readReceipts = it) }
                        .onFailure { privacyError = t("تعذر تحديث إعدادات الخصوصية") }
                }
            },
        )
        privacyError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) }
        SectionLabel(t("الحماية والتفاعل"))
        SettingRow(Icons.Rounded.Groups, t("المجموعات"), t("جهات اتصالي"))
        SettingRow(Icons.Rounded.Block, t("جهات الاتصال المحظورة"), t("إدارة المحظورين"))
        SettingRow(Icons.Rounded.Devices, t("الأجهزة المرتبطة"), t("إدارة الجلسات"))
        SettingRow(Icons.Rounded.VerifiedUser, t("التحقق بخطوتين"), t("إضافة PIN"))
        SettingRow(Icons.Rounded.Lock, t("قفل التطبيق"), t("بصمة أو رمز قفل"))
    }

    if (selector != null) {
        val title = when (selector) { "lastSeen" -> t("آخر ظهور ومتصل الآن"); "photo" -> t("صورة الملف الشخصي"); else -> t("النبذة") }
        val options = listOf("الجميع", "جهات اتصالي", "لا أحد")
        val current = when (selector) { "lastSeen" -> lastSeen; "photo" -> profilePhoto; else -> aboutVisibility }
        AlertDialog(
            onDismissRequest = { selector = null },
            title = { Text(title) },
            text = {
                Column {
                    options.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                when (selector) {
                                    "lastSeen" -> {
                                        lastSeen = option
                                        prefs.edit().putString("last_seen", option).apply()
                                        val showLastSeen = option != "لا أحد"
                                        val showOnline = online == "الجميع" || (online == "نفس إعداد آخر ظهور" && showLastSeen)
                                        scope.launch {
                                            runCatching { api.updatePrivacy(showLastSeen = showLastSeen, showOnline = showOnline) }
                                                .onFailure { privacyError = t("تعذر تحديث إعدادات الخصوصية") }
                                        }
                                    }
                                    "photo" -> { profilePhoto = option; prefs.edit().putString("profile_photo_visibility", option).apply() }
                                    else -> { aboutVisibility = option; prefs.edit().putString("about_visibility", option).apply() }
                                }
                                selector = null
                            }.padding(vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(t(option), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            RadioButton(selected = current == option, onClick = null)
                        }
                    }
                    if (selector == "lastSeen") {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text(t("من يمكنه رؤية أنني متصل الآن"), style = MaterialTheme.typography.labelLarge)
                        listOf("الجميع", "نفس إعداد آخر ظهور").forEach { option ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    online = option
                                    prefs.edit().putString("online_visibility", option).apply()
                                    val showOnline = option == "الجميع" || lastSeen != "لا أحد"
                                    scope.launch {
                                        runCatching { api.updatePrivacy(showOnline = showOnline) }
                                            .onFailure { privacyError = t("تعذر تحديث إعدادات الخصوصية") }
                                    }
                                }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(t(option), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                RadioButton(selected = online == option, onClick = null)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selector = null }) { Text(t("تم")) } },
        )
    }
}

@Composable
private fun ProfilePhoto(name: String, photoUri: String, size: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    val bitmap = remember(photoUri) {
        if (photoUri.isBlank()) null else runCatching {
            context.contentResolver.openInputStream(Uri.parse(photoUri)).use { input -> BitmapFactory.decodeStream(input)?.asImageBitmap() }
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = "صورة الملف الشخصي", contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(CircleShape))
    } else Avatar(name, size = size, online = false)
}

@Composable
private fun CompactTopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = ZoolDimens.ToolbarHeight).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(ZoolDimens.TouchTarget)) {
            Icon(Icons.Rounded.ArrowBack, contentDescription = "رجوع", modifier = Modifier.size(21.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun EditableSetting(icon: ImageVector, title: String, value: String, onClick: () -> Unit) = SettingRow(icon, title, value, onClick)

@Composable
private fun ToggleSetting(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ThemeChoice(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp)); Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); RadioButton(selected = selected, onClick = onClick)
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit = {}) {
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Icon(Icons.Rounded.ChevronLeft, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .32f))
    }
}
