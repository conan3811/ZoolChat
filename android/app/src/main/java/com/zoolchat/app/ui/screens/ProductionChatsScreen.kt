package com.zoolchat.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zoolchat.app.data.RemoteChat
import com.zoolchat.app.ui.components.Avatar
import com.zoolchat.app.ui.i18n.t
import com.zoolchat.app.ui.theme.ZoolGreen

@Composable
fun ProductionChatsScreen(chats:List<RemoteChat>,onRefresh:()->Unit,onOpenChat:(RemoteChat)->Unit,onNewChat:()->Unit,onNewGroup:()->Unit,onSettings:()->Unit){
    var query by remember{mutableStateOf("")}
    val filtered=remember(chats,query){chats.filter{query.isBlank()||it.title.contains(query,true)||(it.lastMessage?.contains(query,true)==true)}}
    val bg = MaterialTheme.colorScheme.background
    val textColor = MaterialTheme.colorScheme.onBackground
    val secondaryText = MaterialTheme.colorScheme.onSurfaceVariant
    val fieldBg = MaterialTheme.colorScheme.surfaceVariant
    Scaffold(containerColor=bg,floatingActionButton={Column(horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy(10.dp)){SmallFloatingActionButton(onClick=onNewGroup,containerColor=MaterialTheme.colorScheme.surfaceVariant){Icon(Icons.Rounded.GroupAdd,t("مجموعة جديدة"))};FloatingActionButton(onClick=onNewChat,containerColor=ZoolGreen,shape=RoundedCornerShape(18.dp),modifier=Modifier.size(54.dp)){Icon(Icons.Rounded.Edit,t("رسالة جديدة"))}}}){pad->
        LazyColumn(Modifier.fillMaxSize().padding(pad),contentPadding=PaddingValues(bottom=80.dp)){
            item{Row(Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically){Text("ZoolChat",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Black,color=textColor,modifier=Modifier.weight(1f));TextButton(onClick=onSettings){Text("⚙",color=textColor)}}}
            item{OutlinedTextField(query,{query=it},modifier=Modifier.fillMaxWidth().padding(horizontal=14.dp),placeholder={Text(t("ابحث في المحادثات"),color=secondaryText)},leadingIcon={Icon(Icons.Rounded.Search,null,tint=secondaryText)},singleLine=true,shape=RoundedCornerShape(24.dp),colors=OutlinedTextFieldDefaults.colors(focusedTextColor=textColor,unfocusedTextColor=textColor,cursorColor=MaterialTheme.colorScheme.primary,focusedContainerColor=fieldBg,unfocusedContainerColor=fieldBg,focusedBorderColor=MaterialTheme.colorScheme.primary,unfocusedBorderColor=MaterialTheme.colorScheme.outlineVariant));Spacer(Modifier.height(8.dp))}
            items(filtered,key={it.id}){chat->Row(Modifier.fillMaxWidth().clickable{onOpenChat(chat)}.padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically){Avatar(chat.title,50.dp);Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Row{Text(chat.title,color=textColor,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis);Text(formatTime(chat.lastMessageAt),color=secondaryText,style=MaterialTheme.typography.labelSmall)};Spacer(Modifier.height(3.dp));Row(verticalAlignment=Alignment.CenterVertically){Text(chat.lastMessage?:t("ابدأ المحادثة"),color=secondaryText,modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis);if(chat.unread>0)Surface(shape=CircleShape,color=ZoolGreen){Text(chat.unread.toString(),color=MaterialTheme.colorScheme.onPrimary,modifier=Modifier.padding(horizontal=7.dp,vertical=2.dp),style=MaterialTheme.typography.labelSmall)}}}};HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant,modifier=Modifier.padding(start=78.dp))}
            if(filtered.isEmpty())item{Box(Modifier.fillParentMaxSize().padding(48.dp),contentAlignment=Alignment.Center){Text(t("لا توجد محادثات بعد"),color=secondaryText)}}
        }
    }
}
private fun formatTime(value:String?):String{if(value.isNullOrBlank())return "";return value.substringAfter('T').take(5)}
