package com.zoolchat.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zoolchat.app.network.ApiClient
import com.zoolchat.app.ui.i18n.t
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun MeetInvitations(api:ApiClient,meetingId:String?,code:String,onDismiss:()->Unit,onAccepted:(String)->Unit) {
 val context=LocalContext.current;val scope=rememberCoroutineScope()
 var invitations by remember{mutableStateOf<List<JSONObject>>(emptyList())}
 var kind by remember{mutableStateOf("username")};var value by remember{mutableStateOf("")}
 var result by remember{mutableStateOf("")};var busy by remember{mutableStateOf(false)}
 LaunchedEffect(Unit){runCatching{api.get("/api/meeting-invitations").getJSONArray("invitations")}.onSuccess{a->invitations=(0 until a.length()).map{a.getJSONObject(it)}}}
 AlertDialog(onDismissRequest=onDismiss,title={Text(t("دعوات ZoolMeet"))},text={Column(Modifier.heightIn(max=500.dp).verticalScroll(rememberScrollState())){
  if(meetingId!=null){
   TextButton(onClick={(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ZoolMeet",code))}){Text(t("نسخ رمز الاجتماع"))}
   listOf("username" to t("اسم المستخدم"),"phone" to t("رقم الجوال"),"email" to t("البريد الإلكتروني")).forEach{(id,label)->Row{RadioButton(kind==id,{kind=id});Text(label)}}
   OutlinedTextField(value,{value=it},label={Text(t("المستلم"))},singleLine=true)
   if(kind=="phone")Text(t("أدخل الرقم مع مفتاح الدولة"))
   TextButton(enabled=!busy&&value.length>=3,onClick={busy=true;scope.launch{runCatching{api.post("/api/meetings/$meetingId/invitations",JSONObject().put("kind",kind).put("value",value))}.onSuccess{result=t("إذا كان المستلم متاحاً فستصله الدعوة")}.onFailure{result=t("تعذر إرسال الدعوة")};busy=false}}){Text(t("دعوة"))}
  }
  invitations.forEach{i->
   Text(i.optString("title"));Text(i.optString("status"))
   if(i.optString("status") in listOf("pending","waiting","admitted"))TextButton(enabled=!busy,onClick={busy=true;scope.launch{runCatching{api.post("/api/meeting-invitations/${i.getString("id")}/accept")}.onSuccess{onAccepted(it.getString("code"))}.onFailure{result=t("الدعوة غير متاحة")};busy=false}}){Text(t("قبول والانضمام"))}
   HorizontalDivider()
  }
  Text(result)
 }},confirmButton={TextButton(onClick=onDismiss){Text(t("إغلاق"))}})
}
