package com.zoolchat.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zoolchat.app.data.RemoteMessage
import com.zoolchat.app.network.ApiClient
import com.zoolchat.app.ui.components.Avatar
import com.zoolchat.app.ui.i18n.t
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun ContactInfoScreen(api:ApiClient,userId:String,chatId:String,messages:List<RemoteMessage>,onBack:()->Unit,onMessage:()->Unit,onCall:(Boolean)->Unit) {
    val scope=rememberCoroutineScope();val context=LocalContext.current
    var profile by remember(userId) { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var section by remember { mutableStateOf("media") }
    var query by remember { mutableStateOf("") }
    var report by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    LaunchedEffect(userId) { runCatching { api.get("/api/users/$userId/profile").getJSONObject("profile") }.onSuccess { profile=it }.onFailure { error=t("تعذر عرض الملف الشخصي") } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TextButton(onClick=onBack){Text(t("رجوع"))}
        profile?.let { p ->
            val displayName=p.optString("displayName").ifBlank { t("مستخدم ZoolChat") }
            Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally){
                Avatar(displayName,96.dp)
                Spacer(Modifier.height(8.dp))
                Text(displayName,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                p.optString("username").takeUnless { it.isBlank()||it=="null" }?.let { Text("@$it",color=MaterialTheme.colorScheme.onSurfaceVariant) }
                p.optString("about").takeUnless { it.isBlank()||it=="null" }?.let { Text(it,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                if(!p.isNull("online")&&p.optBoolean("online"))Text(t("متصل الآن"),color=MaterialTheme.colorScheme.primary)
                else if(!p.isNull("lastSeenAt"))Text(t("آخر ظهور")+" "+p.optString("lastSeenAt"),color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                TextButton(onClick=onMessage){Text(t("رسالة"))}
                TextButton(onClick={onCall(false)}){Text(t("مكالمة صوتية"))}
                TextButton(onClick={onCall(true)}){Text(t("مكالمة فيديو"))}
            }
            HorizontalDivider()
            Text(t("الإشعارات"),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
            Row {
                listOf(0L to t("تشغيل"),8L to t("8 ساعات"),168L to t("أسبوع"),-1L to t("دائماً")).forEach { (hours,label) ->
                    TextButton(onClick={context.getSharedPreferences("zoolchat_user_settings",0).edit().putLong("mute_until_$chatId",if(hours<0)Long.MAX_VALUE else if(hours==0L)0 else System.currentTimeMillis()+hours*3600000).apply()}) { Text(label) }
                }
            }
            Text(t("الوسائط والروابط والمستندات"),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
            Row { listOf("media" to t("الوسائط"),"links" to t("الروابط"),"documents" to t("المستندات")).forEach { (id,label)->TextButton(onClick={section=id}){Text(label)} } }
            OutlinedTextField(query,{query=it},label={Text(t("بحث في الرسائل المحملة"))},modifier=Modifier.fillMaxWidth(),singleLine=true)
            val selected=messages.filter { !it.deleted&&!it.decryptionFailed && (query.isNotBlank()&&it.body.contains(query,true)||query.isBlank()&&when(section){"media"->it.type in listOf("image","video");"documents"->it.type=="document";else->it.body.contains("https://")}) }
            selected.takeLast(30).forEach { Text(it.mediaName?:it.body,modifier=Modifier.padding(vertical=5.dp)) }
            if(selected.isEmpty())Text(t("لا توجد عناصر محملة"),color=MaterialTheme.colorScheme.onSurfaceVariant)
            val groups=p.optJSONArray("groupsInCommon")
            if(groups!=null&&groups.length()>0){
                Text(t("مجموعات مشتركة"),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                for(i in 0 until groups.length())Text(groups.getJSONObject(i).optString("title"),modifier=Modifier.padding(vertical=3.dp))
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick={scope.launch{runCatching{api.post("/api/users/$userId/block")}.onSuccess{onBack()}.onFailure{error=t("تعذر إكمال الطلب")}}},modifier=Modifier.fillMaxWidth()){Text(t("حظر المستخدم"),color=MaterialTheme.colorScheme.error)}
            TextButton(onClick={report=true},modifier=Modifier.fillMaxWidth()){Text(t("الإبلاغ عن المستخدم"),color=MaterialTheme.colorScheme.error)}
        }
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    }
    if(report)AlertDialog(onDismissRequest={report=false},title={Text(t("الإبلاغ"))},text={OutlinedTextField(reason,{reason=it.take(500)},label={Text(t("السبب"))})},confirmButton={TextButton(enabled=reason.trim().length>=3,onClick={scope.launch{runCatching{api.post("/api/reports",JSONObject().put("userId",userId).put("chatId",chatId).put("reason",reason.trim()))}.onSuccess{report=false;reason=""}.onFailure{error=t("تعذر إكمال الطلب")}}}){Text(t("إرسال"))}},dismissButton={TextButton(onClick={report=false}){Text(t("إلغاء"))}})
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupMemberSheet(api:ApiClient,userId:String,myUserId:String,onDismiss:()->Unit,onMessage:()->Unit,onCall:(Boolean)->Unit,onProfile:()->Unit) {
    var profile by remember(userId){mutableStateOf<JSONObject?>(null)}
    var failed by remember(userId){mutableStateOf(false)}
    LaunchedEffect(userId){
        runCatching{api.get("/api/users/$userId/profile").getJSONObject("profile")}
            .onSuccess{profile=it;failed=false}
            .onFailure{failed=true}
    }
    ModalBottomSheet(onDismissRequest=onDismiss){
        Column(Modifier.fillMaxWidth().padding(horizontal=20.dp).padding(bottom=24.dp),horizontalAlignment=Alignment.CenterHorizontally){
            val name=profile?.optString("displayName")?.ifBlank { null }?:t("عضو")
            Avatar(name,72.dp)
            Spacer(Modifier.height(8.dp))
            Text(name,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
            profile?.optString("username")?.takeUnless { it.isBlank()||it=="null" }?.let { Text("@$it",color=MaterialTheme.colorScheme.onSurfaceVariant) }
            if(userId==myUserId)Text(t("أنت"),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
            if(failed)Text(t("تعذر عرض الملف الشخصي"),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            if(userId!=myUserId){
                ListItem(headlineContent={Text(t("إرسال رسالة خاصة"))},modifier=Modifier.fillMaxWidth(),leadingContent={Text("💬")})
                TextButton(onClick=onMessage,modifier=Modifier.fillMaxWidth()){Text(t("إرسال رسالة خاصة"))}
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){
                    TextButton(onClick={onCall(false)}){Text(t("مكالمة صوتية"))}
                    TextButton(onClick={onCall(true)}){Text(t("مكالمة فيديو"))}
                }
            }
            TextButton(onClick=onProfile,modifier=Modifier.fillMaxWidth()){Text(if(userId==myUserId)t("عرض ملفي الشخصي") else t("عرض الملف الشخصي"))}
        }
    }
}
