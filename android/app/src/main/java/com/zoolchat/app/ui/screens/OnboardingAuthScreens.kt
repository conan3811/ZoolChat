package com.zoolchat.app.ui.screens

import com.zoolchat.app.ui.i18n.t
import com.zoolchat.app.ui.i18n.I18n
import com.zoolchat.app.ui.i18n.AppLanguage

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp
import com.zoolchat.app.auth.AuthRepository
import com.zoolchat.app.auth.SessionStore
import com.google.i18n.phonenumbers.PhoneNumberUtil
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun OnboardingScreen(onContinue: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.Forum, null, modifier = Modifier.size(80.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(22.dp)); Text(t("مرحباً بك في ZoolChat"), style=MaterialTheme.typography.headlineMedium, fontWeight=FontWeight.Bold)
        Spacer(Modifier.height(10.dp)); Text(t("زول قريب.. مهما بَعُد"), color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(26.dp)); Text(t("مراسلات، مكالمات صوت وفيديو، اجتماعات وقصص في مكان واحد. سنطلب الصلاحيات عند الحاجة فقط، ويمكنك تغييرها لاحقاً من الإعدادات."))
        Spacer(Modifier.height(28.dp)); Button(onClick=onContinue, modifier=Modifier.fillMaxWidth().height(52.dp)){Text(t("متابعة"))}
    }
}


private data class CountryDialOption(
    val region: String,
    val name: String,
    val callingCode: Int,
)

@Composable
private fun rememberCountryDialOptions(): List<CountryDialOption> {
    val language = I18n.language
    return remember(language) {
        val util = PhoneNumberUtil.getInstance()
        val displayLocale = if (language == AppLanguage.AR) Locale.forLanguageTag("ar") else Locale.ENGLISH
        util.supportedRegions.mapNotNull { region ->
            val name = Locale.Builder().setRegion(region).build().getDisplayCountry(displayLocale).trim()
            val callingCode = util.getCountryCodeForRegion(region)
            if (name.isBlank() || callingCode <= 0) null else CountryDialOption(region, name, callingCode)
        }.sortedBy { it.name.lowercase(displayLocale) }
    }
}

private fun nationalNumberToE164(region: String, raw: String): String {
    val util = PhoneNumberUtil.getInstance()
    val national = raw.filter(Char::isDigit)
    require(national.isNotBlank()) { "رقم الجوال مطلوب" }
    val parsed = runCatching { util.parse(national, region) }.getOrElse { throw IllegalArgumentException("رقم الجوال غير صالح") }
    require(util.isValidNumber(parsed)) { "رقم الجوال غير صالح" }
    return util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
}

@Composable
private fun CountryDialPickerDialog(
    countries: List<CountryDialOption>,
    selectedRegion: String,
    onSelect: (CountryDialOption) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, countries) {
        val q = query.trim().lowercase()
        if (q.isBlank()) countries else countries.filter {
            it.name.lowercase().contains(q) ||
                it.region.lowercase().contains(q) ||
                "+${it.callingCode}".contains(q) ||
                it.callingCode.toString().contains(q)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 8.dp,
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(t("اختر الدولة ومفتاح الاتصال"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = t("إغلاق")) }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    label = { Text(t("ابحث عن دولة أو مفتاح")) },
                    placeholder = { Text(t("مثال: Saudi Arabia أو +966")) },
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 470.dp)) {
                    items(filtered, key = { it.region }) { country ->
                        ListItem(
                            headlineContent = { Text(country.name) },
                            supportingContent = { Text("${country.region}  +${country.callingCode}") },
                            trailingContent = {
                                if (country.region == selectedRegion) Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            },
                            modifier = Modifier.fillMaxWidth().clickable {
                                onSelect(country)
                                onDismiss()
                            },
                        )
                        HorizontalDivider()
                    }
                }
                if (filtered.isEmpty()) {
                    Text(t("لا توجد دولة مطابقة"), modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun PhoneAuthScreen(auth: AuthRepository, session: SessionStore, onSignedIn: () -> Unit) {
    val scope = rememberCoroutineScope()
    var createAccount by remember { mutableStateOf(false) }
    var recoveryMode by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var identifier by remember { mutableStateOf(session.accountUsername) }
    var username by remember { mutableStateOf(session.accountUsername) }
    var password by remember { mutableStateOf("") }
    var recoveryCodeInput by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    val countries = rememberCountryDialOptions()
    val initialRegion = remember(countries) {
        val deviceRegion = Locale.getDefault().country.uppercase()
        when {
            countries.any { it.region == deviceRegion } -> deviceRegion
            countries.any { it.region == "SA" } -> "SA"
            else -> countries.firstOrNull()?.region.orEmpty()
        }
    }
    var selectedRegion by remember { mutableStateOf(initialRegion) }
    val selectedCountry = countries.firstOrNull { it.region == selectedRegion } ?: countries.first()
    var countryPickerOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var issuedRecoveryCode by remember { mutableStateOf<String?>(null) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("ZoolChat", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(t("زول قريب.. مهما بَعُد"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))
            if (!recoveryMode) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(selected=createAccount,onClick={createAccount=true;error=null},shape=SegmentedButtonDefaults.itemShape(0,2)){Text(t("إنشاء حساب"))}
                    SegmentedButton(selected=!createAccount,onClick={createAccount=false;error=null},shape=SegmentedButtonDefaults.itemShape(1,2)){Text(t("تسجيل الدخول"))}
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick={recoveryMode=false;error=null}) { Icon(Icons.Rounded.ArrowBack,t("رجوع")) }
                    Text(t("استرداد الحساب"), style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(16.dp))

            when {
                recoveryMode -> {
                    OutlinedTextField(username,{username=it.lowercase()},label={Text(t("اسم المستخدم"))},prefix={Text("@")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(recoveryCodeInput,{recoveryCodeInput=it},label={Text(t("رمز الاسترداد"))},singleLine=true,modifier=Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(password,{password=it},label={Text(t("كلمة المرور الجديدة"))},singleLine=true,modifier=Modifier.fillMaxWidth(),visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation())
                }
                createAccount -> {
                    OutlinedTextField(
                        email, { email=it.trim() }, label={Text(t("البريد الإلكتروني"))}, singleLine=true, modifier=Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(username,{username=it.lowercase().replace("@","")},label={Text(t("اسم المستخدم"))},prefix={Text("@")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(password,{password=it},label={Text(t("كلمة المرور"))},singleLine=true,modifier=Modifier.fillMaxWidth(),visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation())
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = { countryPickerOpen = true },
                            modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    selectedCountry.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Text("+${selectedCountry.callingCode}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            }
                            Icon(Icons.Rounded.ArrowDropDown, contentDescription = t("اختر الدولة"))
                        }
                        OutlinedTextField(
                            value = phone,
                            onValueChange = { phone = it.filter(Char::isDigit).take(18) },
                            label = { Text(t("رقم الجوال بدون مفتاح الدولة")) },
                            placeholder = { Text(t("أدخل رقم الجوال بدون المفتاح")) },
                            singleLine = true,
                            modifier = Modifier.weight(1.25f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(t("يُستخدم رقم الجوال لاكتشاف جهات اتصالك التي لديها ZoolChat. لن يظهر رقمك في نتائج البحث باسم المستخدم."), style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    OutlinedTextField(
                        identifier, {identifier=it.trim()}, label={Text(t("البريد الإلكتروني أو اسم المستخدم"))}, singleLine=true, modifier=Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(password,{password=it},label={Text(t("كلمة المرور"))},singleLine=true,modifier=Modifier.fillMaxWidth(),visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation())
                }
            }

            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(t(it), color=MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick={
                loading=true; error=null
                scope.launch {
                    if (recoveryMode) {
                        auth.recoverFree(username,recoveryCodeInput,password)
                            .onSuccess{android.widget.Toast.makeText(auth.context,t("تم تسجيل الحساب بنجاح"),android.widget.Toast.LENGTH_SHORT).show();onSignedIn()}
                            .onFailure{error=it.message}
                    } else if(createAccount) {
                        val phoneE164 = runCatching { nationalNumberToE164(selectedCountry.region, phone) }
                            .getOrElse {
                                error = it.message ?: "رقم الجوال غير صالح"
                                loading = false
                                return@launch
                            }
                        auth.registerFree(email,username,password,phoneE164)
                            .onSuccess{android.widget.Toast.makeText(auth.context,t("تم تسجيل الحساب بنجاح"),android.widget.Toast.LENGTH_SHORT).show();onSignedIn()}
                            .onFailure{error=it.message}
                    } else {
                        auth.loginFree(identifier,password).onSuccess{onSignedIn()}.onFailure{error=it.message}
                    }
                    loading=false
                }
            },modifier=Modifier.fillMaxWidth().height(52.dp),enabled=!loading){
                if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Text(when { recoveryMode -> t("استرداد وتسجيل الدخول"); createAccount -> t("إنشاء الحساب"); else -> t("دخول") })
            }
            if (!createAccount && !recoveryMode) {
                TextButton(onClick={recoveryMode=true;error=null},modifier=Modifier.align(Alignment.End)){Text(t("نسيت كلمة المرور؟"))}
            }
            Spacer(Modifier.height(8.dp))
            Text(t("أهلاً بك في ZoolChat"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (countryPickerOpen) {
        CountryDialPickerDialog(
            countries = countries,
            selectedRegion = selectedCountry.region,
            onSelect = { selectedRegion = it.region; error = null },
            onDismiss = { countryPickerOpen = false },
        )
    }

    issuedRecoveryCode?.let { code ->
        AlertDialog(
            onDismissRequest={},
            title={Text(t("احفظ رمز الاسترداد"))},
            text={Column { Text(t("هذا الرمز هو طريقة استعادة الحساب المجانية إذا نسيت كلمة المرور. سيظهر الآن فقط.")); Spacer(Modifier.height(12.dp)); SelectionContainer { Text(code, fontWeight=FontWeight.Bold) } }},
            confirmButton={Button(onClick={issuedRecoveryCode=null;onSignedIn()}){Text(t("حفظته — متابعة"))}},
        )
    }
}

@Composable
fun ContactsConsentScreen(session: SessionStore, onDone: () -> Unit) {
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){session.contactsExplained=true;onDone()}
    Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){
        Icon(Icons.Rounded.Contacts,null,modifier=Modifier.size(70.dp),tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.height(20.dp));Text(t("اعثر على زملائك في ZoolChat"),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
        Spacer(Modifier.height(12.dp));Text(t("سيقرأ ZoolChat أرقام جهات الاتصال على هاتفك بعد موافقتك، ويحوّل كل رقم محلياً إلى بصمة SHA-256 للمطابقة. الأسماء والأرقام الخام لا تُرفع مع طلب اكتشاف جهات الاتصال."))
        Spacer(Modifier.height(24.dp));Button(onClick={launcher.launch(Manifest.permission.READ_CONTACTS)},modifier=Modifier.fillMaxWidth()){Text(t("السماح بجهات الاتصال"))};TextButton(onClick={session.contactsExplained=true;onDone()}){Text(t("ليس الآن"))}
    }
}

@Composable
fun NotificationConsentScreen(session: SessionStore, onDone: () -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { session.notificationsExplained = true; onDone() }
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT < 33) { session.notificationsExplained = true; onDone() } }
    if (Build.VERSION.SDK_INT >= 33) {
        Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){
            Icon(Icons.Rounded.NotificationsActive,null,modifier=Modifier.size(70.dp),tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.height(20.dp));Text(t("لا تفوّت رسالة أو مكالمة"),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
            Spacer(Modifier.height(12.dp));Text(t("يساعد السماح بالإشعارات على تنبيهك بالرسائل والمكالمات الواردة حتى عندما يكون ZoolChat في الخلفية."))
            Spacer(Modifier.height(24.dp));Button(onClick={launcher.launch(Manifest.permission.POST_NOTIFICATIONS)},modifier=Modifier.fillMaxWidth()){Text(t("السماح بالإشعارات"))};TextButton(onClick={session.notificationsExplained=true;onDone()}){Text(t("ليس الآن"))}
        }
    }
}
