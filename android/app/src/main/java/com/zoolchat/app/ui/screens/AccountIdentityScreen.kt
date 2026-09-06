package com.zoolchat.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zoolchat.app.network.ApiClient
import com.zoolchat.app.ui.i18n.I18n
import com.zoolchat.app.ui.i18n.AppLanguage
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLEncoder

@Composable
fun AccountIdentityScreen(api:ApiClient,onBack:()->Unit,onChanged:()->Unit) {
    fun label(ar:String,en:String)=if(I18n.language==AppLanguage.AR) ar else en
    val scope=rememberCoroutineScope()
    val context=LocalContext.current
    val prefs=remember{context.getSharedPreferences("zoolchat_user_settings",Context.MODE_PRIVATE)}
    var displayName by remember { mutableStateOf("") }
    var about by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    fun run(action:suspend()->Unit) { busy=true;scope.launch { try { action() } catch(_:Exception) { result=label("تعذر إكمال الطلب. تحقق من البيانات وحاول لاحقاً.","Unable to complete request. Check your details and try later.") } finally { busy=false } } }

    val photoLauncher=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri:Uri?->
        if(uri!=null){
            runCatching{context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}
            run {
                val mime=context.contentResolver.getType(uri)?:"image/jpeg"
                val media=api.uploadMedia(uri,mime,"profile-photo")
                api.updateProfile(photoMediaId=media.getString("id"))
                prefs.edit().putString("profile_photo",uri.toString()).apply()
                onChanged()
                result=label("تم تحديث صورة الملف الشخصي","Profile photo updated")
            }
        }
    }

    LaunchedEffect(Unit) { run {
        val user=api.get("/api/me").getJSONObject("user")
        displayName=user.optString("displayName").takeUnless{it=="null"}.orEmpty()
        about=user.optString("about").takeUnless{it=="null"}.orEmpty()
        username=user.optString("username").takeUnless { it=="null" }.orEmpty()
        email=user.optString("email").takeUnless { it=="null" }.orEmpty()
        result=if(user.optBoolean("emailVerified"))label("البريد موثق","Email verified") else label("وثق بريدك لتتمكن من استرداد كلمة المرور.","Verify your email to enable password recovery.")
    } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        TextButton(onClick=onBack){Text(label("رجوع","Back"))}
        Text(label("الملف وبيانات الحساب","Profile and account"),style=MaterialTheme.typography.headlineSmall)

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(displayName,{displayName=it.take(80)},label={Text(label("الاسم الظاهر","Display name"))},modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!busy)
        OutlinedTextField(about,{about=it.take(160)},label={Text(label("النبذة","About"))},modifier=Modifier.fillMaxWidth(),enabled=!busy,maxLines=3)
        Button(enabled=!busy&&displayName.trim().isNotBlank(),onClick={run{
            api.updateProfile(displayName=displayName.trim(),about=about.trim());onChanged();result=label("تم حفظ الملف الشخصي","Profile saved")
        }}){Text(label("حفظ الاسم والنبذة","Save name and about"))}
        OutlinedButton(enabled=!busy,onClick={photoLauncher.launch(arrayOf("image/*"))}){
            Icon(Icons.Rounded.CameraAlt,null);Spacer(Modifier.width(6.dp));Text(label("تغيير صورة الملف الشخصي","Change profile photo"))
        }

        HorizontalDivider(Modifier.padding(vertical=16.dp))
        OutlinedTextField(username,{username=it.lowercase()},label={Text(label("اسم المستخدم","Username"))},modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!busy)
        Button(enabled=!busy,onClick={run {
            val q=URLEncoder.encode(username,"UTF-8")
            if(!api.get("/api/me/availability?username=$q").getBoolean("usernameAvailable")) { result=label("اسم المستخدم مستخدم بالفعل","Username is already used");return@run }
            api.put("/api/me",JSONObject().put("username",username));onChanged();result=label("تم حفظ اسم المستخدم","Username saved")
        }}){Text(label("تحقق واحفظ اسم المستخدم","Check and save username"))}
        OutlinedTextField(email,{email=it.trim();pending=false;code=""},label={Text(label("البريد الإلكتروني","Email"))},modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!busy)
        Button(enabled=!busy,onClick={run {
            val q=URLEncoder.encode(email,"UTF-8")
            if(!api.get("/api/me/availability?email=$q").getBoolean("emailAvailable")) { result=label("البريد الإلكتروني مستخدم بالفعل","Email is already used");return@run }
            api.post("/api/me/email/request",JSONObject().put("email",email));pending=true
            result=label("أدخل الرمز المرسل إلى بريدك. لن يتغير بريدك حتى التحقق.","Enter the code sent to your email. Your email changes only after verification.")
        }}){Text(label("إرسال رمز التحقق","Send verification code"))}
        if(pending) {
            OutlinedTextField(code,{code=it.filter(Char::isDigit).take(8)},label={Text(label("رمز التحقق","Verification code"))},modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!busy)
            Button(enabled=!busy,onClick={run {
                api.post("/api/me/email/verify",JSONObject().put("email",email).put("code",code));pending=false;onChanged();result=label("تم توثيق البريد","Email verified")
            }}){Text(label("تأكيد البريد","Verify email"))}
        }
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=12.dp))
        Text(result,modifier=Modifier.padding(top=12.dp))
    }
}
