package com.zoolchat.app.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zoolchat.app.data.RemoteMessage
import com.zoolchat.app.network.ApiClient
import com.zoolchat.app.sync.MessageSyncRepository
import com.zoolchat.app.ui.components.Avatar
import com.zoolchat.app.ui.i18n.t
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun ContactInfoScreen(
    api:ApiClient,
    userId:String,
    chatId:String,
    messages:List<RemoteMessage>,
    fallbackDisplayName:String?=null,
    fallbackUsername:String?=null,
    fallbackPhotoUrl:String?=null,
    onBack:()->Unit,
    onMessage:()->Unit,
    onCall:(Boolean)->Unit,
    onShared:(String)->Unit,
) {
    val scope=rememberCoroutineScope();val context=LocalContext.current
    val hasChat=chatId.isNotBlank()
    var profile by remember(userId) { mutableStateOf<JSONObject?>(null) }
    var error by remember(userId) { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<RemoteMessage>>(emptyList()) }
    var report by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    LaunchedEffect(userId,chatId,fallbackDisplayName,fallbackUsername,fallbackPhotoUrl) {
        val remote=runCatching { api.get("/api/users/$userId/profile").getJSONObject("profile") }.getOrNull()
        if(remote!=null){profile=remote;error=null;return@LaunchedEffect}

        // A live backend can temporarily reject the richer profile endpoint even though this
        // screen was opened from an authorized chat. Reconstruct only already-visible safe
        // identity fields from group/chat/search data; never invent or expose email/phone.
        val safe=JSONObject().put("id",userId)
        var safeName:String?=null
        var safeUsername:String?=null
        var safePhoto:String?=null
        val group=if(hasChat) runCatching{api.groupInfo(chatId)}.getOrNull() else null
        val members=group?.optJSONArray("members")
        val member=(0 until (members?.length()?:0)).mapNotNull{members?.optJSONObject(it)}.firstOrNull{it.optString("userId")==userId}
        safeName=member?.optString("displayName")?.takeIf{it.isNotBlank()}
        safeUsername=member?.optString("username")?.takeIf{it.isNotBlank()&&it!="null"}
        safePhoto=member?.optString("photoUrl")?.takeIf{it.isNotBlank()&&it!="null"}
        if(safeName.isNullOrBlank())safeName=fallbackDisplayName?.takeIf{it.isNotBlank()}
        if(safeUsername.isNullOrBlank())safeUsername=fallbackUsername?.takeIf{it.isNotBlank()}
        if(safePhoto.isNullOrBlank())safePhoto=fallbackPhotoUrl?.takeIf{it.isNotBlank()}
        if(safeName.isNullOrBlank()){
            safeName=runCatching{api.getE2eeKey(userId).optString("displayName").takeIf{it.isNotBlank()}}.getOrNull()
        }
        if(!safeName.isNullOrBlank()){
            safe.put("displayName",safeName)
            safe.put("username",safeUsername?:JSONObject.NULL)
            safe.put("photoUrl",safePhoto?:JSONObject.NULL)
            safe.put("about",JSONObject.NULL)
            runCatching{api.presence(userId)}.getOrNull()?.let{presence->
                if(presence.has("online"))safe.put("online",presence.opt("online"))
                if(presence.has("lastSeenAt"))safe.put("lastSeenAt",presence.opt("lastSeenAt"))
            }
            profile=safe;error=null
        }else error=t("تعذر عرض الملف الشخصي")
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TextButton(onClick=onBack){Text(t("رجوع"))}
        profile?.let { p ->
            val displayName=p.optString("displayName").ifBlank { t("مستخدم ZoolChat") }
            val photoUrl=p.optString("photoUrl").takeUnless{it.isBlank()||it=="null"}
            Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally){
                RemoteProfileAvatar(api,displayName,photoUrl,104.dp)
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
            if(hasChat) {
                            HorizontalDivider()
                            Text(t("البحث في المحادثة"),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                                OutlinedTextField(query,{query=it.take(100)},label={Text(t("بحث"))},modifier=Modifier.weight(1f),singleLine=true)
                                TextButton(enabled=query.trim().length>=2,onClick={scope.launch{
                                    val local=messages.filter{!it.deleted&&!it.decryptionFailed&&it.body.contains(query,true)}
                                    val server=runCatching{api.searchMessages(chatId,query)}.getOrNull()?.let{arr->(0 until arr.length()).map{MessageSyncRepository.parseMessage(arr.getJSONObject(it))}}.orEmpty()
                                    searchResults=(local+server).distinctBy{it.id}.sortedByDescending{it.createdAt}
                                }}){Text(t("بحث"))}
                            }
                            searchResults.take(8).forEach{Text(it.body,modifier=Modifier.padding(vertical=4.dp),maxLines=2)}
                            Text(t("الإشعارات"),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                            Row {
                                listOf(0L to t("تشغيل"),8L to t("8 ساعات"),168L to t("أسبوع"),-1L to t("دائماً")).forEach { (hours,label) ->
                                    TextButton(onClick={context.getSharedPreferences("zoolchat_user_settings",0).edit().putLong("mute_until_$chatId",if(hours<0)Long.MAX_VALUE else if(hours==0L)0 else System.currentTimeMillis()+hours*3600000).apply()}) { Text(label) }
                                }
                            }
                            Text(t("الوسائط والروابط والمستندات"),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){
                                TextButton(onClick={onShared("media")}){Text(t("الوسائط"))}
                                TextButton(onClick={onShared("links")}){Text(t("الروابط"))}
                                TextButton(onClick={onShared("documents")}){Text(t("المستندات"))}
                            }
                            messages.filter{!it.deleted&&(it.type in listOf("image","video","document"))}.takeLast(6).forEach{Text(it.mediaName?:t("مرفق"),modifier=Modifier.padding(vertical=3.dp))}
                            val groups=p.optJSONArray("groupsInCommon")
                            if(groups!=null&&groups.length()>0){
                                Text(t("مجموعات مشتركة"),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                                for(i in 0 until groups.length())Text(groups.getJSONObject(i).optString("title"),modifier=Modifier.padding(vertical=3.dp))
                            }

            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick={scope.launch{runCatching{api.post("/api/users/$userId/block")}.onSuccess{onBack()}.onFailure{error=t("تعذر إكمال الطلب")}}},modifier=Modifier.fillMaxWidth()){Text(t("حظر المستخدم"),color=MaterialTheme.colorScheme.error)}
            TextButton(onClick={report=true},modifier=Modifier.fillMaxWidth()){Text(t("الإبلاغ عن المستخدم"),color=MaterialTheme.colorScheme.error)}
        }
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    }
    if(report)AlertDialog(onDismissRequest={report=false},title={Text(t("الإبلاغ"))},text={OutlinedTextField(reason,{reason=it.take(500)},label={Text(t("السبب"))})},confirmButton={TextButton(enabled=reason.trim().length>=3,onClick={scope.launch{runCatching{api.post("/api/reports",JSONObject().put("userId",userId).put("reason",reason.trim()).apply{if(hasChat)put("chatId",chatId)})}.onSuccess{report=false;reason=""}.onFailure{error=t("تعذر إكمال الطلب")}}}){Text(t("إرسال"))}},dismissButton={TextButton(onClick={report=false}){Text(t("إلغاء"))}})
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupMemberSheet(
    api:ApiClient,
    chatId:String,
    userId:String,
    myUserId:String,
    onDismiss:()->Unit,
    onMessage:()->Unit,
    onCall:(Boolean)->Unit,
    onProfile:()->Unit,
) {
    val scope=rememberCoroutineScope()
    var profile by remember(userId){mutableStateOf<JSONObject?>(null)}
    var group by remember(chatId){mutableStateOf<JSONObject?>(null)}
    var failed by remember(userId){mutableStateOf(false)}
    var actionError by remember{mutableStateOf<String?>(null)}
    suspend fun reloadGroup(){group=runCatching{api.groupInfo(chatId)}.getOrNull()}
    LaunchedEffect(userId,chatId){
        runCatching{api.get("/api/users/$userId/profile").getJSONObject("profile")}.onSuccess{profile=it;failed=false}.onFailure{failed=true}
        reloadGroup()
    }
    val members=group?.optJSONArray("members")
    val member=(0 until (members?.length()?:0)).mapNotNull{members?.optJSONObject(it)}.firstOrNull{it.optString("userId")==userId}
    val memberRole=member?.optString("role","member")?:"member"
    val myRole=group?.optString("myRole","member")?:"member"
    val canRole=myRole=="owner"&&userId!=myUserId&&memberRole!="owner"
    val canRemove=userId!=myUserId&&memberRole!="owner"&&(myRole=="owner"||(myRole=="admin"&&memberRole=="member"))
    ModalBottomSheet(onDismissRequest=onDismiss){
        Column(Modifier.fillMaxWidth().padding(horizontal=20.dp).padding(bottom=24.dp),horizontalAlignment=Alignment.CenterHorizontally){
            val name=profile?.optString("displayName")?.ifBlank { null }?:member?.optString("displayName")?.ifBlank{null}?:t("عضو")
            val photoUrl=profile?.optString("photoUrl")?.takeUnless{it.isBlank()||it=="null"}?:member?.optString("photoUrl")?.takeUnless{it.isBlank()||it=="null"}
            val username=profile?.optString("username")?.takeUnless{it.isBlank()||it=="null"}?:member?.optString("username")?.takeUnless{it.isBlank()||it=="null"}
            RemoteProfileAvatar(api,name,photoUrl,76.dp)
            Spacer(Modifier.height(8.dp))
            Text(name,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
            username?.let { Text("@$it",color=MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(when(memberRole){"owner"->t("المالك");"admin"->t("مشرف");else->if(userId==myUserId)t("أنت") else t("عضو")},style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
            if(failed&&member==null)Text(t("تعذر عرض الملف الشخصي"),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp));HorizontalDivider()
            if(userId!=myUserId){
                TextButton(onClick=onMessage,modifier=Modifier.fillMaxWidth()){Text(t("إرسال رسالة خاصة"))}
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){TextButton(onClick={onCall(false)}){Text(t("مكالمة صوتية"))};TextButton(onClick={onCall(true)}){Text(t("مكالمة فيديو"))}}
            }
            TextButton(onClick=onProfile,modifier=Modifier.fillMaxWidth()){Text(if(userId==myUserId)t("عرض ملفي الشخصي") else t("عرض الملف الشخصي"))}
            if(canRole){
                TextButton(onClick={scope.launch{runCatching{api.setGroupMemberRole(chatId,userId,if(memberRole=="admin")"member" else "admin")}.onSuccess{reloadGroup()}.onFailure{actionError=t("تعذر إكمال الطلب")}}},modifier=Modifier.fillMaxWidth()){
                    Text(if(memberRole=="admin")t("إزالة الإشراف") else t("تعيين مشرف"))
                }
            }
            if(canRemove){TextButton(onClick={scope.launch{runCatching{api.removeGroupMember(chatId,userId)}.onSuccess{onDismiss()}.onFailure{actionError=t("تعذر إكمال الطلب")}}},modifier=Modifier.fillMaxWidth()){Text(t("إزالة من المجموعة"),color=MaterialTheme.colorScheme.error)}}
            actionError?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
        }
    }
}

@Composable
private fun RemoteProfileAvatar(api:ApiClient,name:String,url:String?,size:Dp){
    var bitmap by remember(url){mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)}
    LaunchedEffect(url){bitmap=if(url.isNullOrBlank())null else runCatching{BitmapFactory.decodeFile(api.downloadMedia(url,"profile-photo").absolutePath)?.asImageBitmap()}.getOrNull()}
    if(bitmap!=null)Image(bitmap!!,name,contentScale=ContentScale.Crop,modifier=Modifier.size(size).clip(CircleShape)) else Avatar(name,size)
}
