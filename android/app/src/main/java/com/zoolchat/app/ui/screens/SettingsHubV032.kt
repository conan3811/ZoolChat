package com.zoolchat.app.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.zoolchat.app.ui.i18n.AppLanguage
import com.zoolchat.app.ui.i18n.t
import com.zoolchat.app.ui.theme.ThemeMode
import com.zoolchat.app.ui.theme.ZoolDimens
import java.io.File
import kotlin.math.ln
import kotlin.math.pow

@Composable
fun SettingsHubV032(
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
    onChats: () -> Unit,
    onNotifications: () -> Unit,
    onStorageData: () -> Unit,
    onHelp: () -> Unit,
    onAbout: () -> Unit,
    onLogout: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("zoolchat_user_settings", Context.MODE_PRIVATE) }
    val profileName = accountDisplayName?.takeIf { it.isNotBlank() }
        ?: accountUsername?.takeIf { it.isNotBlank() }
        ?: "ZoolChat"
    val photoUri = prefs.getString("profile_photo", "").orEmpty()
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
    ) {
        SettingsTopBar(t("الإعدادات"), onBack)

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ZoolDimens.ScreenPadding, vertical = 6.dp)
                .clickable(onClick = onEditProfile),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(18.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                SettingsProfilePhoto(profileName, photoUri, 56.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(profileName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        t("متاح") + (accountUsername?.takeIf { it.isNotBlank() }?.let { " • @$it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.Rounded.QrCode2, contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }

        SettingsSection(t("الحساب"))
        SettingsRow(Icons.Rounded.Key, t("الحساب"), t("الأجهزة، تصدير البيانات، حذف الحساب"), onAccount)
        SettingsRow(Icons.Rounded.Security, t("الخصوصية"), t("آخر ظهور، متصل الآن، مؤشرات القراءة"), onPrivacy)
        SettingsRow(Icons.Rounded.Chat, t("الدردشات"), t("حجم الخط، زر الإدخال، ظهور الوسائط والأرشفة"), onChats)
        SettingsRow(Icons.Rounded.Notifications, t("الإشعارات"), t("الرسائل والمجموعات والمكالمات"), onNotifications)
        SettingsRow(Icons.Rounded.DataSaverOn, t("التخزين والبيانات"), t("إدارة التخزين والتنزيل وجودة الوسائط"), onStorageData)
        SettingsRow(
            when (themeMode) {
                ThemeMode.DARK -> Icons.Rounded.DarkMode
                ThemeMode.LIGHT -> Icons.Rounded.LightMode
                ThemeMode.SYSTEM -> Icons.Rounded.BrightnessAuto
            },
            t("المظهر"),
            when (themeMode) {
                ThemeMode.DARK -> t("الوضع الداكن")
                ThemeMode.LIGHT -> t("الوضع الفاتح")
                ThemeMode.SYSTEM -> t("حسب إعداد الجهاز")
            },
        ) { showThemeDialog = true }

        SettingsSection(t("الدعم"))
        SettingsRow(Icons.Rounded.Language, t("لغة التطبيق"), if (appLanguage == AppLanguage.AR) "العربية" else "English") { showLanguageDialog = true }
        SettingsRow(Icons.Rounded.HelpOutline, t("المساعدة"), t("مركز المساعدة، تواصل معنا، الخصوصية"), onHelp)
        SettingsRow(Icons.Rounded.Info, t("معلومات عن التطبيق"), "ZoolChat • v${BuildConfig.VERSION_NAME}", onAbout)

        HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        SettingsRow(
            icon = Icons.Rounded.Logout,
            title = t("تسجيل الخروج"),
            subtitle = t("الخروج من هذا الحساب على هذا الجهاز"),
            onClick = { showLogoutConfirm = true },
            danger = true,
        )
        Spacer(Modifier.height(12.dp))
    }

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text(t("المظهر")) },
            text = {
                Column {
                    ChoiceRow(t("فاتح"), themeMode == ThemeMode.LIGHT) { onThemeModeChange(ThemeMode.LIGHT); showThemeDialog = false }
                    ChoiceRow(t("داكن"), themeMode == ThemeMode.DARK) { onThemeModeChange(ThemeMode.DARK); showThemeDialog = false }
                    ChoiceRow(t("حسب الجهاز"), themeMode == ThemeMode.SYSTEM) { onThemeModeChange(ThemeMode.SYSTEM); showThemeDialog = false }
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
                    ChoiceRow("العربية", appLanguage == AppLanguage.AR) { onLanguageChange(AppLanguage.AR); showLanguageDialog = false }
                    ChoiceRow("English", appLanguage == AppLanguage.EN) { onLanguageChange(AppLanguage.EN); showLanguageDialog = false }
                }
            },
            confirmButton = { TextButton(onClick = { showLanguageDialog = false }) { Text(t("إغلاق")) } },
        )
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text(t("تسجيل الخروج؟")) },
            text = { Text(t("سيتم تسجيل خروجك من ZoolChat على هذا الجهاز، ويمكنك تسجيل الدخول مرة أخرى لاحقاً.")) },
            confirmButton = {
                TextButton(
                    onClick = { showLogoutConfirm = false; onLogout() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(t("تسجيل الخروج")) }
            },
            dismissButton = { TextButton(onClick = { showLogoutConfirm = false }) { Text(t("إلغاء")) } },
        )
    }
}

@Composable
fun ChatSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("zoolchat_user_settings", Context.MODE_PRIVATE) }
    var enterIsSend by remember { mutableStateOf(prefs.getBoolean("chat_enter_is_send", false)) }
    var mediaVisibility by remember { mutableStateOf(prefs.getBoolean("chat_media_visibility", true)) }
    var keepArchived by remember { mutableStateOf(prefs.getBoolean("chat_keep_archived", true)) }
    var fontSize by remember { mutableStateOf(prefs.getString("chat_font_size", "medium") ?: "medium") }
    var showFontDialog by remember { mutableStateOf(false) }

    SettingsPage(t("الدردشات"), onBack) {
        SettingsSection(t("العرض"))
        SettingsRow(Icons.Rounded.FormatSize, t("حجم الخط"), when (fontSize) { "small" -> t("صغير"); "large" -> t("كبير"); else -> t("متوسط") }) { showFontDialog = true }
        SettingsToggle(Icons.Rounded.Keyboard, t("زر الإدخال للإرسال"), t("عند تشغيله، يرسل زر الإدخال رسالتك"), enterIsSend) {
            enterIsSend = it; prefs.edit().putBoolean("chat_enter_is_send", it).apply()
        }
        SettingsToggle(Icons.Rounded.Photo, t("ظهور الوسائط"), t("إظهار الوسائط التي يتم تنزيلها حديثاً في معرض الهاتف"), mediaVisibility) {
            mediaVisibility = it; prefs.edit().putBoolean("chat_media_visibility", it).apply()
        }

        SettingsSection(t("الدردشات المؤرشفة"))
        SettingsToggle(Icons.Rounded.Archive, t("إبقاء الدردشات في الأرشيف"), t("تظل الدردشات المؤرشفة مؤرشفة عند وصول رسائل جديدة"), keepArchived) {
            keepArchived = it; prefs.edit().putBoolean("chat_keep_archived", it).apply()
        }

        SettingsSection(t("النسخ الاحتياطي والسجل"))
        SettingsInfoRow(Icons.Rounded.Cloud, t("النسخ الاحتياطي للدردشات"), t("سيتم ربط النسخ الاحتياطي السحابي عندما تتوفر خدمة تخزين مخصصة لـ ZoolChat"))
        SettingsInfoRow(Icons.Rounded.History, t("سجل الدردشة"), t("إدارة التصدير والمسح من داخل المحادثة أو إعدادات الحساب"))
    }

    if (showFontDialog) {
        AlertDialog(
            onDismissRequest = { showFontDialog = false },
            title = { Text(t("حجم الخط")) },
            text = {
                Column {
                    listOf("small" to t("صغير"), "medium" to t("متوسط"), "large" to t("كبير")).forEach { (value, label) ->
                        ChoiceRow(label, fontSize == value) {
                            fontSize = value
                            prefs.edit().putString("chat_font_size", value).apply()
                            showFontDialog = false
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showFontDialog = false }) { Text(t("إغلاق")) } },
        )
    }
}

@Composable
fun NotificationSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("zoolchat_user_settings", Context.MODE_PRIVATE) }
    var tones by remember { mutableStateOf(prefs.getBoolean("notif_conversation_tones", true)) }
    var messages by remember { mutableStateOf(prefs.getBoolean("notif_messages_enabled", true)) }
    var groups by remember { mutableStateOf(prefs.getBoolean("notif_groups_enabled", true)) }
    var reactions by remember { mutableStateOf(prefs.getBoolean("notif_reactions_enabled", true)) }
    var calls by remember { mutableStateOf(prefs.getBoolean("notif_calls_enabled", true)) }
    var highPriority by remember { mutableStateOf(prefs.getBoolean("notif_high_priority", true)) }

    SettingsPage(t("الإشعارات"), onBack) {
        SettingsToggle(Icons.Rounded.VolumeUp, t("نغمات المحادثات"), t("تشغيل أصوات الرسائل الواردة والصادرة"), tones) {
            tones = it; prefs.edit().putBoolean("notif_conversation_tones", it).apply()
        }
        SettingsSection(t("الرسائل"))
        SettingsToggle(Icons.Rounded.Message, t("إشعارات الرسائل"), t("السماح بإشعارات الرسائل الفردية"), messages) {
            messages = it; prefs.edit().putBoolean("notif_messages_enabled", it).apply()
        }
        SettingsToggle(Icons.Rounded.Groups, t("إشعارات المجموعات"), t("السماح بإشعارات رسائل المجموعات"), groups) {
            groups = it; prefs.edit().putBoolean("notif_groups_enabled", it).apply()
        }
        SettingsToggle(Icons.Rounded.Favorite, t("إشعارات التفاعلات"), t("تنبيهك عند التفاعل مع رسائلك أو حالتك"), reactions) {
            reactions = it; prefs.edit().putBoolean("notif_reactions_enabled", it).apply()
        }
        SettingsToggle(Icons.Rounded.PriorityHigh, t("إشعارات ذات أولوية عالية"), t("عرض معاينات الرسائل بشكل بارز عندما يسمح Android"), highPriority) {
            highPriority = it; prefs.edit().putBoolean("notif_high_priority", it).apply()
        }
        SettingsSection(t("المكالمات"))
        SettingsToggle(Icons.Rounded.PhoneInTalk, t("إشعارات المكالمات"), t("الرنين والاهتزاز للمكالمات الواردة"), calls) {
            calls = it; prefs.edit().putBoolean("notif_calls_enabled", it).apply()
        }
        SettingsRow(Icons.Rounded.Settings, t("إعدادات إشعارات Android"), t("النغمة، الاهتزاز، شاشة القفل والقنوات")) {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
        }
        Text(
            t("ملاحظة: إعدادات إشعارات Android يمكنها تجاوز إعدادات التطبيق."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
fun StorageDataSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("zoolchat_user_settings", Context.MODE_PRIVATE) }
    var mobile by remember { mutableStateOf(prefs.getBoolean("media_auto_mobile", false)) }
    var wifi by remember { mutableStateOf(prefs.getBoolean("media_auto_wifi", true)) }
    var roaming by remember { mutableStateOf(prefs.getBoolean("media_auto_roaming", false)) }
    var callSaver by remember { mutableStateOf(prefs.getBoolean("call_data_saver", false)) }
    var uploadQuality by remember { mutableStateOf(prefs.getString("media_upload_quality", "standard") ?: "standard") }
    var showQuality by remember { mutableStateOf(false) }
    var cacheBytes by remember { mutableLongStateOf(0L) }

    fun refreshCache() { cacheBytes = directoryBytes(context.cacheDir) }
    LaunchedEffect(Unit) { refreshCache() }

    SettingsPage(t("التخزين والبيانات"), onBack) {
        SettingsSection(t("إدارة التخزين"))
        SettingsRow(Icons.Rounded.Storage, t("مساحة التخزين المؤقت"), humanBytes(cacheBytes)) {
            runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
        }
        SettingsRow(Icons.Rounded.DeleteSweep, t("مسح الملفات المؤقتة"), t("لا يحذف الرسائل أو الحساب")) {
            runCatching { context.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
            refreshCache()
        }

        SettingsSection(t("التنزيل التلقائي للوسائط"))
        SettingsToggle(Icons.Rounded.SignalCellularAlt, t("عند استخدام بيانات الهاتف"), t("تنزيل الوسائط تلقائياً عبر بيانات الجوال"), mobile) {
            mobile = it; prefs.edit().putBoolean("media_auto_mobile", it).apply()
        }
        SettingsToggle(Icons.Rounded.Wifi, t("عند الاتصال بشبكة Wi‑Fi"), t("تنزيل الوسائط تلقائياً عبر Wi‑Fi"), wifi) {
            wifi = it; prefs.edit().putBoolean("media_auto_wifi", it).apply()
        }
        SettingsToggle(Icons.Rounded.Public, t("أثناء التجوال"), t("قد يؤدي ذلك إلى رسوم بيانات إضافية"), roaming) {
            roaming = it; prefs.edit().putBoolean("media_auto_roaming", it).apply()
        }

        SettingsSection(t("جودة الوسائط والمكالمات"))
        SettingsRow(Icons.Rounded.HighQuality, t("جودة تحميل الوسائط"), if (uploadQuality == "hd") t("جودة HD") else t("جودة قياسية")) { showQuality = true }
        SettingsToggle(Icons.Rounded.DataSaverOn, t("استخدام بيانات أقل للمكالمات"), t("تقليل استهلاك الشبكة عندما تكون جودة الاتصال محدودة"), callSaver) {
            callSaver = it; prefs.edit().putBoolean("call_data_saver", it).apply()
        }
    }

    if (showQuality) {
        AlertDialog(
            onDismissRequest = { showQuality = false },
            title = { Text(t("جودة تحميل الوسائط")) },
            text = {
                Column {
                    ChoiceRow(t("جودة قياسية"), uploadQuality == "standard") { uploadQuality = "standard"; prefs.edit().putString("media_upload_quality", uploadQuality).apply(); showQuality = false }
                    ChoiceRow(t("جودة HD"), uploadQuality == "hd") { uploadQuality = "hd"; prefs.edit().putString("media_upload_quality", uploadQuality).apply(); showQuality = false }
                }
            },
            confirmButton = { TextButton(onClick = { showQuality = false }) { Text(t("إغلاق")) } },
        )
    }
}

@Composable
fun HelpSettingsScreen(onBack: () -> Unit, onAbout: () -> Unit) {
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<String?>(null) }

    SettingsPage(t("المساعدة"), onBack) {
        SettingsRow(Icons.Rounded.HelpOutline, t("مركز المساعدة"), t("أسئلة شائعة حول الحساب والرسائل والمكالمات")) { dialog = "help" }
        SettingsRow(Icons.Rounded.Phone, t("تواصل معنا"), t("فتح محادثة دعم عبر واتساب")) {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/966567638840")))
            }
        }
        SettingsRow(Icons.Rounded.PrivacyTip, t("الشروط وسياسة الخصوصية"), t("كيف يتعامل ZoolChat مع بياناتك")) { dialog = "privacy" }
        SettingsRow(Icons.Rounded.Info, t("معلومات عن التطبيق"), "ZoolChat • v${BuildConfig.VERSION_NAME}", onAbout)
    }

    if (dialog != null) {
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(if (dialog == "help") t("مركز المساعدة") else t("الخصوصية")) },
            text = {
                Text(
                    if (dialog == "help")
                        t("إذا واجهتك مشكلة في تسجيل الدخول أو الرسائل أو المكالمات أو الحالات، تأكد أولاً من اتصال الإنترنت وصلاحيات الإشعارات والميكروفون والكاميرا. إذا استمرت المشكلة استخدم خيار تواصل معنا.")
                    else
                        t("يعرض ZoolChat معلومات الملف الشخصي والحضور وفق إعدادات الخصوصية، ولا ينبغي عرض كلمات المرور أو مفاتيح التشفير أو رموز الدخول لأي مستخدم آخر."),
                )
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(t("إغلاق")) } },
        )
    }
}

@Composable
private fun SettingsPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding(),
    ) {
        SettingsTopBar(title, onBack)
        content()
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun SettingsTopBar(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = ZoolDimens.ToolbarHeight).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(ZoolDimens.TouchTarget)) {
            Icon(Icons.Rounded.ArrowBack, contentDescription = t("رجوع"), modifier = Modifier.size(21.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SettingsSection(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    val tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().heightIn(min = 58.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Rounded.ChevronLeft, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .32f))
    }
}

@Composable
private fun SettingsInfoRow(icon: ImageVector, title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsToggle(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        RadioButton(selected = selected, onClick = onClick)
    }
}

@Composable
private fun SettingsProfilePhoto(name: String, photoUri: String, size: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    val bitmap = remember(photoUri) {
        if (photoUri.isBlank()) null else runCatching {
            context.contentResolver.openInputStream(Uri.parse(photoUri)).use { input -> BitmapFactory.decodeStream(input)?.asImageBitmap() }
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(CircleShape))
    } else {
        Avatar(name, size = size, online = false)
    }
}

private fun directoryBytes(root: File): Long = runCatching {
    root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}.getOrDefault(0L)

private fun humanBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val group = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(0, units.lastIndex)
    val value = bytes / 1024.0.pow(group.toDouble())
    return if (group == 0) "${bytes} B" else String.format("%.1f %s", value, units[group])
}
