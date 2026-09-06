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

private fun authLabel(ar:String,en:String)=if(I18n.language==AppLanguage.AR) ar else en

@Composable
fun PhoneAuthScreen(auth: AuthRepository, session: SessionStore, onSignedIn: () -> Unit) {
    val scope=rememberCoroutineScope()
    var mode by remember { mutableStateOf("login") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var codeSent by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val countries=rememberCountryDialOptions()
    var region by remember { mutableStateOf(Locale.getDefault().country.takeIf { r -> countries.any { it.region==r } } ?: "SA") }
    val country=countries.firstOrNull { it.region==region } ?: countries.first()
    var picker by remember { mutableStateOf(false) }
    fun changeMode(value:String) { mode=value; password=""; confirm=""; code=""; codeSent=false; error=null; feedback=null }
    androidx.activity.compose.BackHandler(enabled=mode!="login"&&!loading) { changeMode("login") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.Center) {
        Text("ZoolChat",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
        Text(when(mode){"register"->authLabel("تسجيل حساب جديد","Create new account");"recover"->authLabel("استرداد كلمة المرور","Password recovery");else->authLabel("تسجيل الدخول","Login")},style=MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        if(mode!="login") {
            OutlinedTextField(email,{email=it.trim()},label={Text(authLabel("البريد الإلكتروني","Email"))},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email),singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!loading)
        }
        if(mode!="recover") {
            OutlinedButton(onClick={picker=true},enabled=!loading) { Text("${country.name}  +${country.callingCode}") }
            OutlinedTextField(phone,{phone=it.filter(Char::isDigit).take(18)},label={Text(authLabel("رقم الجوال","Mobile number"))},placeholder={Text(authLabel("أدخل رقم الجوال بدون المفتاح","Enter mobile number without country code"))},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Phone),singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!loading)
        }
        if(mode=="recover"&&codeSent) {
            OutlinedTextField(code,{code=it.filter(Char::isDigit).take(8)},label={Text(authLabel("الرمز المؤقت","Temporary code"))},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth(),enabled=!loading)
        }
        if(mode!="recover"||codeSent) {
            OutlinedTextField(password,{password=it},label={Text(authLabel("كلمة المرور","Password"))},singleLine=true,visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth(),enabled=!loading)
            if(mode!="login") OutlinedTextField(confirm,{confirm=it},label={Text(authLabel("تأكيد كلمة المرور","Confirm password"))},singleLine=true,visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth(),enabled=!loading)
        }
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        feedback?.let { Text(it,color=MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(16.dp))
        Button(enabled=!loading,onClick={
            loading=true; error=null
            scope.launch {
                try {
                    if(mode!="login"&&(mode!="recover"||codeSent)) require(password==confirm) { authLabel("كلمتا المرور غير متطابقتين","Passwords do not match") }
                    when(mode) {
                        "register" -> {
                            auth.registerAccount(email,password,nationalNumberToE164(country.region,phone)).getOrThrow()
                            android.widget.Toast.makeText(auth.context,authLabel("تم تسجيل الحساب بنجاح","Account created successfully"),android.widget.Toast.LENGTH_SHORT).show()
                            onSignedIn()
                        }
                        "recover" -> if(!codeSent) {
                            auth.requestPasswordCode(email).getOrThrow(); codeSent=true
                            feedback=authLabel("إذا كان البريد موثقاً ومسجلاً، سيصلك رمز صالح لمدة 15 دقيقة.","If this email is registered and verified, a code valid for 15 minutes will arrive.")
                        } else {
                            auth.resetPassword(email,code,password).getOrThrow();changeMode("login")
                            feedback=authLabel("تم تغيير كلمة المرور. سجل الدخول بكلمة المرور الجديدة.","Password changed. Sign in with your new password.")
                        }
                        else -> { auth.loginFree(nationalNumberToE164(country.region,phone),password).getOrThrow();onSignedIn() }
                    }
                } catch(e:Exception) { error=e.message ?: authLabel("تعذر إكمال الطلب","Unable to complete request") }
                finally { loading=false }
            }
        },modifier=Modifier.fillMaxWidth()) {
            if(loading) CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp)
            else Text(when(mode){"register"->authLabel("إنشاء الحساب","Create account");"recover"->if(codeSent)authLabel("تغيير كلمة المرور","Reset password") else authLabel("إرسال الرمز","Send code");else->authLabel("دخول","Login")})
        }
        if(mode=="login") {
            TextButton(onClick={changeMode("recover")},enabled=!loading){Text(authLabel("نسيت كلمة المرور؟","Forgot password?"))}
            TextButton(onClick={changeMode("register")},enabled=!loading){Text(authLabel("تسجيل حساب جديد","Create new account"))}
        } else TextButton(onClick={changeMode("login")},enabled=!loading){Text(authLabel("العودة لتسجيل الدخول","Back to login"))}
    }
    if(picker) CountryDialPickerDialog(countries,country.region,{region=it.region},{picker=false})
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
