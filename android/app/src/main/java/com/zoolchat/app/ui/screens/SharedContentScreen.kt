package com.zoolchat.app.ui.screens

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zoolchat.app.crypto.E2eeManager
import com.zoolchat.app.data.RemoteMessage
import com.zoolchat.app.media.MediaRepository
import com.zoolchat.app.network.ApiClient
import com.zoolchat.app.sync.MessageSyncRepository
import com.zoolchat.app.ui.i18n.t
import kotlinx.coroutines.launch

@Composable
fun SharedContentScreen(
    api:ApiClient,
    e2ee:E2eeManager,
    peerUserId:String?,
    chatId:String,
    initialKind:String="media",
    localMessages:List<RemoteMessage> = emptyList(),
    onBack:()->Unit,
){
    val context=androidx.compose.ui.platform.LocalContext.current
    val scope=rememberCoroutineScope()
    val repo=remember(api,e2ee,peerUserId){MediaRepository(context.applicationContext,api,e2ee,peerUserId)}
    var kind by remember{mutableStateOf(if(initialKind in setOf("media","links","documents"))initialKind else "media")}
    var remote by remember{mutableStateOf<List<RemoteMessage>>(emptyList())}
    var loading by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf<String?>(null)}
    LaunchedEffect(chatId,kind){
        loading=true;error=null
        runCatching{api.sharedContent(chatId,kind)}.onSuccess{arr->remote=(0 until arr.length()).map{MessageSyncRepository.parseMessage(arr.getJSONObject(it))}}.onFailure{error=t("تعذر تحميل المحتوى المشترك")}
        loading=false
    }
    val visible=remember(remote,localMessages,kind){
        if(kind!="links")remote else (remote+localMessages.filter{!it.deleted&&!it.decryptionFailed&&Regex("https?://",RegexOption.IGNORE_CASE).containsMatchIn(it.body)}).distinctBy{it.id}.sortedByDescending{it.createdAt}
    }
    Scaffold(topBar={Row(Modifier.fillMaxWidth().padding(6.dp),verticalAlignment=Alignment.CenterVertically){IconButton(onClick=onBack){Icon(Icons.Rounded.ArrowBack,t("رجوع"))};Text(t("الوسائط والروابط والمستندات"),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)}}){pad->
        Column(Modifier.fillMaxSize().padding(pad)){
            TabRow(selectedTabIndex=listOf("media","links","documents").indexOf(kind)){listOf("media" to t("الوسائط"),"links" to t("الروابط"),"documents" to t("المستندات")).forEach{(id,label)->Tab(selected=kind==id,onClick={kind=id},text={Text(label)})}}
            if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let{Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(16.dp))}
            LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                items(visible,key={it.id}){m->
                    Card(Modifier.fillMaxWidth().clickable(enabled=kind!="links"&&m.mediaUrl!=null){scope.launch{runCatching{repo.open(m)}.onFailure{error=t("تعذر فتح المرفق")}}}){
                        Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){
                            Icon(when{kind=="links"->Icons.Rounded.Link;m.type=="image"->Icons.Rounded.Image;m.type=="video"->Icons.Rounded.Movie;else->Icons.Rounded.Description},null,modifier=Modifier.size(30.dp));Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)){Text(if(kind=="links")m.body else (m.mediaName?:m.body.ifBlank{t("مرفق")}),maxLines=3);Text(m.createdAt.substringBefore('T'),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                        }
                    }
                }
                if(!loading&&visible.isEmpty())item{Box(Modifier.fillParentMaxSize(),contentAlignment=Alignment.Center){Text(t("لا توجد عناصر"),color=MaterialTheme.colorScheme.onSurfaceVariant)}}
            }
        }
    }
}
