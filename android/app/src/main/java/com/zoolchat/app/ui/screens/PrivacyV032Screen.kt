package com.zoolchat.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zoolchat.app.network.ApiClient
import com.zoolchat.app.ui.i18n.t
import kotlinx.coroutines.launch

private data class PrivacyChoice(val id:String,val label:String)

@Composable
fun PrivacyV032Screen(api:ApiClient,onBack:()->Unit){
    val scope=rememberCoroutineScope()
    var readReceipts by remember{mutableStateOf(true)}
    var showLastSeen by remember{mutableStateOf(true)}
    var showOnline by remember{mutableStateOf(true)}
    var photo by remember{mutableStateOf("contacts")}
    var about by remember{mutableStateOf("contacts")}
    var loading by remember{mutableStateOf(true)}
    var error by remember{mutableStateOf<String?>(null)}
    var selector by remember{mutableStateOf<String?>(null)}
    val choices=listOf(PrivacyChoice("everyone",t("الجميع")),PrivacyChoice("contacts",t("جهات اتصالي")),PrivacyChoice("nobody",t("لا أحد")))
    fun label(value:String)=choices.firstOrNull{it.id==value}?.label?:t("جهات اتصالي")
    fun save(block:suspend()->Unit){scope.launch{runCatching{block()}.onFailure{error=t("تعذر تحديث إعدادات الخصوصية")}}}
    LaunchedEffect(Unit){
        runCatching{api.getPrivacy()}.onSuccess{p->
            readReceipts=p.optBoolean("readReceipts",true);showLastSeen=p.optBoolean("showLastSeen",true);showOnline=p.optBoolean("showOnline",true)
            photo=p.optString("profilePhotoVisibility","contacts");about=p.optString("aboutVisibility","contacts")
        }.onFailure{error=t("تعذر تحديث إعدادات الخصوصية")};loading=false
    }
    Scaffold(topBar={Row(Modifier.fillMaxWidth().padding(6.dp),verticalAlignment=Alignment.CenterVertically){IconButton(onClick=onBack){Icon(Icons.Rounded.ArrowBack,t("رجوع"))};Text(t("الخصوصية"),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)}}){pad->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal=16.dp)){
            if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(t("من يمكنه رؤية معلوماتي الشخصية"),style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(vertical=12.dp))
            PrivacyRow(Icons.Rounded.Schedule,t("آخر ظهور"),if(showLastSeen)t("الجميع") else t("لا أحد")){showLastSeen=!showLastSeen;if(!showLastSeen)showOnline=false;save{api.updatePrivacy(showLastSeen=showLastSeen,showOnline=showOnline)}}
            PrivacyRow(Icons.Rounded.Circle,t("متصل الآن"),if(showOnline)t("الجميع") else t("لا أحد")){showOnline=!showOnline;save{api.updatePrivacy(showOnline=showOnline)}}
            PrivacyRow(Icons.Rounded.AccountCircle,t("صورة الملف الشخصي"),label(photo)){selector="photo"}
            PrivacyRow(Icons.Rounded.Info,t("النبذة"),label(about)){selector="about"}
            HorizontalDivider(Modifier.padding(vertical=10.dp))
            Row(Modifier.fillMaxWidth().padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Rounded.DoneAll,null);Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(t("مؤشرات قراءة الرسائل"),fontWeight=FontWeight.Medium);Text(t("عند إيقافها لن ترسل أو تستقبل علامتي القراءة في الدردشات الفردية."),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Switch(readReceipts,{v->readReceipts=v;save{api.updatePrivacy(readReceipts=v)}})}
            error?.let{Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(top=8.dp))}
        }
    }
    if(selector!=null){
        val current=if(selector=="photo")photo else about
        AlertDialog(onDismissRequest={selector=null},title={Text(if(selector=="photo")t("صورة الملف الشخصي") else t("النبذة"))},text={Column{choices.forEach{c->Row(Modifier.fillMaxWidth().clickable{
            if(selector=="photo"){photo=c.id;save{api.updatePrivacy(profilePhotoVisibility=c.id)}}else{about=c.id;save{api.updatePrivacy(aboutVisibility=c.id)}};selector=null
        }.padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){Text(c.label,Modifier.weight(1f));RadioButton(current==c.id,null)}}}},confirmButton={TextButton(onClick={selector=null}){Text(t("إغلاق"))}})
    }
}

@Composable
private fun PrivacyRow(icon:androidx.compose.ui.graphics.vector.ImageVector,title:String,value:String,onClick:()->Unit){
    Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically){Icon(icon,null);Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(title,fontWeight=FontWeight.Medium);Text(value,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Icon(Icons.Rounded.ChevronLeft,null)}
}
