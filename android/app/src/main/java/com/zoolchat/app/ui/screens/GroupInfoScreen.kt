package com.zoolchat.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zoolchat.app.data.RegisteredContact
import com.zoolchat.app.data.RemoteGroupInfo
import com.zoolchat.app.data.RemoteGroupMember
import com.zoolchat.app.network.ApiClient
import com.zoolchat.app.ui.components.Avatar
import com.zoolchat.app.ui.i18n.t
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun GroupInfoScreen(
    api: ApiClient,
    chatId: String,
    contacts: List<RegisteredContact>,
    onBack: () -> Unit,
    onLeaveDone: () -> Unit,
) {
    val scope = rememberCoroutineScope(); val context = LocalContext.current
    var group by remember { mutableStateOf<RemoteGroupInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var selectedAdd by remember { mutableStateOf<Set<String>>(emptySet()) }

    suspend fun load() { runCatching { parseGroup(api.groupInfo(chatId)) }.onSuccess { group = it; error = null }.onFailure { error = it.message } }
    LaunchedEffect(chatId) { load() }

    if (showAdd && group != null) {
        AlertDialog(onDismissRequest = { showAdd = false; selectedAdd = emptySet() }, title = { Text(t("إضافة أعضاء")) }, text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                contacts.filter { c -> group!!.members.none { it.userId == c.userId } }.forEach { c ->
                    val checked = c.userId in selectedAdd
                    Row(Modifier.fillMaxWidth().clickable { selectedAdd = if (checked) selectedAdd-c.userId else selectedAdd+c.userId }.padding(vertical=7.dp),verticalAlignment=Alignment.CenterVertically){Avatar(c.displayName,36.dp);Spacer(Modifier.width(8.dp));Text(c.displayName,Modifier.weight(1f));Checkbox(checked,{selectedAdd=if(checked)selectedAdd-c.userId else selectedAdd+c.userId})}
                }
            }
        }, confirmButton = { TextButton(enabled = selectedAdd.isNotEmpty(), onClick = { scope.launch { runCatching { api.addGroupMembers(chatId, selectedAdd.toList()) }.onSuccess { showAdd=false; selectedAdd=emptySet(); load() }.onFailure { error=it.message } } }) { Text(t("إضافة")) } }, dismissButton = { TextButton(onClick={showAdd=false}){Text(t("إلغاء"))} })
    }

    Scaffold(topBar={Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically){IconButton(onClick=onBack){Icon(Icons.Rounded.ArrowBack,t("رجوع"))};Text(t("معلومات المجموعة"),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)}}){pad->
        val g=group
        if(g==null){Box(Modifier.fillMaxSize().padding(pad),contentAlignment=Alignment.Center){if(error==null)CircularProgressIndicator() else Text(error!!,color=MaterialTheme.colorScheme.error)};return@Scaffold}
        val admin=g.myRole in setOf("owner","admin")
        LazyColumn(Modifier.fillMaxSize().padding(pad),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            item { Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally){Avatar(g.title,82.dp);Spacer(Modifier.height(10.dp));Text(g.title,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);if(g.description.isNotBlank())Text(g.description,color=MaterialTheme.colorScheme.onSurfaceVariant);Text("${g.members.size} ${t("عضو")}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)} }
            item { HorizontalDivider(); ListItem(headlineContent={Text(t("رابط/رمز الدعوة"))},supportingContent={Text(g.inviteCode)},leadingContent={Icon(Icons.Rounded.Link,null)},trailingContent={IconButton(onClick={val cm=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager;cm.setPrimaryClip(ClipData.newPlainText("ZoolChat group invite",g.inviteCode))}){Icon(Icons.Rounded.ContentCopy,t("نسخ"))}}) }
            if(admin){
                item { GroupSwitch(t("المشرفون فقط يرسلون"),g.onlyAdminsSend){v->scope.launch{api.updateGroup(chatId,JSONObject().put("onlyAdminsSend",v));load()}} }
                item { GroupSwitch(t("المشرفون فقط يعدلون معلومات المجموعة"),g.onlyAdminsEdit){v->scope.launch{api.updateGroup(chatId,JSONObject().put("onlyAdminsEdit",v));load()}} }
                item { GroupSwitch(t("المشرفون فقط يضيفون أعضاء"),g.onlyAdminsAdd){v->scope.launch{api.updateGroup(chatId,JSONObject().put("onlyAdminsAdd",v));load()}} }
                item { GroupSwitch(t("الموافقة على الأعضاء الجدد"),g.approveNewMembers){v->scope.launch{api.updateGroup(chatId,JSONObject().put("approveNewMembers",v));load()}} }
            }
            item {
                var expanded by remember { mutableStateOf(false) }
                ListItem(headlineContent={Text(t("الرسائل المؤقتة"))},supportingContent={Text(when(g.disappearingSeconds){86400->t("24 ساعة");604800->t("7 أيام");7776000->t("90 يومًا");else->t("إيقاف")})},leadingContent={Icon(Icons.Rounded.Timer,null)},modifier=Modifier.clickable(enabled=admin){expanded=true})
                DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}){listOf(0 to t("إيقاف"),86400 to t("24 ساعة"),604800 to t("7 أيام"),7776000 to t("90 يومًا")).forEach{(sec,label)->DropdownMenuItem(text={Text(label)},onClick={expanded=false;scope.launch{api.updateGroup(chatId,JSONObject().put("disappearingSeconds",sec));load()}})}}
            }
            item { Row(Modifier.fillMaxWidth().padding(top=8.dp),verticalAlignment=Alignment.CenterVertically){Text("${t("الأعضاء")} (${g.members.size})",fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));if(admin)TextButton(onClick={showAdd=true}){Icon(Icons.Rounded.PersonAdd,null);Spacer(Modifier.width(4.dp));Text(t("إضافة"))}} }
            items(g.members,key={it.userId}){m->
                var menu by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().padding(vertical=7.dp),verticalAlignment=Alignment.CenterVertically){Avatar(m.displayName,42.dp);Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(m.displayName,fontWeight=FontWeight.SemiBold);Text(when(m.role){"owner"->t("المالك");"admin"->t("مشرف");else->m.username?.let{"@$it"}?:t("عضو")},style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};if(admin&&m.role!="owner"){Box{IconButton(onClick={menu=true}){Icon(Icons.Rounded.MoreVert,null)};DropdownMenu(menu,{menu=false}){if(g.myRole=="owner")DropdownMenuItem(text={Text(if(m.role=="admin")t("إزالة الإشراف") else t("تعيين مشرف"))},onClick={menu=false;scope.launch{api.setGroupMemberRole(chatId,m.userId,if(m.role=="admin")"member" else "admin");load()}});DropdownMenuItem(text={Text(t("إزالة من المجموعة"),color=MaterialTheme.colorScheme.error)},onClick={menu=false;scope.launch{api.removeGroupMember(chatId,m.userId);load()}})}}}}
            }
            item { Spacer(Modifier.height(12.dp)); OutlinedButton(onClick={scope.launch{runCatching{api.leaveGroup(chatId)}.onSuccess{onLeaveDone()}.onFailure{error=it.message}}},modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.error)){Icon(Icons.Rounded.ExitToApp,null);Spacer(Modifier.width(6.dp));Text(if(g.myRole=="owner")t("المالك يجب أن ينقل الملكية أو يحذف المجموعة") else t("مغادرة المجموعة"))} }
            if(error!=null)item{Text(error!!,color=MaterialTheme.colorScheme.error)}
        }
    }
}

@Composable private fun GroupSwitch(label:String,value:Boolean,onChange:(Boolean)->Unit){ListItem(headlineContent={Text(label)},trailingContent={Switch(value,onChange)})}

private fun parseGroup(o:JSONObject):RemoteGroupInfo{
    val arr=o.optJSONArray("members")?:org.json.JSONArray();val members=(0 until arr.length()).map{idx->val m=arr.getJSONObject(idx);RemoteGroupMember(m.getString("userId"),m.getString("displayName"),m.optString("username").takeIf{it.isNotBlank()},m.optString("photoUrl").takeIf{it.isNotBlank()},m.optString("role","member"))}
    return RemoteGroupInfo(o.getString("id"),o.getString("title"),o.optString("description"),o.optString("inviteCode"),o.optString("myRole","member"),o.optBoolean("onlyAdminsSend"),o.optBoolean("onlyAdminsEdit"),o.optBoolean("onlyAdminsAdd"),o.optBoolean("approveNewMembers"),o.optInt("disappearingSeconds"),members)
}
