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

private data class StatusPerson(val userId:String,val displayName:String,val username:String?,val photoUrl:String?)
private data class StatusGroup(val ownerId:String,val displayName:String,val statuses:List<JSONObject>)
private fun statusLabel(ar:String,en:String)=if(I18n.language==AppLanguage.AR) ar else en

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoriesScreen(
    api:ApiClient,
    myUserId:String,
    contacts:List<RegisteredContact>,
    onOpenSettings:()->Unit,
) {
    val scope=rememberCoroutineScope()
    var statuses by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var people by remember { mutableStateOf<List<StatusPerson>>(contacts.map{StatusPerson(it.userId,it.displayName,it.username,it.photoUrl)}) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var composer by remember { mutableStateOf(false) }
    var viewerStatuses by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var viewerIndex by remember { mutableIntStateOf(0) }
    var seededAddYours by remember { mutableStateOf("") }

    LaunchedEffect(contacts,refreshKey) {
        loading=true;error=null
        runCatching {
            val candidateArr=api.get("/api/status/audience-candidates").optJSONArray("users")?:JSONArray()
            val merged=linkedMapOf<String,StatusPerson>()
            contacts.forEach{merged[it.userId]=StatusPerson(it.userId,it.displayName,it.username,it.photoUrl)}
            for(i in 0 until candidateArr.length()){
                val o=candidateArr.getJSONObject(i);val id=o.optString("userId");if(id.isBlank())continue
                merged.putIfAbsent(id,StatusPerson(id,o.optString("displayName","ZoolChat"),o.optString("username").takeIf{it.isNotBlank()&&it!="null"},o.optString("photoUrl").takeIf{it.isNotBlank()&&it!="null"}))
            }
            people=merged.values.toList()
            val arr=api.get("/api/status").optJSONArray("statuses")?:JSONArray()
            statuses=(0 until arr.length()).map{arr.getJSONObject(it)}
        }.onFailure {
            error=statusLabel("تعذر تحميل الحالات. ستعمل بعد نشر Backend v0.32.","Unable to load statuses. They will work once Backend v0.32 is deployed.")
        }
        loading=false
    }

    val grouped=remember(statuses){
        statuses.groupBy{it.optString("ownerId")}.map{(owner,list)->
            StatusGroup(owner,list.lastOrNull()?.optString("ownerDisplayName","ZoolChat")?:"ZoolChat",list.sortedBy{it.optString("createdAt")})
        }
    }
    val mine=grouped.firstOrNull{it.ownerId==myUserId}
    val others=grouped.filter{it.ownerId!=myUserId}.sortedByDescending{it.statuses.lastOrNull()?.optString("createdAt")}

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
            Text(statusLabel("التحديثات","Updates"),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
            IconButton(onClick={refreshKey++}){Icon(Icons.Rounded.Refresh,statusLabel("تحديث","Refresh"))}
            IconButton(onClick=onOpenSettings){Icon(Icons.Rounded.Settings,statusLabel("الإعدادات","Settings"))}
        }
        if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
        ListItem(
            headlineContent={Text(statusLabel("حالتي","My status"),fontWeight=FontWeight.SemiBold)},
            supportingContent={Text(if(mine==null)statusLabel("اضغط لإضافة حالة","Tap to add a status") else statusLabel("اضغط للعرض أو أضف تحديثاً جديداً","Tap to view or add another update"))},
            leadingContent={
                Box{
                    Avatar(statusLabel("أنا","Me"),56.dp,modifier=Modifier.border(2.dp,if(mine!=null)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,CircleShape).padding(3.dp))
                    Icon(Icons.Rounded.AddCircle,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(22.dp).align(Alignment.BottomEnd).background(MaterialTheme.colorScheme.surface,CircleShape))
                }
            },
            modifier=Modifier.clickable{
                if(mine==null){seededAddYours="";composer=true}else{viewerStatuses=mine.statuses;viewerIndex=0}
            },
            trailingContent={IconButton(onClick={seededAddYours="";composer=true}){Icon(Icons.Rounded.Add,statusLabel("إضافة حالة","Add status"))}},
        )
        HorizontalDivider()
        Text(statusLabel("الحالات الحديثة","Recent updates"),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,modifier=Modifier.padding(16.dp))
        if(others.isEmpty()&&!loading)Text(statusLabel("لا توجد حالات متاحة الآن","No status updates available"),modifier=Modifier.padding(horizontal=16.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.weight(1f)){
            items(others,key={it.ownerId}){g->
                val unseen=g.statuses.any{!it.optBoolean("viewed")}
                ListItem(
                    headlineContent={Text(g.displayName,fontWeight=FontWeight.SemiBold)},
                    supportingContent={Text("${g.statuses.size} ${statusLabel("تحديث","update")}")},
                    leadingContent={Avatar(g.displayName,54.dp,modifier=Modifier.border(3.dp,if(unseen)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,CircleShape).padding(3.dp))},
                    modifier=Modifier.clickable{viewerStatuses=g.statuses;viewerIndex=g.statuses.indexOfFirst{!it.optBoolean("viewed")}.takeIf{it>=0}?:0},
                )
            }
            item{
                HorizontalDivider(Modifier.padding(top=8.dp))
                Text(statusLabel("القنوات","Channels"),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,modifier=Modifier.padding(16.dp))
                Text(statusLabel("القنوات مستقلة عن الحالات وسيتم تطويرها ضمن مسار المجتمعات والقنوات.","Channels are separate from Status and are developed with Communities and Channels."),modifier=Modifier.padding(horizontal=16.dp,bottom=24.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        error?.let{Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(12.dp))}
    }

    if(composer)StatusComposer(
        api=api,
        people=people,
        contacts=contacts,
        initialAddYours=seededAddYours,
        onDismiss={composer=false;seededAddYours=""},
        onPublished={composer=false;seededAddYours="";refreshKey++},
    )

    if(viewerStatuses.isNotEmpty()){
        val current=viewerStatuses.getOrNull(viewerIndex)
        if(current!=null)StatusViewer(
            api=api,
            status=current,
            myUserId=myUserId,
            index=viewerIndex,
            count=viewerStatuses.size,
            onViewed={id->
                statuses=statuses.map{o->if(o.optString("id")==id)JSONObject(o.toString()).put("viewed",true) else o}
            },
            onPrevious={if(viewerIndex>0)viewerIndex--},
            onNext={if(viewerIndex<viewerStatuses.lastIndex)viewerIndex++ else viewerStatuses=emptyList()},
            onClose={viewerStatuses=emptyList()},
            onDeleted={viewerStatuses=emptyList();refreshKey++},
            onAddYours={prompt->viewerStatuses=emptyList();seededAddYours=prompt;composer=true},
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
    var imageUris by remember{mutableStateOf<List<Uri>>(emptyList())}
    var videoUri by remember{mutableStateOf<Uri?>(null)}
    var musicUri by remember{mutableStateOf<Uri?>(null)}
    var voiceFile by remember{mutableStateOf<File?>(null)}
    var stickerUris by remember{mutableStateOf<List<Uri>>(emptyList())}
    var recording by remember{mutableStateOf(false)}
    var audienceMode by remember{mutableStateOf("contacts")}
    var audienceIds by remember{mutableStateOf<Set<String>>(emptySet())}
    var mentionIds by remember{mutableStateOf<Set<String>>(emptySet())}
    var showAudience by remember{mutableStateOf(false)}
    var showMentions by remember{mutableStateOf(false)}
    var addYours by remember(initialAddYours){mutableStateOf(initialAddYours)}
    var busy by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf<String?>(null)}

    val imagePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->imageUris=uris.take(6);if(imageUris.isNotEmpty())kind=if(imageUris.size>1)"collage" else "image"}
    val videoPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null){videoUri=uri;kind="video"}}
    val musicPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null){musicUri=uri;kind="music"}}
    val stickerPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->stickerUris=uris.take(10)}
    val micPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->
        if(granted){recorder.start().onSuccess{recording=true;voiceFile=null;kind="voice"}.onFailure{error=statusLabel("تعذر بدء التسجيل","Unable to start recording")}}
        else error=statusLabel("يلزم إذن الميكروفون لتسجيل حالة صوتية","Microphone permission is required for voice status")
    }
    LaunchedEffect(recording){
        if(recording){delay(30_000);if(recorder.isRecording){recorder.stop().onSuccess{voiceFile=it}.onFailure{error=statusLabel("تعذر حفظ التسجيل","Unable to save recording")};recording=false}}
    }
    DisposableEffect(Unit){onDispose{if(recorder.isRecording)recorder.cancel()}}

    fun toggle(set:Set<String>,id:String)=if(id in set)set-id else set+id
    suspend fun uploadUris(uris:List<Uri>,prefix:String):List<String>{
        return uris.mapIndexed{i,uri->
            val mime=context.contentResolver.getType(uri)?:"application/octet-stream"
            api.uploadMedia(uri,mime,"${prefix}_${i}").getString("id")
        }
    }

    ModalBottomSheet(onDismissRequest=onDismiss){
        Column(Modifier.fillMaxWidth().padding(horizontal=16.dp).padding(bottom=24.dp)){
            Text(statusLabel("إضافة حالة","Add status"),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
            LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.padding(vertical=12.dp)){
                val modes=listOf(
                    "text" to statusLabel("نص","Text"),"image" to statusLabel("صورة","Photo"),"video" to statusLabel("فيديو","Video"),
                    "voice" to statusLabel("صوت","Voice"),"link" to statusLabel("رابط","Link"),"collage" to statusLabel("تخطيط","Layout"),"music" to statusLabel("موسيقى","Music")
                )
                items(modes,key={it.first}){m->FilterChip(selected=kind==m.first,onClick={kind=m.first},label={Text(m.second)})}
            }
            when(kind){
                "text"->OutlinedTextField(text,{text=it.take(1000)},label={Text(statusLabel("اكتب حالة","Type a status"))},modifier=Modifier.fillMaxWidth(),minLines=4,maxLines=8)
                "image"->Column{OutlinedButton(onClick={imagePicker.launch(arrayOf("image/*"))}){Icon(Icons.Rounded.PhotoCamera,null);Spacer(Modifier.width(6.dp));Text(statusLabel("اختيار صورة","Choose photo"))};Text(statusLabel("يمكنك إضافة ملصقات صور ونص وذكر خاص.","You can add photo stickers, text and private mentions."))}
                "collage"->Column{OutlinedButton(onClick={imagePicker.launch(arrayOf("image/*"))}){Icon(Icons.Rounded.Collections,null);Spacer(Modifier.width(6.dp));Text(statusLabel("اختر حتى 6 صور","Choose up to 6 photos"))};Text("${imageUris.size}/6")}
                "video"->OutlinedButton(onClick={videoPicker.launch(arrayOf("video/*"))}){Icon(Icons.Rounded.Movie,null);Spacer(Modifier.width(6.dp));Text(statusLabel("اختيار فيديو","Choose video"))}
                "voice"->Column{
                    Button(onClick={if(recording){{recorder.stop().onSuccess{voiceFile=it}.onFailure{error=statusLabel("تعذر حفظ التسجيل","Unable to save recording")};recording=false}} else {{micPermission.launch(Manifest.permission.RECORD_AUDIO)}}}){
                        Icon(if(recording)Icons.Rounded.Stop else Icons.Rounded.Mic,null);Spacer(Modifier.width(6.dp));Text(if(recording)statusLabel("إيقاف وحفظ التسجيل","Stop and save") else statusLabel("تسجيل صوت حتى 30 ثانية","Record up to 30 seconds"))
                    }
                    if(voiceFile!=null)Text(statusLabel("التسجيل جاهز للنشر","Recording ready to publish"),color=MaterialTheme.colorScheme.primary)
                }
                "link"->OutlinedTextField(link,{link=it.take(2048)},label={Text(statusLabel("الرابط","Link"))},placeholder={Text("https://")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                "music"->Column{OutlinedButton(onClick={musicPicker.launch(arrayOf("audio/*"))}){Icon(Icons.Rounded.MusicNote,null);Spacer(Modifier.width(6.dp));Text(statusLabel("اختيار مقطع صوتي من الجهاز","Choose audio from device"))};Text(statusLabel("كتالوج الموسيقى المرخّص يحتاج مزود حقوق موسيقية خارجي؛ ZoolChat يدعم حالياً الصوت الذي يختاره المستخدم من جهازه.","A licensed music catalog requires an external music-rights provider; ZoolChat currently supports user-selected device audio."),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            }
            if(kind!="text"&&kind!="link")OutlinedTextField(text,{text=it.take(1000)},label={Text(statusLabel("تعليق","Caption"))},modifier=Modifier.fillMaxWidth().padding(top=8.dp),maxLines=3)
            if(kind in listOf("image","video","collage","music"))OutlinedButton(onClick={stickerPicker.launch(arrayOf("image/*"))},modifier=Modifier.padding(top=6.dp)){Icon(Icons.Rounded.StickyNote2,null);Spacer(Modifier.width(6.dp));Text("${statusLabel("ملصقات صور","Photo stickers")} (${stickerUris.size})")}
            OutlinedTextField(addYours,{addYours=it.take(120)},label={Text(statusLabel("أضف ملصق «أضف ما لديك» (اختياري)","Add an “Add Yours” prompt (optional)"))},modifier=Modifier.fillMaxWidth().padding(top=8.dp),singleLine=true)
            Row(Modifier.fillMaxWidth().padding(top=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedButton(onClick={showAudience=true},modifier=Modifier.weight(1f)){Icon(Icons.Rounded.Visibility,null);Spacer(Modifier.width(4.dp));Text(statusLabel("خصوصية الحالة","Status privacy"),maxLines=1)}
                OutlinedButton(onClick={showMentions=true},modifier=Modifier.weight(1f)){Icon(Icons.Rounded.AlternateEmail,null);Spacer(Modifier.width(4.dp));Text("${statusLabel("ذكر خاص","Private mentions")} (${mentionIds.size})",maxLines=1)}
            }
            Text(when(audienceMode){"except"->statusLabel("جهات اتصالي باستثناء المحددين","My contacts except selected");"only"->statusLabel("المشاركة فقط مع المحددين","Only share with selected");else->statusLabel("جهات اتصالي","My contacts")},style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=6.dp))
            error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            Button(enabled=!busy&&!recording,onClick={
                busy=true;error=null
                scope.launch{
                    try{
                        val primaryUris=when(kind){"image","collage"->imageUris;"video"->listOfNotNull(videoUri);"voice"->listOfNotNull(voiceFile?.let{Uri.fromFile(it)});else->emptyList()}
                        if(kind=="text"&&text.trim().isBlank())error(statusLabel("اكتب نص الحالة","Enter status text"))
                        if(kind=="link"&&!link.trim().startsWith("http"))error(statusLabel("أدخل رابطاً صالحاً يبدأ بـ http","Enter a valid link beginning with http"))
                        if(kind in listOf("image","collage","video","voice")&&primaryUris.isEmpty())error(statusLabel("اختر أو سجّل الوسائط أولاً","Choose or record media first"))
                        if(kind=="music"&&musicUri==null)error(statusLabel("اختر مقطعاً صوتياً أولاً","Choose an audio track first"))
                        val mediaIds=uploadUris(primaryUris,"status")
                        val stickerIds=uploadUris(stickerUris,"status_sticker")
                        val musicId=musicUri?.let{uploadUris(listOf(it),"status_music").firstOrNull()}
                        val ids=when(audienceMode){"contacts"->contacts.map{it.userId}.distinct();else->audienceIds.toList()}
                        val body=JSONObject()
                            .put("kind",kind)
                            .put("text",text.trim())
                            .put("mediaIds",JSONArray(mediaIds))
                            .put("stickerMediaIds",JSONArray(stickerIds))
                            .put("audienceMode",audienceMode)
                            .put("audienceUserIds",JSONArray(ids))
                            .put("mentionedUserIds",JSONArray(mentionIds.toList()))
                            .put("addYoursPrompt",addYours.trim().takeIf{it.isNotBlank()}?:JSONObject.NULL)
                        if(kind=="link")body.put("linkUrl",link.trim())
                        if(kind=="collage")body.put("layout",JSONObject().put("template",if(imageUris.size<=4)"grid" else "six-grid").put("count",imageUris.size))
                        if(musicId!=null)body.put("musicMediaId",musicId)
                        api.post("/api/status",body)
                        voiceFile?.delete();onPublished()
                    }catch(e:Exception){error=e.message?:statusLabel("تعذر نشر الحالة","Unable to publish status")}
                    finally{busy=false}
                }
            },modifier=Modifier.fillMaxWidth().height(52.dp).padding(top=8.dp)){
                if(busy)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)else Text(statusLabel("نشر الحالة","Post status"))
            }
        }
    }

    if(showAudience)AlertDialog(
        onDismissRequest={showAudience=false},
        title={Text(statusLabel("خصوصية الحالة","Status privacy"))},
        text={Column{
            listOf("contacts" to statusLabel("جهات اتصالي","My contacts"),"except" to statusLabel("جهات اتصالي باستثناء…","My contacts except…"),"only" to statusLabel("المشاركة فقط مع…","Only share with…")).forEach{(mode,label)->
                Row(Modifier.fillMaxWidth().clickable{audienceMode=mode}.padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically){RadioButton(audienceMode==mode,{audienceMode=mode});Text(label)}
            }
            if(audienceMode!="contacts")LazyColumn(Modifier.heightIn(max=260.dp)){items(people,key={it.userId}){p->Row(Modifier.fillMaxWidth().clickable{audienceIds=toggle(audienceIds,p.userId)}.padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically){Checkbox(p.userId in audienceIds,{audienceIds=toggle(audienceIds,p.userId)});Text(p.displayName)}}}
        }},
        confirmButton={TextButton(onClick={showAudience=false}){Text(statusLabel("تم","Done"))}},
    )
    if(showMentions)AlertDialog(
        onDismissRequest={showMentions=false},title={Text(statusLabel("ذكر خاص","Private mentions"))},
        text={Column{Text(statusLabel("سيتمكن الأشخاص المذكورون من رؤية الحالة وإعادة مشاركتها.","Mentioned people can see this status and reshare it."));LazyColumn(Modifier.heightIn(max=330.dp)){items(people,key={it.userId}){p->Row(Modifier.fillMaxWidth().clickable{if(mentionIds.size<50||p.userId in mentionIds)mentionIds=toggle(mentionIds,p.userId)}.padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically){Checkbox(p.userId in mentionIds,{if(mentionIds.size<50||p.userId in mentionIds)mentionIds=toggle(mentionIds,p.userId)});Column{Text(p.displayName);p.username?.let{Text("@$it",style=MaterialTheme.typography.bodySmall)}}}}}}},
        confirmButton={TextButton(onClick={showMentions=false}){Text(statusLabel("تم","Done"))}},
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
    val id=status.optString("id")
    val ownerId=status.optString("ownerId")
    val own=ownerId==myUserId
    var liked by remember(id){mutableStateOf(status.optBoolean("liked"))}
    var reply by remember(id){mutableStateOf("")}
    var feedback by remember(id){mutableStateOf<String?>(null)}
    var viewers by remember{mutableStateOf<List<JSONObject>>(emptyList())}
    var showViewers by remember{mutableStateOf(false)}
    LaunchedEffect(id){
        if(!own)runCatching{api.post("/api/status/$id/view")}.onSuccess{onViewed(id)}
    }
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxSize(),color=Color(0xFF0B141A)){
            Column(Modifier.fillMaxSize()){
                Row(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
                    IconButton(onClick=onClose){Icon(Icons.Rounded.Close,null,tint=Color.White)}
                    Avatar(status.optString("ownerDisplayName","ZoolChat"),40.dp)
                    Column(Modifier.weight(1f).padding(horizontal=8.dp)){Text(status.optString("ownerDisplayName","ZoolChat"),color=Color.White,fontWeight=FontWeight.Bold);Text("${index+1}/$count",color=Color.White.copy(.7f),style=MaterialTheme.typography.labelSmall)}
                    if(own)IconButton(onClick={scope.launch{runCatching{api.delete("/api/status/$id")}.onSuccess{onDeleted()}}}){Icon(Icons.Rounded.Delete,null,tint=Color.White)}
                }
                Row(Modifier.fillMaxWidth().padding(horizontal=10.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)){repeat(count){i->LinearProgressIndicator(progress={if(i<=index)1f else 0f},modifier=Modifier.weight(1f).height(3.dp))}}
                Box(Modifier.fillMaxWidth().weight(1f),contentAlignment=Alignment.Center){
                    StatusContent(api,status)
                    IconButton(onClick=onPrevious,enabled=index>0,modifier=Modifier.align(Alignment.CenterStart)){Icon(Icons.Rounded.ChevronLeft,null,tint=Color.White,modifier=Modifier.size(42.dp))}
                    IconButton(onClick=onNext,modifier=Modifier.align(Alignment.CenterEnd)){Icon(Icons.Rounded.ChevronRight,null,tint=Color.White,modifier=Modifier.size(42.dp))}
                }
                status.optString("addYoursPrompt").takeIf{it.isNotBlank()&&it!="null"}?.let{prompt->AssistChip(onClick={onAddYours(prompt)},label={Text("➕ $prompt")},modifier=Modifier.align(Alignment.CenterHorizontally))}
                if(own){
                    TextButton(onClick={scope.launch{runCatching{api.get("/api/status/$id/viewers").optJSONArray("viewers")?:JSONArray()}.onSuccess{arr->viewers=(0 until arr.length()).map{arr.getJSONObject(it)};showViewers=true}}},modifier=Modifier.align(Alignment.CenterHorizontally)){Icon(Icons.Rounded.Visibility,null,tint=Color.White);Spacer(Modifier.width(5.dp));Text("${status.optInt("viewCount",0)} ${statusLabel("مشاهدة","views")} • ${status.optInt("likeCount",0)} ${statusLabel("إعجاب","likes")}",color=Color.White)}
                }else{
                    LazyRow(Modifier.fillMaxWidth().padding(horizontal=12.dp),horizontalArrangement=Arrangement.SpaceEvenly){items(listOf("❤️","😂","😮","😢","🙏","👏","🔥","🎉")){emoji->Text(emoji,modifier=Modifier.clickable{scope.launch{runCatching{api.post("/api/status/$id/reaction",JSONObject().put("emoji",emoji))}.onSuccess{feedback=statusLabel("تم إرسال التفاعل","Reaction sent")}}}.padding(8.dp),style=MaterialTheme.typography.titleLarge)}}
                    Row(Modifier.fillMaxWidth().padding(10.dp),verticalAlignment=Alignment.CenterVertically){
                        OutlinedTextField(reply,{reply=it.take(2000)},placeholder={Text(statusLabel("رد خاص…","Private reply…"))},singleLine=true,modifier=Modifier.weight(1f),colors=OutlinedTextFieldDefaults.colors(focusedTextColor=Color.White,unfocusedTextColor=Color.White,focusedBorderColor=Color.White.copy(.7f),unfocusedBorderColor=Color.White.copy(.4f),focusedPlaceholderColor=Color.White.copy(.6f),unfocusedPlaceholderColor=Color.White.copy(.6f)))
                        IconButton(onClick={scope.launch{runCatching{val r=api.post("/api/status/$id/like");liked=r.optBoolean("liked")}}}){Icon(if(liked)Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,null,tint=if(liked)Color(0xFFE91E63) else Color.White)}
                        IconButton(enabled=reply.trim().isNotBlank(),onClick={scope.launch{runCatching{api.post("/api/status/$id/reply",JSONObject().put("text",reply.trim()))}.onSuccess{reply="";feedback=statusLabel("تم إرسال الرد بشكل خاص","Private reply sent")}}}){Icon(Icons.Rounded.Send,null,tint=Color.White)}
                        if(status.optBoolean("canReshare"))IconButton(onClick={scope.launch{runCatching{api.post("/api/status/$id/reshare")}.onSuccess{feedback=statusLabel("تمت إعادة المشاركة إلى حالتك","Reshared to your status")}}}){Icon(Icons.Rounded.IosShare,null,tint=Color.White)}
                    }
                }
                feedback?.let{Text(it,color=Color.White.copy(.8f),modifier=Modifier.padding(horizontal=16.dp,bottom=8.dp))}
            }
        }
    }
    if(showViewers)AlertDialog(onDismissRequest={showViewers=false},title={Text(statusLabel("من شاهد حالتك","Status viewers"))},text={if(viewers.isEmpty())Text(statusLabel("لا توجد مشاهدات بعد","No views yet"))else LazyColumn(Modifier.heightIn(max=420.dp)){items(viewers,key={it.optString("userId")}){v->ListItem(headlineContent={Text(v.optString("displayName","ZoolChat"))},supportingContent={Text(buildString{v.optString("username").takeIf{it.isNotBlank()&&it!="null"}?.let{append("@$it ")};v.optString("reaction").takeIf{it.isNotBlank()&&it!="null"}?.let{append(it)}})},trailingContent={if(v.optBoolean("liked"))Icon(Icons.Rounded.Favorite,null,tint=MaterialTheme.colorScheme.primary)})}}},confirmButton={TextButton(onClick={showViewers=false}){Text(statusLabel("إغلاق","Close"))}})
}

@Composable
private fun StatusContent(api:ApiClient,status:JSONObject){
    val kind=status.optString("kind")
    val text=status.optString("text")
    val link=status.optString("linkUrl").takeIf{it.isNotBlank()&&it!="null"}
    val media=status.optJSONArray("media")?:JSONArray()
    val primary=(0 until media.length()).map{media.getJSONObject(it)}.filter{it.optString("role")=="primary"}
    val music=(0 until media.length()).map{media.getJSONObject(it)}.firstOrNull{it.optString("role")=="music"}
    val stickers=(0 until media.length()).map{media.getJSONObject(it)}.filter{it.optString("role")=="sticker"}
    Column(Modifier.fillMaxWidth().padding(horizontal=36.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
        when(kind){
            "image"->primary.firstOrNull()?.optString("url")?.let{StatusImage(api,it,Modifier.fillMaxWidth().heightIn(max=560.dp))}
            "collage"->LazyColumn(Modifier.fillMaxWidth().heightIn(max=560.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){items(primary,key={it.optString("id")}){m->StatusImage(api,m.optString("url"),Modifier.fillMaxWidth().height(180.dp))}}
            "video"->primary.firstOrNull()?.optString("url")?.let{StatusVideo(api,it)}
            "voice"->primary.firstOrNull()?.optString("url")?.let{StatusAudio(api,it,statusLabel("حالة صوتية","Voice status"))}
            "music"->music?.optString("url")?.let{StatusAudio(api,it,statusLabel("موسيقى الحالة","Status music"))}
            "link"->{Icon(Icons.Rounded.Link,null,tint=Color.White,modifier=Modifier.size(58.dp));link?.let{Text(Uri.parse(it).host?:it,color=Color.White,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text(it,color=Color(0xFF66D9A5),modifier=Modifier.padding(top=8.dp))}}
            else->Text(text,color=Color.White,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
        }
        if(kind!="text"&&text.isNotBlank())Text(text,color=Color.White,style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(top=14.dp))
        if(stickers.isNotEmpty())LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp),modifier=Modifier.padding(top=10.dp)){items(stickers,key={it.optString("id")}){m->StatusImage(api,m.optString("url"),Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)))}}
    }
}

@Composable
private fun StatusImage(api:ApiClient,url:String,modifier:Modifier=Modifier){
    var image by remember(url){mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)}
    LaunchedEffect(url){image=runCatching{BitmapFactory.decodeFile(api.downloadMedia(url,"status-image").absolutePath)?.asImageBitmap()}.getOrNull()}
    if(image!=null)Image(image!!,null,modifier=modifier,contentScale=ContentScale.Fit)else CircularProgressIndicator()
}

@Composable
private fun StatusVideo(api:ApiClient,url:String){
    var file by remember(url){mutableStateOf<File?>(null)}
    LaunchedEffect(url){file=runCatching{api.downloadMedia(url,"status-video.mp4")}.getOrNull()}
    if(file==null)CircularProgressIndicator()else AndroidView(
        factory={context->VideoView(context).apply{setVideoPath(file!!.absolutePath);setOnPreparedListener{it.isLooping=true;start()}}},
        update={if(!it.isPlaying)it.start()},
        modifier=Modifier.fillMaxWidth().heightIn(min=240.dp,max=560.dp),
    )
}

@Composable
private fun StatusAudio(api:ApiClient,url:String,label:String){
    var file by remember(url){mutableStateOf<File?>(null)}
    var playing by remember(url){mutableStateOf(false)}
    val player=remember(url){MediaPlayer()}
    LaunchedEffect(url){file=runCatching{api.downloadMedia(url,"status-audio.m4a")}.getOrNull()}
    DisposableEffect(player){onDispose{runCatching{player.release()}}}
    Column(horizontalAlignment=Alignment.CenterHorizontally){
        Icon(Icons.Rounded.GraphicEq,null,tint=Color.White,modifier=Modifier.size(86.dp));Text(label,color=Color.White)
        FilledTonalIconButton(enabled=file!=null,onClick={
            if(playing){runCatching{player.pause()};playing=false}else{
                runCatching{
                    if(player.currentPosition==0){player.reset();player.setDataSource(file!!.absolutePath);player.prepare()}
                    player.start();playing=true;player.setOnCompletionListener{playing=false}
                }
            }
        }){Icon(if(playing)Icons.Rounded.Pause else Icons.Rounded.PlayArrow,null)}
    }
}
