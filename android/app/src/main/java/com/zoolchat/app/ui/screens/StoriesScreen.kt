package com.zoolchat.app.ui.screens

import android.Manifest
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zoolchat.app.data.RegisteredContact
import com.zoolchat.app.media.VoiceNoteRecorder
import com.zoolchat.app.network.ApiClient
import com.zoolchat.app.ui.components.Avatar
import com.zoolchat.app.ui.i18n.AppLanguage
import com.zoolchat.app.ui.i18n.I18n
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private data class StatusPerson(val userId:String,val displayName:String,val username:String?)
private data class StatusGroup(val ownerId:String,val displayName:String,val posts:List<JSONObject>)
private fun s(ar:String,en:String)=if(I18n.language==AppLanguage.AR)ar else en

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoriesScreen(
    api:ApiClient,
    myUserId:String,
    contacts:List<RegisteredContact>,
    onOpenSettings:()->Unit,
){
    var posts by remember{mutableStateOf<List<JSONObject>>(emptyList())}
    var candidates by remember{mutableStateOf(contacts.map{StatusPerson(it.userId,it.displayName,it.username)})}
    var refresh by remember{mutableIntStateOf(0)}
    var busy by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf<String?>(null)}
    var composing by remember{mutableStateOf(false)}
    var addYoursSeed by remember{mutableStateOf("")}
    var viewing by remember{mutableStateOf<List<JSONObject>>(emptyList())}
    var viewIndex by remember{mutableIntStateOf(0)}

    LaunchedEffect(refresh,contacts){
        busy=true;error=null
        runCatching{
            val merged=linkedMapOf<String,StatusPerson>()
            contacts.forEach{merged[it.userId]=StatusPerson(it.userId,it.displayName,it.username)}
            val ca=api.get("/api/status/audience-candidates").optJSONArray("users")?:JSONArray()
            for(i in 0 until ca.length()){
                val o=ca.getJSONObject(i);val id=o.optString("userId");if(id.isNotBlank())merged.putIfAbsent(id,StatusPerson(id,o.optString("displayName","ZoolChat"),o.optString("username").takeIf{it.isNotBlank()&&it!="null"}))
            }
            candidates=merged.values.toList()
            val arr=api.get("/api/status").optJSONArray("statuses")?:JSONArray()
            posts=(0 until arr.length()).map{arr.getJSONObject(it)}
        }.onFailure{error=s("تعذر تحميل الحالات. يلزم نشر Backend v0.32 أولاً.","Unable to load statuses. Backend v0.32 must be deployed first.")}
        busy=false
    }

    val groups=remember(posts){posts.groupBy{it.optString("ownerId")}.map{(id,list)->StatusGroup(id,list.lastOrNull()?.optString("ownerDisplayName","ZoolChat")?:"ZoolChat",list.sortedBy{it.optString("createdAt")})}}
    val mine=groups.firstOrNull{it.ownerId==myUserId}
    val others=groups.filter{it.ownerId!=myUserId}.sortedByDescending{it.posts.lastOrNull()?.optString("createdAt")}

    Column(Modifier.fillMaxSize()){
        Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){
            Text(s("التحديثات","Updates"),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
            IconButton(onClick={refresh++}){Icon(Icons.Rounded.Refresh,s("تحديث","Refresh"))}
            IconButton(onClick=onOpenSettings){Icon(Icons.Rounded.Settings,s("الإعدادات","Settings"))}
        }
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        ListItem(
            headlineContent={Text(s("حالتي","My status"),fontWeight=FontWeight.SemiBold)},
            supportingContent={Text(if(mine==null)s("أضف حالة جديدة","Add a new status") else s("عرض حالتي أو إضافة تحديث جديد","View my status or add another update"))},
            leadingContent={Box{Avatar(s("أنا","Me"),56.dp,modifier=Modifier.border(3.dp,if(mine==null)MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.primary,CircleShape).padding(3.dp));Icon(Icons.Rounded.AddCircle,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(22.dp).align(Alignment.BottomEnd).background(MaterialTheme.colorScheme.surface,CircleShape))}},
            trailingContent={IconButton(onClick={addYoursSeed="";composing=true}){Icon(Icons.Rounded.Add,s("إضافة","Add"))}},
            modifier=Modifier.clickable{if(mine==null){addYoursSeed="";composing=true}else{viewing=mine.posts;viewIndex=0}},
        )
        HorizontalDivider()
        Text(s("الحالات الحديثة","Recent updates"),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,modifier=Modifier.padding(16.dp))
        LazyColumn(Modifier.weight(1f)){
            items(others,key={it.ownerId}){g->
                val unseen=g.posts.any{!it.optBoolean("viewed")}
                ListItem(
                    headlineContent={Text(g.displayName,fontWeight=FontWeight.SemiBold)},
                    supportingContent={Text("${g.posts.size} ${s("تحديث","updates")}")},
                    leadingContent={Avatar(g.displayName,54.dp,modifier=Modifier.border(3.dp,if(unseen)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,CircleShape).padding(3.dp))},
                    modifier=Modifier.clickable{viewing=g.posts;viewIndex=g.posts.indexOfFirst{!it.optBoolean("viewed")}.let{if(it<0)0 else it}},
                )
            }
            if(others.isEmpty()&&!busy)item{Text(s("لا توجد حالات متاحة الآن","No status updates available"),modifier=Modifier.padding(horizontal=16.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)}
            item{HorizontalDivider(Modifier.padding(top=12.dp));Text(s("القنوات","Channels"),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,modifier=Modifier.padding(16.dp));Text(s("القنوات منفصلة عن الحالات داخل ZoolChat.","Channels are separate from Status in ZoolChat."),modifier=Modifier.padding(start=16.dp,end=16.dp,bottom=24.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
        error?.let{Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(12.dp))}
    }

    if(composing)StatusComposer(api,candidates,contacts,addYoursSeed,{composing=false;addYoursSeed=""}){composing=false;addYoursSeed="";refresh++}
    if(viewing.isNotEmpty())viewing.getOrNull(viewIndex)?.let{post->
        StatusViewer(
            api=api,status=post,myUserId=myUserId,index=viewIndex,count=viewing.size,
            onViewed={id->posts=posts.map{p->if(p.optString("id")==id)JSONObject(p.toString()).put("viewed",true) else p}},
            onPrevious={if(viewIndex>0)viewIndex--},
            onNext={if(viewIndex<viewing.lastIndex)viewIndex++ else viewing=emptyList()},
            onClose={viewing=emptyList()},
            onDeleted={viewing=emptyList();refresh++},
            onAddYours={prompt->viewing=emptyList();addYoursSeed=prompt;composing=true},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusComposer(
    api:ApiClient,
    people:List<StatusPerson>,
    contacts:List<RegisteredContact>,
    initialAddYours:String,
    onDismiss:()->Unit,
    onPublished:()->Unit,
){
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val recorder=remember{VoiceNoteRecorder(context.applicationContext)}
    var kind by remember{mutableStateOf("text")}
    var text by remember{mutableStateOf("")}
    var link by remember{mutableStateOf("")}
    var images by remember{mutableStateOf<List<Uri>>(emptyList())}
    var video by remember{mutableStateOf<Uri?>(null)}
    var music by remember{mutableStateOf<Uri?>(null)}
    var voice by remember{mutableStateOf<File?>(null)}
    var stickers by remember{mutableStateOf<List<Uri>>(emptyList())}
    var recording by remember{mutableStateOf(false)}
    var audienceMode by remember{mutableStateOf("contacts")}
    var audienceSelection by remember{mutableStateOf<Set<String>>(emptySet())}
    var mentions by remember{mutableStateOf<Set<String>>(emptySet())}
    var audienceDialog by remember{mutableStateOf(false)}
    var mentionsDialog by remember{mutableStateOf(false)}
    var addYours by remember(initialAddYours){mutableStateOf(initialAddYours)}
    var publishing by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf<String?>(null)}

    val imagePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){u->images=u.take(6);if(images.isNotEmpty())kind=if(images.size>1)"collage" else "image"}
    val videoPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){u->if(u!=null){video=u;kind="video"}}
    val musicPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){u->if(u!=null){music=u;kind="music"}}
    val stickerPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){u->stickers=u.take(10)}
    val mic=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->
        if(granted)recorder.start().onSuccess{recording=true;voice=null;kind="voice"}.onFailure{error=s("تعذر بدء التسجيل","Unable to start recording")}
        else error=s("يلزم إذن الميكروفون","Microphone permission is required")
    }
    LaunchedEffect(recording){if(recording){delay(30_000);if(recorder.isRecording){recorder.stop().onSuccess{voice=it}.onFailure{error=s("تعذر حفظ التسجيل","Unable to save recording")};recording=false}}}
    DisposableEffect(Unit){onDispose{if(recorder.isRecording)recorder.cancel()}}

    fun toggled(set:Set<String>,id:String)=if(id in set)set-id else set+id
    suspend fun upload(uris:List<Uri>,prefix:String)=uris.mapIndexed{i,u->api.uploadMedia(u,context.contentResolver.getType(u)?:"application/octet-stream","${prefix}_$i").getString("id")}

    ModalBottomSheet(onDismissRequest=onDismiss){
        Column(Modifier.fillMaxWidth().padding(horizontal=16.dp).padding(bottom=24.dp)){
            Text(s("إضافة حالة","Add status"),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
            LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.padding(vertical=10.dp)){
                items(listOf("text" to s("نص","Text"),"image" to s("صورة","Photo"),"video" to s("فيديو","Video"),"voice" to s("صوت","Voice"),"link" to s("رابط","Link"),"collage" to s("تخطيط","Layout"),"music" to s("موسيقى","Music")),key={it.first}){m->FilterChip(selected=kind==m.first,onClick={kind=m.first},label={Text(m.second)})}
            }
            when(kind){
                "text"->OutlinedTextField(text,{text=it.take(1000)},label={Text(s("اكتب حالة","Type a status"))},modifier=Modifier.fillMaxWidth(),minLines=4,maxLines=8)
                "image"->OutlinedButton(onClick={imagePicker.launch(arrayOf("image/*"))}){Icon(Icons.Rounded.PhotoCamera,null);Spacer(Modifier.width(6.dp));Text(s("اختيار صورة","Choose photo"))}
                "collage"->Column{OutlinedButton(onClick={imagePicker.launch(arrayOf("image/*"))}){Icon(Icons.Rounded.Collections,null);Spacer(Modifier.width(6.dp));Text(s("اختر حتى 6 صور","Choose up to 6 photos"))};Text("${images.size}/6")}
                "video"->OutlinedButton(onClick={videoPicker.launch(arrayOf("video/*"))}){Icon(Icons.Rounded.Movie,null);Spacer(Modifier.width(6.dp));Text(s("اختيار فيديو","Choose video"))}
                "voice"->Column{
                    Button(onClick={
                        if(recording){recorder.stop().onSuccess{voice=it}.onFailure{error=s("تعذر حفظ التسجيل","Unable to save recording")};recording=false}
                        else mic.launch(Manifest.permission.RECORD_AUDIO)
                    }){Icon(if(recording)Icons.Rounded.Stop else Icons.Rounded.Mic,null);Spacer(Modifier.width(6.dp));Text(if(recording)s("إيقاف وحفظ","Stop and save") else s("تسجيل صوت حتى 30 ثانية","Record up to 30 seconds"))}
                    if(voice!=null)Text(s("التسجيل جاهز","Recording ready"),color=MaterialTheme.colorScheme.primary)
                }
                "link"->OutlinedTextField(link,{link=it.take(2048)},label={Text(s("الرابط","Link"))},placeholder={Text("https://")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                "music"->Column{OutlinedButton(onClick={musicPicker.launch(arrayOf("audio/*"))}){Icon(Icons.Rounded.MusicNote,null);Spacer(Modifier.width(6.dp));Text(s("اختيار صوت من الجهاز","Choose audio from device"))};Text(s("يدعم ZoolChat حالياً ملفاً صوتياً يختاره المستخدم. كتالوج موسيقى مرخّص يحتاج مزود حقوق خارجي.","ZoolChat currently supports user-selected audio. A licensed music catalog requires an external rights provider."),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            }
            if(kind!="text"&&kind!="link")OutlinedTextField(text,{text=it.take(1000)},label={Text(s("تعليق","Caption"))},modifier=Modifier.fillMaxWidth().padding(top=8.dp),maxLines=3)
            if(kind in listOf("image","video","collage","music"))OutlinedButton(onClick={stickerPicker.launch(arrayOf("image/*"))},modifier=Modifier.padding(top=6.dp)){Icon(Icons.Rounded.StickyNote2,null);Spacer(Modifier.width(5.dp));Text("${s("ملصقات صور","Photo stickers")} (${stickers.size})")}
            OutlinedTextField(addYours,{addYours=it.take(120)},label={Text(s("أضف ما لديك (اختياري)","Add Yours (optional)"))},modifier=Modifier.fillMaxWidth().padding(top=8.dp),singleLine=true)
            Row(Modifier.fillMaxWidth().padding(top=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedButton(onClick={audienceDialog=true},modifier=Modifier.weight(1f)){Icon(Icons.Rounded.Visibility,null);Spacer(Modifier.width(4.dp));Text(s("الخصوصية","Privacy"),maxLines=1)}
                OutlinedButton(onClick={mentionsDialog=true},modifier=Modifier.weight(1f)){Icon(Icons.Rounded.AlternateEmail,null);Spacer(Modifier.width(4.dp));Text("${s("ذكر خاص","Mentions")} (${mentions.size})",maxLines=1)}
            }
            Text(when(audienceMode){"except"->s("جهات اتصالي باستثناء المحددين","My contacts except selected");"only"->s("المشاركة فقط مع المحددين","Only share with selected");else->s("جهات اتصالي","My contacts")},style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=6.dp))
            error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            Button(enabled=!publishing&&!recording,onClick={
                publishing=true;error=null
                scope.launch{
                    try{
                        val primary=when(kind){"image","collage"->images;"video"->listOfNotNull(video);"voice"->listOfNotNull(voice?.let{Uri.fromFile(it)});else->emptyList()}
                        if(kind=="text"&&text.trim().isBlank())throw IllegalArgumentException(s("اكتب نص الحالة","Enter status text"))
                        if(kind=="link"&&!link.trim().startsWith("http"))throw IllegalArgumentException(s("أدخل رابطاً صالحاً يبدأ بـ http","Enter a valid link beginning with http"))
                        if(kind in listOf("image","collage","video","voice")&&primary.isEmpty())throw IllegalArgumentException(s("اختر أو سجّل الوسائط أولاً","Choose or record media first"))
                        if(kind=="music"&&music==null)throw IllegalArgumentException(s("اختر ملفاً صوتياً أولاً","Choose an audio file first"))
                        val mediaIds=upload(primary,"status")
                        val stickerIds=upload(stickers,"status_sticker")
                        val musicId=music?.let{upload(listOf(it),"status_music").firstOrNull()}
                        val audienceIds=if(audienceMode=="contacts")contacts.map{it.userId}.distinct() else audienceSelection.toList()
                        val body=JSONObject().put("kind",kind).put("text",text.trim()).put("mediaIds",JSONArray(mediaIds)).put("stickerMediaIds",JSONArray(stickerIds)).put("audienceMode",audienceMode).put("audienceUserIds",JSONArray(audienceIds)).put("mentionedUserIds",JSONArray(mentions.toList())).put("addYoursPrompt",addYours.trim().takeIf{it.isNotBlank()}?:JSONObject.NULL)
                        if(kind=="link")body.put("linkUrl",link.trim())
                        if(kind=="collage")body.put("layout",JSONObject().put("template",if(images.size<=4)"grid" else "six-grid").put("count",images.size))
                        if(musicId!=null)body.put("musicMediaId",musicId)
                        api.post("/api/status",body)
                        voice?.delete();onPublished()
                    }catch(e:Exception){error=e.message?:s("تعذر نشر الحالة","Unable to publish status")}
                    finally{publishing=false}
                }
            },modifier=Modifier.fillMaxWidth().height(52.dp).padding(top=8.dp)){if(publishing)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)else Text(s("نشر الحالة","Post status"))}
        }
    }

    if(audienceDialog)AlertDialog(
        onDismissRequest={audienceDialog=false},title={Text(s("خصوصية الحالة","Status privacy"))},
        text={Column{
            listOf("contacts" to s("جهات اتصالي","My contacts"),"except" to s("جهات اتصالي باستثناء…","My contacts except…"),"only" to s("المشاركة فقط مع…","Only share with…")).forEach{(mode,label)->Row(Modifier.fillMaxWidth().clickable{audienceMode=mode}.padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically){RadioButton(audienceMode==mode,{audienceMode=mode});Text(label)}}
            if(audienceMode!="contacts")LazyColumn(Modifier.heightIn(max=260.dp)){items(people,key={it.userId}){p->Row(Modifier.fillMaxWidth().clickable{audienceSelection=toggled(audienceSelection,p.userId)}.padding(vertical=3.dp),verticalAlignment=Alignment.CenterVertically){Checkbox(p.userId in audienceSelection,{audienceSelection=toggled(audienceSelection,p.userId)});Text(p.displayName)}}}
        }},confirmButton={TextButton(onClick={audienceDialog=false}){Text(s("تم","Done"))}},
    )
    if(mentionsDialog)AlertDialog(
        onDismissRequest={mentionsDialog=false},title={Text(s("ذكر خاص","Private mentions"))},
        text={Column{Text(s("الذكر خاص، ويمكن لمن تذكره إعادة مشاركة الحالة.","Mentions are private and mentioned people can reshare the status."));LazyColumn(Modifier.heightIn(max=300.dp)){items(people,key={it.userId}){p->Row(Modifier.fillMaxWidth().clickable{if(mentions.size<50||p.userId in mentions)mentions=toggled(mentions,p.userId)}.padding(vertical=3.dp),verticalAlignment=Alignment.CenterVertically){Checkbox(p.userId in mentions,{if(mentions.size<50||p.userId in mentions)mentions=toggled(mentions,p.userId)});Column{Text(p.displayName);p.username?.let{Text("@$it",style=MaterialTheme.typography.bodySmall)}}}}}}},
        confirmButton={TextButton(onClick={mentionsDialog=false}){Text(s("تم","Done"))}},
    )
}

@Composable
private fun StatusViewer(
    api:ApiClient,
    status:JSONObject,
    myUserId:String,
    index:Int,
    count:Int,
    onViewed:(String)->Unit,
    onPrevious:()->Unit,
    onNext:()->Unit,
    onClose:()->Unit,
    onDeleted:()->Unit,
    onAddYours:(String)->Unit,
){
    val scope=rememberCoroutineScope()
    val id=status.optString("id");val own=status.optString("ownerId")==myUserId
    var liked by remember(id){mutableStateOf(status.optBoolean("liked"))}
    var reply by remember(id){mutableStateOf("")}
    var note by remember(id){mutableStateOf<String?>(null)}
    var viewerList by remember{mutableStateOf<List<JSONObject>>(emptyList())}
    var showViewers by remember{mutableStateOf(false)}
    LaunchedEffect(id){if(!own)runCatching{api.post("/api/status/$id/view")}.onSuccess{onViewed(id)}}

    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxSize(),color=Color(0xFF0B141A)){
            Column(Modifier.fillMaxSize()){
                Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically){
                    IconButton(onClick=onClose){Icon(Icons.Rounded.Close,null,tint=Color.White)}
                    Avatar(status.optString("ownerDisplayName","ZoolChat"),40.dp)
                    Column(Modifier.weight(1f).padding(horizontal=8.dp)){Text(status.optString("ownerDisplayName","ZoolChat"),color=Color.White,fontWeight=FontWeight.Bold);Text("${index+1}/$count",color=Color.White.copy(.7f),style=MaterialTheme.typography.labelSmall)}
                    if(own)IconButton(onClick={scope.launch{runCatching{api.delete("/api/status/$id")}.onSuccess{onDeleted()}}}){Icon(Icons.Rounded.Delete,null,tint=Color.White)}
                }
                Row(Modifier.fillMaxWidth().padding(horizontal=10.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)){repeat(count){i->LinearProgressIndicator(progress={if(i<=index)1f else 0f},modifier=Modifier.weight(1f).height(3.dp))}}
                Box(Modifier.fillMaxWidth().weight(1f),contentAlignment=Alignment.Center){
                    StatusContent(api,status)
                    IconButton(enabled=index>0,onClick=onPrevious,modifier=Modifier.align(Alignment.CenterStart)){Icon(Icons.Rounded.ChevronLeft,null,tint=Color.White,modifier=Modifier.size(40.dp))}
                    IconButton(onClick=onNext,modifier=Modifier.align(Alignment.CenterEnd)){Icon(Icons.Rounded.ChevronRight,null,tint=Color.White,modifier=Modifier.size(40.dp))}
                }
                status.optString("addYoursPrompt").takeIf{it.isNotBlank()&&it!="null"}?.let{prompt->AssistChip(onClick={onAddYours(prompt)},label={Text("➕ $prompt")},modifier=Modifier.align(Alignment.CenterHorizontally))}
                if(own){
                    TextButton(onClick={scope.launch{runCatching{api.get("/api/status/$id/viewers").optJSONArray("viewers")?:JSONArray()}.onSuccess{a->viewerList=(0 until a.length()).map{a.getJSONObject(it)};showViewers=true}}},modifier=Modifier.align(Alignment.CenterHorizontally)){Icon(Icons.Rounded.Visibility,null,tint=Color.White);Spacer(Modifier.width(5.dp));Text("${status.optInt("viewCount",0)} ${s("مشاهدة","views")} • ${status.optInt("likeCount",0)} ${s("إعجاب","likes")}",color=Color.White)}
                }else{
                    LazyRow(Modifier.fillMaxWidth().padding(horizontal=12.dp),horizontalArrangement=Arrangement.SpaceEvenly){items(listOf("😍","😂","😮","😥","🙏","👏","🎉","💯")){emoji->Text(emoji,style=MaterialTheme.typography.titleLarge,modifier=Modifier.clickable{scope.launch{runCatching{api.post("/api/status/$id/reaction",JSONObject().put("emoji",emoji))}.onSuccess{note=s("تم إرسال التفاعل","Reaction sent")}}}.padding(8.dp))}}
                    Row(Modifier.fillMaxWidth().padding(10.dp),verticalAlignment=Alignment.CenterVertically){
                        OutlinedTextField(reply,{reply=it.take(2000)},placeholder={Text(s("رد خاص…","Private reply…"))},singleLine=true,modifier=Modifier.weight(1f),colors=OutlinedTextFieldDefaults.colors(focusedTextColor=Color.White,unfocusedTextColor=Color.White,focusedBorderColor=Color.White.copy(.7f),unfocusedBorderColor=Color.White.copy(.4f),focusedPlaceholderColor=Color.White.copy(.6f),unfocusedPlaceholderColor=Color.White.copy(.6f)))
                        IconButton(onClick={scope.launch{runCatching{api.post("/api/status/$id/like")}.onSuccess{liked=it.optBoolean("liked")}}}){Icon(if(liked)Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,null,tint=if(liked)Color(0xFFE91E63) else Color.White)}
                        IconButton(enabled=reply.trim().isNotBlank(),onClick={scope.launch{runCatching{api.post("/api/status/$id/reply",JSONObject().put("text",reply.trim()))}.onSuccess{reply="";note=s("تم إرسال الرد بشكل خاص","Private reply sent")}}}){Icon(Icons.Rounded.Send,null,tint=Color.White)}
                        if(status.optBoolean("canReshare"))IconButton(onClick={scope.launch{runCatching{api.post("/api/status/$id/reshare")}.onSuccess{note=s("تمت إعادة المشاركة","Status reshared")}}}){Icon(Icons.Rounded.IosShare,null,tint=Color.White)}
                    }
                }
                note?.let{Text(it,color=Color.White.copy(.8f),modifier=Modifier.padding(start=16.dp,end=16.dp,bottom=6.dp))}
            }
        }
    }
    if(showViewers)AlertDialog(
        onDismissRequest={showViewers=false},title={Text(s("من شاهد حالتك","Status viewers"))},
        text={if(viewerList.isEmpty())Text(s("لا توجد مشاهدات بعد","No views yet"))else LazyColumn(Modifier.heightIn(max=420.dp)){items(viewerList,key={it.optString("userId")}){v->ListItem(headlineContent={Text(v.optString("displayName","ZoolChat"))},supportingContent={Text(buildString{v.optString("username").takeIf{it.isNotBlank()&&it!="null"}?.let{append("@$it ")};v.optString("reaction").takeIf{it.isNotBlank()&&it!="null"}?.let{append(it)}})},trailingContent={if(v.optBoolean("liked"))Icon(Icons.Rounded.Favorite,null,tint=MaterialTheme.colorScheme.primary)})}}},
        confirmButton={TextButton(onClick={showViewers=false}){Text(s("إغلاق","Close"))}},
    )
}

@Composable
private fun StatusContent(api:ApiClient,status:JSONObject){
    val kind=status.optString("kind");val text=status.optString("text")
    val media=status.optJSONArray("media")?:JSONArray()
    val all=(0 until media.length()).map{media.getJSONObject(it)}
    val primary=all.filter{it.optString("role")=="primary"};val music=all.firstOrNull{it.optString("role")=="music"};val stickers=all.filter{it.optString("role")=="sticker"}
    Column(Modifier.fillMaxWidth().padding(horizontal=36.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
        when(kind){
            "image"->primary.firstOrNull()?.optString("url")?.let{StatusImage(api,it,Modifier.fillMaxWidth().heightIn(max=560.dp))}
            "collage"->LazyColumn(Modifier.fillMaxWidth().heightIn(max=560.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){items(primary,key={it.optString("id")}){m->StatusImage(api,m.optString("url"),Modifier.fillMaxWidth().height(180.dp))}}
            "video"->primary.firstOrNull()?.optString("url")?.let{StatusVideo(api,it)}
            "voice"->primary.firstOrNull()?.optString("url")?.let{StatusAudio(api,it,s("حالة صوتية","Voice status"))}
            "music"->music?.optString("url")?.let{StatusAudio(api,it,s("موسيقى الحالة","Status music"))}
            "link"->{Icon(Icons.Rounded.Link,null,tint=Color.White,modifier=Modifier.size(56.dp));val url=status.optString("linkUrl");Text(Uri.parse(url).host?:url,color=Color.White,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text(url,color=Color(0xFF66D9A5),modifier=Modifier.padding(top=8.dp))}
            else->Text(text,color=Color.White,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
        }
        if(kind!="text"&&text.isNotBlank())Text(text,color=Color.White,style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(top=12.dp))
        if(stickers.isNotEmpty())LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp),modifier=Modifier.padding(top=10.dp)){items(stickers,key={it.optString("id")}){m->StatusImage(api,m.optString("url"),Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)))}}
    }
}

@Composable
private fun StatusImage(api:ApiClient,url:String,modifier:Modifier){
    var bitmap by remember(url){mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)}
    LaunchedEffect(url){bitmap=runCatching{BitmapFactory.decodeFile(api.downloadMedia(url,"status-image").absolutePath)?.asImageBitmap()}.getOrNull()}
    if(bitmap==null)CircularProgressIndicator()else Image(bitmap!!,null,modifier=modifier,contentScale=ContentScale.Fit)
}

@Composable
private fun StatusVideo(api:ApiClient,url:String){
    var file by remember(url){mutableStateOf<File?>(null)}
    LaunchedEffect(url){file=runCatching{api.downloadMedia(url,"status-video.mp4")}.getOrNull()}
    if(file==null)CircularProgressIndicator()else AndroidView(factory={context->VideoView(context).apply{setVideoPath(file!!.absolutePath);setOnPreparedListener{it.isLooping=true;start()}}},update={if(!it.isPlaying)it.start()},modifier=Modifier.fillMaxWidth().heightIn(min=240.dp,max=560.dp))
}

@Composable
private fun StatusAudio(api:ApiClient,url:String,label:String){
    val context=LocalContext.current
    var file by remember(url){mutableStateOf<File?>(null)}
    var player by remember(url){mutableStateOf<MediaPlayer?>(null)}
    var playing by remember(url){mutableStateOf(false)}
    LaunchedEffect(url){
        file=runCatching{api.downloadMedia(url,"status-audio.m4a")}.getOrNull()
        player?.release();player=file?.let{MediaPlayer.create(context,Uri.fromFile(it))}
        player?.setOnCompletionListener{playing=false}
    }
    DisposableEffect(url){onDispose{runCatching{player?.release()}}}
    Column(horizontalAlignment=Alignment.CenterHorizontally){
        Icon(Icons.Rounded.GraphicEq,null,tint=Color.White,modifier=Modifier.size(82.dp));Text(label,color=Color.White)
        FilledTonalIconButton(enabled=player!=null,onClick={player?.let{p->if(playing){p.pause();playing=false}else{p.start();playing=true}}}){Icon(if(playing)Icons.Rounded.Pause else Icons.Rounded.PlayArrow,null)}
    }
}
