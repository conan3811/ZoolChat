package com.zoolchat.app.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.zoolchat.app.auth.SessionStore
import com.zoolchat.app.network.ApiClient
import com.zoolchat.app.network.ApiException
import com.zoolchat.app.ui.i18n.AppLanguage
import com.zoolchat.app.ui.i18n.I18n
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLEncoder

private fun setupLabel(ar:String,en:String)=if(I18n.language==AppLanguage.AR) ar else en
private val usernamePattern=Regex("^[A-Za-z0-9_.]{3,32}$")

private suspend fun usernameAvailableCompatible(api:ApiClient,username:String):Boolean {
    val encoded=URLEncoder.encode(username,"UTF-8")
    return try {
        api.get("/api/me/availability?username=$encoded").getBoolean("usernameAvailable")
    } catch(e:ApiException) {
        if(e.status!=404) throw e
        val users=api.searchUsers(username)
        (0 until users.length()).none { i ->
            users.getJSONObject(i).optString("username").equals(username,ignoreCase=true)
        }
    }
}

@Composable
fun InitialProfileSetupScreen(
    api:ApiClient,
    session:SessionStore,
    onComplete:()->Unit,
) {
    val scope=rememberCoroutineScope()
    val context=LocalContext.current
    var stage by remember { mutableIntStateOf(0) }
    var displayName by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var usernameState by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    var photoBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    val photoPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        photoUri=uri
        photoBitmap=uri?.let { picked -> runCatching { context.contentResolver.openInputStream(picked)?.use(BitmapFactory::decodeStream) }.getOrNull() }
        error=null
    }

    fun finishSetup() {
        session.initialProfileSetupPending=false
        onComplete()
    }

    if(stage==1){
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement=Arrangement.Center,
            horizontalAlignment=Alignment.CenterHorizontally,
        ) {
            Text(setupLabel("صورة الملف الشخصي","Profile photo"),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
            Text(
                setupLabel("أضف صورة تساعد أصدقاءك على التعرف عليك. يمكنك تخطي هذه الخطوة وإضافة الصورة لاحقاً.","Add a photo so friends can recognize you. You can skip this and add one later."),
                color=MaterialTheme.colorScheme.onSurfaceVariant,
                modifier=Modifier.padding(top=8.dp,bottom=22.dp),
            )
            Box(Modifier.size(150.dp),contentAlignment=Alignment.Center){
                if(photoBitmap!=null) Image(photoBitmap!!.asImageBitmap(),null,modifier=Modifier.fillMaxSize().clip(CircleShape))
                else Icon(Icons.Rounded.AccountCircle,null,modifier=Modifier.fillMaxSize(),tint=MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(18.dp))
            OutlinedButton(enabled=!busy,onClick={photoPicker.launch(arrayOf("image/*"))}){
                Icon(Icons.Rounded.PhotoCamera,null);Spacer(Modifier.size(8.dp));Text(if(photoUri==null)setupLabel("اختيار صورة","Choose photo") else setupLabel("تغيير الصورة","Change photo"))
            }
            error?.let { Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(top=10.dp)) }
            Spacer(Modifier.height(18.dp))
            Button(
                enabled=!busy&&photoUri!=null,
                modifier=Modifier.fillMaxWidth().height(52.dp),
                onClick={
                    val uri=photoUri?:return@Button
                    busy=true;error=null
                    scope.launch {
                        try {
                            val mime=context.contentResolver.getType(uri)?:"image/jpeg"
                            val mediaId=api.uploadMedia(uri,mime,"profile_photo").getString("id")
                            api.put("/api/me",JSONObject().put("photoMediaId",mediaId))
                            finishSetup()
                        } catch(_:Exception) {
                            error=setupLabel("تعذر حفظ الصورة. حاول مرة أخرى أو اختر تخطي.","Unable to save the photo. Try again or choose Skip.")
                        } finally { busy=false }
                    }
                },
            ) {
                if(busy)CircularProgressIndicator(modifier=Modifier.size(22.dp),strokeWidth=2.dp)
                else Text(setupLabel("حفظ الصورة والمتابعة","Save photo and continue"))
            }
            TextButton(enabled=!busy,onClick={finishSetup()},modifier=Modifier.padding(top=6.dp)){
                Text(setupLabel("تخطي الآن","Skip for now"))
            }
        }
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement=Arrangement.Center,
        horizontalAlignment=Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.AccountCircle,null,modifier=Modifier.height(72.dp),tint=MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(setupLabel("أكمل ملفك الشخصي","Complete your profile"),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
        Text(
            setupLabel("اختر اسماً ظاهراً واسم مستخدم فريداً. بعد ذلك يمكنك اختيار صورة للملف الشخصي.","Choose a display name and a unique username. Next you can choose a profile photo."),
            color=MaterialTheme.colorScheme.onSurfaceVariant,
            modifier=Modifier.padding(top=8.dp,bottom=20.dp),
        )
        OutlinedTextField(
            value=displayName,
            onValueChange={displayName=it.take(80);error=null},
            label={Text(setupLabel("الاسم الظاهر","Display name"))},
            modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!busy,
            keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.Words),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value=username,
            onValueChange={value->
                username=value.trim().removePrefix("@").take(32)
                usernameState=null
                error=null
            },
            label={Text(setupLabel("اسم المستخدم","Username"))},
            prefix={Text("@")},
            supportingText={
                Text(usernameState?:setupLabel("3–32 حرفاً: حروف إنجليزية، أرقام، _ أو .","3–32 characters: letters, numbers, _ or ."))
            },
            isError=usernameState==setupLabel("اسم المستخدم مستخدم بالفعل","Username is already used"),
            modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!busy,
            keyboardOptions=KeyboardOptions(autoCorrectEnabled=false),
        )
        error?.let { Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(top=8.dp)) }
        Spacer(Modifier.height(18.dp))
        Button(
            enabled=!busy&&displayName.trim().isNotBlank()&&username.isNotBlank(),
            modifier=Modifier.fillMaxWidth().height(52.dp),
            onClick={
                val name=displayName.trim()
                val user=username.lowercase()
                if(name.isBlank()) { error=setupLabel("أدخل الاسم الظاهر","Enter a display name");return@Button }
                if(!usernamePattern.matches(user)) { error=setupLabel("اسم المستخدم غير صالح","Invalid username");return@Button }
                busy=true;error=null;usernameState=setupLabel("جاري الحفظ…","Saving…")
                scope.launch {
                    try {
                        val result=api.put("/api/me",JSONObject().put("displayName",name).put("username",user)).getJSONObject("user")
                        val savedName=result.optString("displayName").takeUnless{it=="null"}.orEmpty()
                        val savedUsername=result.optString("username").takeUnless{it=="null"}.orEmpty().lowercase()
                        if(savedName!=name || !savedUsername.equals(user,ignoreCase=true)) {
                            throw IllegalStateException("profile_identity_not_persisted")
                        }
                        session.localDisplayName=savedName
                        session.accountUsername=savedUsername
                        usernameState=setupLabel("تم حفظ اسم المستخدم","Username saved")
                        stage=1
                    } catch(e:ApiException) {
                        val raw=e.message.orEmpty()
                        if(e.status==409||raw.contains("username_taken",true)) usernameState=setupLabel("اسم المستخدم مستخدم بالفعل","Username is already used")
                        else error=setupLabel("تعذر حفظ الملف الشخصي. حاول مرة أخرى.","Unable to save your profile. Try again.")
                    } catch(_:Exception) {
                        error=setupLabel("تعذر حفظ الملف الشخصي. تحقق من الاتصال وحاول مرة أخرى.","Unable to save your profile. Check your connection and try again.")
                    } finally { busy=false }
                }
            },
        ) {
            if(busy) CircularProgressIndicator(modifier=Modifier.size(22.dp),strokeWidth=2.dp)
            else { Icon(Icons.Rounded.CheckCircle,null);Spacer(Modifier.size(6.dp));Text(setupLabel("حفظ والمتابعة","Save and continue")) }
        }
    }
}
