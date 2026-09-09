package com.zoolchat.app.ui.screens

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.zoolchat.app.auth.AuthRepository
import com.zoolchat.app.auth.SessionStore
import com.zoolchat.app.auth.registerAccountCompatible
import com.zoolchat.app.ui.i18n.AppLanguage
import com.zoolchat.app.ui.i18n.I18n
import com.zoolchat.app.ui.i18n.t
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun OnboardingScreen(onContinue: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.Forum, null, modifier = Modifier.size(80.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(22.dp))
        Text(t("مرحباً بك في ZoolChat"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(t("زول قريب.. مهما بَعُد"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(26.dp))
        Text(t("مراسلات، مكالمات صوت وفيديو، اجتماعات وقصص في مكان واحد. سنطلب الصلاحيات عند الحاجة فقط، ويمكنك تغييرها لاحقاً من الإعدادات."))
        Spacer(Modifier.height(28.dp))
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(t("متابعة")) }
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
    require(national.isNotBlank()) { authLabel("رقم الجوال مطلوب", "Mobile number is required") }
    val parsed = runCatching { util.parse(national, region) }
        .getOrElse { throw IllegalArgumentException(authLabel("رقم الجوال غير صالح", "Invalid mobile number")) }
    require(util.isValidNumber(parsed)) { authLabel("رقم الجوال غير صالح", "Invalid mobile number") }
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
                    Text(
                        t("اختر الدولة ومفتاح الاتصال"),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
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
                                if (country.region == selectedRegion) {
                                    Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
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

private fun authLabel(ar: String, en: String) = if (I18n.language == AppLanguage.AR) ar else en

private fun passwordLengthError(password: String): String? = when {
    password.length < 8 -> authLabel("كلمة المرور يجب أن تكون 8 أحرف على الأقل", "Password must be at least 8 characters")
    password.length > 128 -> authLabel("كلمة المرور يجب ألا تتجاوز 128 حرفاً", "Password must not exceed 128 characters")
    else -> null
}

@Composable
fun PhoneAuthScreen(auth: AuthRepository, session: SessionStore, onSignedIn: () -> Unit) {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf("login") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var codeSent by remember { mutableStateOf(false) }
    var resendCooldownSeconds by remember { mutableIntStateOf(0) }
    var twoFactorChallenge by remember { mutableStateOf<String?>(null) }
    var twoFactorCode by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val countries = rememberCountryDialOptions()
    var region by remember { mutableStateOf("SA") }
    val country = countries.firstOrNull { it.region == region } ?: countries.first()
    var picker by remember { mutableStateOf(false) }

    fun changeMode(value: String) {
        mode = value
        password = ""
        confirm = ""
        code = ""
        codeSent = false
        resendCooldownSeconds = 0
        twoFactorChallenge = null
        twoFactorCode = ""
        com.zoolchat.app.auth.RecentAuthSecret.clear()
        error = null
        feedback = null
    }

    BackHandler(enabled = (mode != "login" || twoFactorChallenge != null) && !loading) {
        if (twoFactorChallenge != null) { twoFactorChallenge = null; twoFactorCode = ""; com.zoolchat.app.auth.RecentAuthSecret.clear() }
        else changeMode("login")
    }

    LaunchedEffect(resendCooldownSeconds) {
        if (resendCooldownSeconds > 0) {
            delay(1_000)
            resendCooldownSeconds -= 1
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("ZoolChat", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            when (mode) {
                "register" -> authLabel("تسجيل حساب جديد", "Create new account")
                "recover" -> authLabel("استرداد كلمة المرور", "Password recovery")
                else -> authLabel("تسجيل الدخول", "Login")
            },
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(16.dp))

        if (mode != "login") {
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text(authLabel("البريد الإلكتروني", "Email")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading,
            )
            Spacer(Modifier.height(8.dp))
        }

        if (mode != "recover") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { picker = true },
                    enabled = !loading,
                    modifier = Modifier.width(132.dp).height(56.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text(if (country.region == "SA") "🇸🇦 +966" else "+${country.callingCode}", maxLines = 1)
                    Spacer(Modifier.width(2.dp))
                    Icon(Icons.Rounded.ArrowDropDown, contentDescription = authLabel("اختيار الدولة", "Choose country"))
                }
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it.filter(Char::isDigit).take(18) },
                    label = { Text(authLabel("رقم الجوال", "Mobile number")) },
                    placeholder = { Text(authLabel("05xxxxxxxx", "Mobile number")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    enabled = !loading,
                )
            }
            Text(
                country.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
            Spacer(Modifier.height(8.dp))
        }

        if (mode == "recover" && codeSent) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter(Char::isDigit).take(8) },
                label = { Text(authLabel("الرمز المؤقت", "Temporary code")) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading,
            )
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = {
                    loading = true
                    error = null
                    feedback = null
                    scope.launch {
                        try {
                            auth.requestPasswordCode(email).getOrThrow()
                            code = ""
                            resendCooldownSeconds = 60
                            feedback = authLabel(
                                "تم طلب رمز مؤقت جديد. إذا كان البريد موثقاً ومسجلاً فسيصلك عبر البريد الإلكتروني.",
                                "A new temporary code was requested. If the email is registered and verified, it will be sent by email.",
                            )
                        } catch (e: Exception) {
                            error = e.message ?: authLabel("تعذر إعادة إرسال الرمز", "Unable to resend code")
                        } finally {
                            loading = false
                        }
                    }
                },
                enabled = !loading && resendCooldownSeconds == 0,
            ) {
                Text(
                    if (resendCooldownSeconds > 0)
                        authLabel("إعادة إرسال الرمز بعد ${resendCooldownSeconds}ث", "Resend code in ${resendCooldownSeconds}s")
                    else authLabel("إعادة إرسال الرمز", "Resend code")
                )
            }
            Spacer(Modifier.height(4.dp))
        }

        if (mode != "recover" || codeSent) {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(authLabel("كلمة المرور", "Password")) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading,
            )
            if (mode != "login") {
                Text(
                    authLabel(
                        "من 8 إلى 128 حرفاً — الحروف الكبيرة والصغيرة والأرقام والرموز كلها مسموحة",
                        "8–128 characters — uppercase, lowercase, numbers and symbols are all supported",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text(authLabel("تأكيد كلمة المرور", "Confirm password")) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading,
                )
            }
        }

        if (twoFactorChallenge != null) {
            Text(authLabel("أرسلنا رمز التحقق إلى بريدك الإلكتروني الموثّق.", "We sent a verification code to your verified email."), color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = twoFactorCode, onValueChange = { twoFactorCode = it.filter(Char::isDigit).take(8) },
                label = { Text(authLabel("رمز التحقق بخطوتين", "Two-step verification code")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !loading,
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        feedback?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(16.dp))

        Button(
            enabled = !loading,
            onClick = {
                loading = true
                error = null
                scope.launch {
                    try {
                        if (mode != "login" && (mode != "recover" || codeSent)) {
                            passwordLengthError(password)?.let { throw IllegalArgumentException(it) }
                            require(password == confirm) {
                                authLabel("كلمتا المرور غير متطابقتين", "Passwords do not match")
                            }
                        }
                        when (mode) {
                            "register" -> {
                                val e164 = nationalNumberToE164(country.region, phone)
                                registerAccountCompatible(auth, session, email, password, e164).getOrThrow()
                                Toast.makeText(
                                    auth.context,
                                    authLabel("تم تسجيل الحساب بنجاح", "Account created successfully"),
                                    Toast.LENGTH_SHORT,
                                ).show()
                                onSignedIn()
                            }
                            "recover" -> if (!codeSent) {
                                auth.requestPasswordCode(email).getOrThrow()
                                codeSent = true
                                resendCooldownSeconds = 60
                                feedback = authLabel(
                                    "إذا كان البريد موثقاً ومسجلاً، سيصلك رمز صالح لمدة 15 دقيقة.",
                                    "If this email is registered and verified, a code valid for 15 minutes will arrive.",
                                )
                            } else {
                                auth.resetPassword(email, code, password).getOrThrow()
                                changeMode("login")
                                feedback = authLabel(
                                    "تم تغيير كلمة المرور. سجل الدخول بكلمة المرور الجديدة.",
                                    "Password changed. Sign in with your new password.",
                                )
                            }
                            else -> {
                                if (twoFactorChallenge != null) {
                                    auth.completeTwoFactorLogin(twoFactorChallenge!!, twoFactorCode).getOrThrow()
                                    onSignedIn()
                                } else {
                                    passwordLengthError(password)?.let { throw IllegalArgumentException(it) }
                                    val challenge = auth.loginFree(nationalNumberToE164(country.region, phone), password).getOrThrow()
                                    if (challenge != null) {
                                        twoFactorChallenge = challenge
                                        twoFactorCode = ""
                                    } else onSignedIn()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        error = e.message ?: authLabel("تعذر إكمال الطلب", "Unable to complete request")
                    } finally {
                        loading = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    when (mode) {
                        "register" -> authLabel("إنشاء الحساب", "Create account")
                        "recover" -> if (codeSent) authLabel("تغيير كلمة المرور", "Reset password") else authLabel("إرسال الرمز", "Send code")
                        else -> authLabel("دخول", "Login")
                    }
                )
            }
        }

        if (mode == "login") {
            TextButton(onClick = { changeMode("recover") }, enabled = !loading) {
                Text(authLabel("نسيت كلمة المرور؟", "Forgot password?"))
            }
            TextButton(onClick = { changeMode("register") }, enabled = !loading) {
                Text(authLabel("تسجيل حساب جديد", "Create new account"))
            }
        } else {
            TextButton(onClick = { changeMode("login") }, enabled = !loading) {
                Text(authLabel("العودة لتسجيل الدخول", "Back to login"))
            }
        }
    }

    if (picker) {
        CountryDialPickerDialog(
            countries = countries,
            selectedRegion = country.region,
            onSelect = { region = it.region },
            onDismiss = { picker = false },
        )
    }
}

@Composable
fun ContactsConsentScreen(session: SessionStore, onDone: () -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        session.contactsExplained = true
        onDone()
    }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.Contacts, null, modifier = Modifier.size(70.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp))
        Text(t("اعثر على زملائك في ZoolChat"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(t("سيقرأ ZoolChat أرقام جهات الاتصال على هاتفك بعد موافقتك، ويحوّل كل رقم محلياً إلى بصمة SHA-256 للمطابقة. الأسماء والأرقام الخام لا تُرفع مع طلب اكتشاف جهات الاتصال."))
        Spacer(Modifier.height(24.dp))
        Button(onClick = { launcher.launch(Manifest.permission.READ_CONTACTS) }, modifier = Modifier.fillMaxWidth()) {
            Text(t("السماح بجهات الاتصال"))
        }
        TextButton(onClick = { session.contactsExplained = true; onDone() }) { Text(t("ليس الآن")) }
    }
}

@Composable
fun NotificationConsentScreen(session: SessionStore, onDone: () -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        session.notificationsExplained = true
        onDone()
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT < 33) {
            session.notificationsExplained = true
            onDone()
        }
    }
    if (Build.VERSION.SDK_INT >= 33) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Rounded.NotificationsActive, null, modifier = Modifier.size(70.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(20.dp))
            Text(t("لا تفوّت رسالة أو مكالمة"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(t("يساعد السماح بالإشعارات على تنبيهك بالرسائل والمكالمات الواردة حتى عندما يكون ZoolChat في الخلفية."))
            Spacer(Modifier.height(24.dp))
            Button(onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }, modifier = Modifier.fillMaxWidth()) {
                Text(t("السماح بالإشعارات"))
            }
            TextButton(onClick = { session.notificationsExplained = true; onDone() }) { Text(t("ليس الآن")) }
        }
    }
}