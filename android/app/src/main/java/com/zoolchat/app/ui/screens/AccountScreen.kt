package com.zoolchat.app.ui.screens

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.zoolchat.app.crypto.E2eeManager
import com.zoolchat.app.network.ApiClient
import com.zoolchat.app.ui.i18n.t
import kotlinx.coroutines.launch
import org.json.JSONObject

private data class DeviceRow(val id:String,val name:String,val lastSeen:String)
private const val ACCOUNT_ICON_DP = 24

@Composable
fun AccountScreen(
    api: ApiClient,
    e2ee: E2eeManager,
    username: String,
    onBack: () -> Unit,
    onEditIdentity: () -> Unit,
    onLogout: () -> Unit,
    onDeleteAccount: () -> Unit,
) {
    val context=LocalContext.current
    val prefs=remember { context.getSharedPreferences("zoolchat_user_settings",Context.MODE_PRIVATE) }
    val scope=rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<DeviceRow>>(emptyList()) }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var emailVerified by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var infoDialog by remember { mutableStateOf<String?>(null) }
    var showDelete by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var showPhone by remember { mutableStateOf(false) }
    var showBackup by remember { mutableStateOf(false) }
    var securityNotifications by remember { mutableStateOf(prefs.getBoolean("security_notifications_enabled",true)) }
    var exporting by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val createExport=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->
        val payload=exporting
        if(uri!=null&&payload!=null) runCatching { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use{it.write(payload)} }
        exporting=null
    }
    fun safeError(message:String?=null){error=message?:t("تعذر إكمال الطلب")}
    fun refreshDevices(){scope.launch{runCatching{api.devices()}.onSuccess{arr->devices=(0 until arr.length()).map{i->val o=arr.getJSONObject(i);DeviceRow(o.getString("id"),o.optString("deviceName","Android"),o.optString("lastSeenAt"))}}.onFailure{safeError()}}}
    fun refreshMe(){scope.launch{runCatching{api.get("/api/me").getJSONObject("user")}.onSuccess{u->email=u.optString("email").takeUnless{it=="null"}.orEmpty();phone=u.optString("phoneE164").takeUnless{it=="null"}.orEmpty();emailVerified=u.optBoolean("emailVerified",false)}.onFailure{safeError()}}}
    LaunchedEffect(Unit){refreshDevices();refreshMe()}

    Column(Modifier.fillMaxSize()){
        Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(horizontal=6.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton(onClick=onBack){Icon(Icons.Rounded.ArrowBack,t("رجوع"),modifier=Modifier.size(24.dp))}
            Text(t("الحساب"),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
        }
        error?.let{Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(horizontal=18.dp,vertical=6.dp))}
        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=18.dp)){
            item{
                AccountRow(Icons.Rounded.PersonAdd,t("إضافة حساب"),t("استخدام حساب ZoolChat آخر على هذا الجهاز")){
                    infoDialog="multi"
                }
                AccountSection(t("تسجيل الدخول والأمان"))
                AccountRow(Icons.Rounded.Passkey,t("مفاتيح المرور"),t("تسجيل دخول بدون كلمة مرور عند توفر الدعم")){infoDialog="passkey"}
                AccountRow(Icons.Rounded.Password,t("كلمة المرور"),t("تغيير كلمة مرور الحساب")){showPassword=true}
                AccountRow(Icons.Rounded.Email,t("البريد الإلكتروني"),if(email.isBlank())t("إضافة بريد إلكتروني") else if(emailVerified)t("موثّق") else t("غير موثّق"),onClick=onEditIdentity)
                AccountRow(Icons.Rounded.EnhancedEncryption,t("التحقق بخطوتين"),t("طبقة أمان إضافية للحساب")){infoDialog="2fa"}
                AccountToggle(Icons.Rounded.Security,t("إشعارات الأمان"),t("تنبيهك عند تغير مفاتيح الأمان المهمة"),securityNotifications){
                    securityNotifications=it
                    prefs.edit().putBoolean("security_notifications_enabled",it).apply()
                }
                AccountRow(Icons.Rounded.CloudDone,t("حماية سجل الرسائل المشفرة"),t("حفظ مفتاح التشفير بنسخة مشفرة لاستعادته بعد إعادة التثبيت")){showBackup=true}

                AccountSection(t("حسابك"))
                AccountRow(Icons.Rounded.AlternateEmail,t("اسم المستخدم"),if(username.isBlank())t("إضافة اسم مستخدم") else "@$username",onClick=onEditIdentity)
                AccountRow(Icons.Rounded.PhoneForwarded,t("تغيير رقم الهاتف"),phone.ifBlank{t("تحديث رقم الحساب")}){showPhone=true}

                AccountSection(t("الأجهزة والبيانات"))
            }
            items(devices,key={it.id}){d->
                ListItem(
                    headlineContent={Text(d.name)},
                    supportingContent={Text(d.lastSeen.ifBlank{t("نشط")})},
                    leadingContent={Icon(Icons.Rounded.PhoneAndroid,null,modifier=Modifier.size(ACCOUNT_ICON_DP.dp))},
                    trailingContent={IconButton(onClick={scope.launch{runCatching{api.revokeDevice(d.id)}.onSuccess{refreshDevices()}.onFailure{safeError()}}}){Icon(Icons.Rounded.LinkOff,t("إلغاء ربط الجهاز"))}}
                )
            }
            item{
                AccountRow(Icons.Rounded.Download,t("تصدير بياناتي"),t("حفظ نسخة من بيانات الحساب")){
                    scope.launch{runCatching{api.exportAccount().toString(2)}.onSuccess{exporting=it;createExport.launch("zoolchat-export.json")}.onFailure{safeError()}}
                }
                HorizontalDivider(Modifier.padding(horizontal=16.dp,vertical=8.dp))
                AccountRow(Icons.Rounded.DeleteForever,t("حذف حساب ZoolChat نهائياً"),t("حذف الحساب والبيانات المرتبطة به"),danger=true,onClick={showDelete=true})
                AccountRow(Icons.Rounded.Logout,t("تسجيل الخروج"),t("الخروج من هذا الحساب على هذا الجهاز"),danger=true,onClick=onLogout)
            }
        }
    }

    if(infoDialog!=null) AlertDialog(
        onDismissRequest={infoDialog=null},
        title={Text(when(infoDialog){"multi"->t("إضافة حساب");"passkey"->t("مفاتيح المرور");else->t("التحقق بخطوتين")})},
        text={Text(when(infoDialog){
            "multi"->t("تعدد الحسابات يحتاج تخزين جلسات منفصلة والتبديل بينها بأمان. لن يظهر كخيار فعّال قبل اكتمال هذا المسار حتى لا نفقد جلستك الحالية.")
            "passkey"->t("مفاتيح المرور تحتاج ربط Android Credential Manager بخادم WebAuthn. الخيار ظاهر ضمن خطة الحساب لكنه لن يدّعي أنه مفعّل قبل اكتمال التحقق الحقيقي.")
            else->t("التحقق بخطوتين يحتاج تحدياً حقيقياً أثناء تسجيل الدخول واسترداداً آمناً. لن نفعّل مفتاحاً شكلياً لا يحمي الحساب فعلياً.")
        })},
        confirmButton={TextButton(onClick={infoDialog=null}){Text(t("حسناً"))}},
    )

    if(showPassword) PasswordChangeDialog(
        busy=busy,
        onDismiss={if(!busy)showPassword=false},
        onSubmit={current,next->
            busy=true;error=null
            scope.launch{
                runCatching{
                    // Make sure the current local E2EE identity already has a recoverable backup.
                    if(e2ee.hasLocalIdentity()) e2ee.backupCurrentIdentityWithVerifiedPassword(current)
                    api.put("/api/me/password",JSONObject().put("currentPassword",current).put("newPassword",next))
                }.onSuccess{
                    showPassword=false
                    busy=false
                    onLogout()
                }.onFailure{busy=false;safeError(t("تعذر تغيير كلمة المرور. تحقق من كلمة المرور الحالية."))}
            }
        },
    )

    if(showPhone) PhoneChangeDialog(
        initial=phone,
        busy=busy,
        onDismiss={if(!busy)showPhone=false},
        onSubmit={next,password->
            busy=true;error=null
            scope.launch{
                runCatching{api.put("/api/me/phone",JSONObject().put("phoneE164",next).put("password",password))}
                    .onSuccess{phone=next;showPhone=false;busy=false}
                    .onFailure{busy=false;safeError(t("تعذر تغيير رقم الهاتف. تحقق من الرقم وكلمة المرور."))}
            }
        },
    )

    if(showBackup) EncryptionBackupDialog(
        busy=busy,
        onDismiss={if(!busy)showBackup=false},
        onSubmit={password->
            busy=true;error=null
            scope.launch{
                runCatching{e2ee.backupCurrentIdentityWithVerifiedPassword(password)}
                    .onSuccess{showBackup=false;busy=false;error=null}
                    .onFailure{busy=false;safeError(t("تعذر إنشاء النسخة المشفرة. تحقق من كلمة المرور."))}
            }
        },
    )

    if(showDelete) AlertDialog(
        onDismissRequest={showDelete=false},
        title={Text(t("حذف الحساب نهائياً؟"))},
        text={Text(t("سيُحذف الحساب والرسائل والبيانات المرتبطة به نهائياً. لا يمكن التراجع عن هذه العملية."))},
        confirmButton={TextButton(onClick={showDelete=false;onDeleteAccount()},colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)){Text(t("حذف نهائي"))}},
        dismissButton={TextButton(onClick={showDelete=false}){Text(t("إلغاء"))}},
    )
}

@Composable
private fun PasswordChangeDialog(busy:Boolean,onDismiss:()->Unit,onSubmit:(String,String)->Unit){
    var current by remember{mutableStateOf("")};var next by remember{mutableStateOf("")};var confirm by remember{mutableStateOf("")};var localError by remember{mutableStateOf<String?>(null)}
    AlertDialog(onDismissRequest=onDismiss,title={Text(t("تغيير كلمة المرور"))},text={Column{
        OutlinedTextField(current,{current=it},label={Text(t("كلمة المرور الحالية"))},visualTransformation=PasswordVisualTransformation(),singleLine=true,enabled=!busy)
        OutlinedTextField(next,{next=it},label={Text(t("كلمة المرور الجديدة"))},visualTransformation=PasswordVisualTransformation(),singleLine=true,enabled=!busy)
        OutlinedTextField(confirm,{confirm=it},label={Text(t("تأكيد كلمة المرور الجديدة"))},visualTransformation=PasswordVisualTransformation(),singleLine=true,enabled=!busy)
        Text(t("8–128 حرفاً، وتدعم الحروف الكبيرة والصغيرة والأرقام والرموز."),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        localError?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={Button(enabled=!busy,onClick={if(next.length !in 8..128||next!=confirm){localError=t("تحقق من كلمة المرور الجديدة وتأكيدها") }else onSubmit(current,next)}){Text(t("تغيير"))}},dismissButton={TextButton(enabled=!busy,onClick=onDismiss){Text(t("إلغاء"))}})
}

@Composable
private fun PhoneChangeDialog(initial:String,busy:Boolean,onDismiss:()->Unit,onSubmit:(String,String)->Unit){
    var next by remember(initial){mutableStateOf(initial)};var password by remember{mutableStateOf("")};var localError by remember{mutableStateOf<String?>(null)}
    AlertDialog(onDismissRequest=onDismiss,title={Text(t("تغيير رقم الهاتف"))},text={Column{
        OutlinedTextField(next,{next=it.trim()},label={Text(t("رقم الهاتف بالصيغة الدولية"))},placeholder={Text("+xxxxxxxxxxxx")},singleLine=true,enabled=!busy)
        OutlinedTextField(password,{password=it},label={Text(t("كلمة المرور"))},visualTransformation=PasswordVisualTransformation(),singleLine=true,enabled=!busy)
        localError?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={Button(enabled=!busy,onClick={if(!Regex("^\\+[1-9]\\d{5,14}$").matches(next)||password.length<8)localError=t("تحقق من الرقم وكلمة المرور") else onSubmit(next,password)}){Text(t("حفظ"))}},dismissButton={TextButton(enabled=!busy,onClick=onDismiss){Text(t("إلغاء"))}})
}

@Composable
private fun EncryptionBackupDialog(busy:Boolean,onDismiss:()->Unit,onSubmit:(String)->Unit){
    var password by remember{mutableStateOf("")}
    AlertDialog(onDismissRequest=onDismiss,title={Text(t("حماية سجل الرسائل المشفرة"))},text={Column{
        Text(t("سيتم تشفير مفتاح رسائلك بكلمة مرور الحساب قبل حفظ النسخة. الخادم لا يحصل على كلمة المرور أو المفتاح الخاص بصورته المقروءة."))
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(password,{password=it},label={Text(t("كلمة المرور"))},visualTransformation=PasswordVisualTransformation(),singleLine=true,enabled=!busy)
    }},confirmButton={Button(enabled=!busy&&password.length>=8,onClick={onSubmit(password)}){Text(t("إنشاء نسخة مشفرة"))}},dismissButton={TextButton(enabled=!busy,onClick=onDismiss){Text(t("إلغاء"))}})
}

@Composable
private fun AccountSection(text:String){
    Text(text,style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary,modifier=Modifier.padding(start=18.dp,end=18.dp,top=16.dp,bottom=4.dp))
}

@Composable
private fun AccountRow(icon:ImageVector,title:String,subtitle:String,danger:Boolean=false,onClick:()->Unit){
    val tint=if(danger)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().heightIn(min=64.dp).clickable(onClick=onClick).padding(horizontal=18.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
        Icon(icon,null,tint=tint,modifier=Modifier.size(ACCOUNT_ICON_DP.dp));Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)){Text(title,fontWeight=FontWeight.Medium,color=if(danger)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface);Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        Icon(Icons.Rounded.ChevronLeft,null,tint=MaterialTheme.colorScheme.onSurface.copy(alpha=.35f),modifier=Modifier.size(20.dp))
    }
}

@Composable
private fun AccountToggle(icon:ImageVector,title:String,subtitle:String,checked:Boolean,onChecked:(Boolean)->Unit){
    Row(Modifier.fillMaxWidth().heightIn(min=64.dp).padding(horizontal=18.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
        Icon(icon,null,tint=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.size(ACCOUNT_ICON_DP.dp));Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)){Text(title,fontWeight=FontWeight.Medium);Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        Switch(checked=checked,onCheckedChange=onChecked)
    }
}
