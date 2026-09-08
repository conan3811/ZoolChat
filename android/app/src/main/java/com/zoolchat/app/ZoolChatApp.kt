package com.zoolchat.app

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.zoolchat.app.auth.RecentAuthSecret
import com.zoolchat.app.state.ZoolAppViewModel
import com.zoolchat.app.ui.i18n.AppLanguage
import com.zoolchat.app.ui.i18n.I18n
import com.zoolchat.app.ui.i18n.t
import com.zoolchat.app.ui.screens.*
import com.zoolchat.app.ui.theme.ThemeMode
import kotlinx.coroutines.launch

private data class BottomItem(val route:String,val label:String,val icon:androidx.compose.ui.graphics.vector.ImageVector)

@Composable
fun ZoolChatApp(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    incomingCall: IncomingCallNav? = null,
    onIncomingCallConsumed: () -> Unit = {},
    openChatId: String? = null,
    onOpenChatConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("zoolchat_preferences", Context.MODE_PRIVATE) }
    var languageSelected by remember { mutableStateOf(prefs.getBoolean("language_selected", false)) }
    var appLanguage by remember { mutableStateOf(AppLanguage.fromCode(prefs.getString("app_language", "ar"))) }
    SideEffect { I18n.language = appLanguage }
    val direction = if (appLanguage == AppLanguage.AR) LayoutDirection.Rtl else LayoutDirection.Ltr

    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        if (!languageSelected) {
            LanguageSelectionScreen { selected ->
                appLanguage = selected
                I18n.language = selected
                prefs.edit().putString("app_language", selected.code).putBoolean("language_selected", true).apply()
                languageSelected = true
            }
            return@CompositionLocalProvider
        }

        val vm: ZoolAppViewModel = viewModel()
        val authScope = rememberCoroutineScope()
        var authenticated by remember { mutableStateOf(vm.isAuthenticated) }
        var profileSetupPending by remember { mutableStateOf(vm.session.initialProfileSetupPending) }
        var sessionIdentityChecked by remember { mutableStateOf(!vm.isAuthenticated || vm.e2ee.hasLocalIdentity()) }

        // Never enter Chats with a stale authenticated session whose local E2EE private
        // identity is gone. Re-authentication provides the verified password required to
        // restore the encrypted backup or safely rotate an unrecoverable legacy identity.
        LaunchedEffect(Unit) {
            if (vm.isAuthenticated && !vm.e2ee.hasLocalIdentity()) {
                vm.auth.signOut()
                authenticated = false
                profileSetupPending = false
            }
            sessionIdentityChecked = true
        }

        if (!sessionIdentityChecked) {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                CircularProgressIndicator()
            }
            return@CompositionLocalProvider
        }

        if (!authenticated) {
            PhoneAuthScreen(vm.auth,vm.session) {
                val password=RecentAuthSecret.consume()
                if(password==null){
                    authenticated = vm.isAuthenticated
                    profileSetupPending = vm.session.initialProfileSetupPending
                } else {
                    authScope.launch {
                        val identityReady = runCatching { vm.e2ee.prepareIdentityAfterPasswordAuth(password) }.getOrDefault(false)
                        if (!identityReady) {
                            vm.auth.signOut()
                            authenticated = false
                            profileSetupPending = false
                        } else {
                            authenticated = vm.isAuthenticated
                            profileSetupPending = vm.session.initialProfileSetupPending
                        }
                    }
                }
            }
            return@CompositionLocalProvider
        }

        if (profileSetupPending) {
            InitialProfileSetupScreen(vm.api,vm.session) {
                profileSetupPending = vm.session.initialProfileSetupPending
            }
            return@CompositionLocalProvider
        }

        LaunchedEffect(authenticated,profileSetupPending) {
            if(authenticated&&!profileSetupPending) {
                vm.bootstrap()
                vm.discoverContacts()
            }
        }
        ProductionNav(
            vm = vm,
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
            appLanguage = appLanguage,
            onLanguageChange = { newLanguage ->
                appLanguage = newLanguage
                I18n.language = newLanguage
                prefs.edit().putString("app_language", newLanguage.code).putBoolean("language_selected", true).apply()
            },
            incomingCall = incomingCall,
            onIncomingCallConsumed = onIncomingCallConsumed,
            openChatId = openChatId,
            onOpenChatConsumed = onOpenChatConsumed,
            onSignedOut = {
                authenticated = vm.isAuthenticated
                profileSetupPending = vm.session.initialProfileSetupPending
            },
        )
    }
}

@Composable
private fun ProductionNav(
    vm: ZoolAppViewModel,
    themeMode: ThemeMode,
    onThemeModeChange:(ThemeMode)->Unit,
    appLanguage: AppLanguage,
    onLanguageChange:(AppLanguage)->Unit,
    incomingCall:IncomingCallNav?,
    onIncomingCallConsumed:()->Unit,
    openChatId:String?,
    onOpenChatConsumed:()->Unit,
    onSignedOut:()->Unit,
){
    val permissionLauncher=androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { vm.session.notificationsExplained=true }
    LaunchedEffect(Unit) { if(android.os.Build.VERSION.SDK_INT>=33&&!vm.session.notificationsExplained)permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
    val nav=rememberNavController()
    val scope=rememberCoroutineScope()
    var memberId by remember { mutableStateOf<String?>(null) }
    var memberChatId by remember { mutableStateOf("") }
    val chats by vm.chats.collectAsState()
    val contacts by vm.contacts.collectAsState()
    val searchResults by vm.directorySearch.collectAsState()
    val callHistory by vm.callHistory.collectAsState()
    val messages by vm.messages.collectAsState()
    val hasMore by vm.hasMoreMessages.collectAsState()
    val realtimeIncoming by vm.incomingRealtimeCall.collectAsState()
    val presence by vm.presence.collectAsState()
    val typing by vm.typing.collectAsState()
    val me by vm.currentUser.collectAsState()
    val current by nav.currentBackStackEntryAsState();val route=current?.destination?.route.orEmpty();val showBottom=route in setOf("chats","stories","communities","calls","meet")
    LaunchedEffect(incomingCall){if(incomingCall!=null){nav.navigate("livecall/incoming/${incomingCall.callId}/${incomingCall.video}/${incomingCall.autoAccept}");onIncomingCallConsumed()}}
    LaunchedEffect(realtimeIncoming){if(realtimeIncoming!=null){nav.navigate("livecall/incoming/${realtimeIncoming!!.first}/${realtimeIncoming!!.second}/false");vm.consumeIncomingRealtimeCall()}}
    LaunchedEffect(openChatId){if(!openChatId.isNullOrBlank()){nav.navigate(if(openChatId=="__meet__") "meet" else "prodchat/$openChatId");onOpenChatConsumed()}}

    memberId?.let { id ->
        GroupMemberSheet(
            api=vm.api,
            chatId=memberChatId,
            userId=id,
            myUserId=me?.id.orEmpty(),
            onDismiss={memberId=null},
            onMessage={memberId=null;vm.openDirect(id){nav.navigate("prodchat/$it")}},
            onCall={video->memberId=null;nav.navigate("livecall/new/$id/$video")},
            onProfile={memberId=null;if(id==me?.id)nav.navigate("profile") else nav.navigate("contact/$id/$memberChatId")},
        )
    }
    Scaffold(Modifier.fillMaxSize(),containerColor=MaterialTheme.colorScheme.background,bottomBar={if(showBottom)ZoolBottomBar(nav)}){inner->
        NavHost(nav,"chats",Modifier.padding(inner)){
            composable("chats"){ProductionChatsScreen(chats,{vm.refreshChats()},{c->nav.navigate("prodchat/${c.id}")},{nav.navigate("contacts")},{nav.navigate("group-create")},{nav.navigate("profile")})}
            composable("group-create"){GroupCreateScreen(vm.api,contacts,searchResults,{vm.searchUsers(it)},{nav.popBackStack()}){id->vm.refreshChats();nav.navigate("prodchat/$id"){popUpTo("chats")}}}
            composable("contacts"){
                ContactsDirectoryScreen(
                    contacts=contacts,
                    searchResults=searchResults,
                    onSearch={vm.searchUsers(it)},
                    onBack={nav.popBackStack()},
                    onChat={c->vm.openDirect(c.userId){id->nav.navigate("prodchat/$id")}},
                    onCall={c->nav.navigate("livecall/new/${c.userId}/false")},
                    onProfile={c->nav.navigate("user/${c.userId}")},
                )
            }
            composable("user/{peer}") { entry ->
                val peer=entry.arguments?.getString("peer").orEmpty()
                val fallback=searchResults.firstOrNull{it.userId==peer} ?: contacts.firstOrNull{it.userId==peer}
                ContactInfoScreen(
                    api=vm.api,
                    userId=peer,
                    chatId="",
                    messages=emptyList(),
                    fallbackDisplayName=fallback?.displayName,
                    fallbackUsername=fallback?.username,
                    fallbackPhotoUrl=fallback?.photoUrl,
                    onBack={nav.popBackStack()},
                    onMessage={vm.openDirect(peer){nav.navigate("prodchat/$it")}},
                    onCall={video->nav.navigate("livecall/new/$peer/$video")},
                    onShared={},
                )
            }
            composable("prodchat/{id}"){entry->
                val id=entry.arguments?.getString("id").orEmpty()
                val c=chats.firstOrNull{it.id==id}
                ProductionChatScreen(
                    api=vm.api,
                    e2ee=vm.e2ee,
                    peerUserId=c?.peerUserId,
                    chatId=id,
                    title=c?.title?:"ZoolChat",
                    myUserId=me?.id.orEmpty(),
                    messages=messages[id].orEmpty(),
                    hasMore=hasMore[id]?:false,
                    onBack={nav.popBackStack()},
                    onLoad={vm.loadMessages(id)},
                    onLoadOlder={vm.loadOlderMessages(id)},
                    onSend={vm.sendMessage(id,it)},
                    onSendMedia={uri,mime,name->vm.sendMedia(id,uri,mime,name)},
                    onRetry={vm.retryMessage(it)},
                    onEditMessage={messageId,body->vm.editMessage(id,messageId,body)},
                    onDeleteForEveryone={messageId->vm.deleteMessage(id,messageId)},
                    onReact={messageId,emoji->vm.reactMessage(id,messageId,emoji)},
                    onMarkRead={vm.markRead(id)},
                    peerPresence=c?.peerUserId?.let { presence[it] },
                    peerTyping=typing[id] ?: false,
                    onRefreshPresence={vm.refreshPresence(c?.peerUserId)},
                    onTypingChanged={active->vm.setTyping(id,active)},
                    onVoiceCall={c?.peerUserId?.let{nav.navigate("livecall/new/$it/false")}},
                    onVideoCall={c?.peerUserId?.let{nav.navigate("livecall/new/$it/true")}},
                    isGroup=c?.type=="group",
                    onOpenGroupInfo={nav.navigate("group-info/$id")},
                    onOpenProfile={c?.peerUserId?.let { nav.navigate("contact/$it/$id") }},
                    onMember={memberId=it;memberChatId=id},
                )
            }
            composable("contact/{peer}/{chat}") { entry ->
                val peer=entry.arguments?.getString("peer").orEmpty();val chat=entry.arguments?.getString("chat").orEmpty()
                val c=chats.firstOrNull{it.id==chat}
                val fallback=contacts.firstOrNull{it.userId==peer} ?: searchResults.firstOrNull{it.userId==peer}
                ContactInfoScreen(
                    api=vm.api,
                    userId=peer,
                    chatId=chat,
                    messages=messages[chat].orEmpty(),
                    fallbackDisplayName=fallback?.displayName?:c?.title,
                    fallbackUsername=fallback?.username,
                    fallbackPhotoUrl=fallback?.photoUrl,
                    onBack={nav.popBackStack()},
                    onMessage={vm.openDirect(peer){nav.navigate("prodchat/$it")}},
                    onCall={video->nav.navigate("livecall/new/$peer/$video")},
                    onShared={kind->nav.navigate("shared/$chat/$kind")},
                )
            }
            composable("shared/{chat}/{kind}"){entry->
                val chat=entry.arguments?.getString("chat").orEmpty();val kind=entry.arguments?.getString("kind").orEmpty();val c=chats.firstOrNull{it.id==chat}
                SharedContentScreen(vm.api,vm.e2ee,c?.peerUserId,chat,kind,messages[chat].orEmpty()){nav.popBackStack()}
            }
            composable("stories"){
                StoriesScreen(
                    api=vm.api,
                    myUserId=me?.id.orEmpty(),
                    contacts=contacts,
                    onOpenSettings={nav.navigate("profile")},
                )
            }
            composable("communities"){CommunitiesScreen{nav.navigate("profile")}}
            composable("calls"){
                ProductionCallsScreen(
                    calls=callHistory,
                    onRefresh={vm.refreshCallHistory()},
                    onStartWithPeer={peer,video->nav.navigate("livecall/new/$peer/$video")},
                    onStartQuick={video->val peer=contacts.firstOrNull()?.userId;if(peer!=null)nav.navigate("livecall/new/$peer/$video") else nav.navigate("contacts")},
                    onOpenSettings={nav.navigate("profile")},
                )
            }
            composable("meet"){MeetScreen(vm.api,me?.id.orEmpty(),me?.displayName?:"ZoolChat"){nav.navigate("profile")}}
            composable("group-info/{id}"){e->
                val id=e.arguments?.getString("id").orEmpty()
                GroupInfoScreen(
                    api=vm.api,
                    chatId=id,
                    contacts=contacts,
                    onBack={nav.popBackStack()},
                    onLeaveDone={vm.refreshChats();nav.navigate("chats"){popUpTo("chats"){inclusive=true}}},
                    onShared={kind->nav.navigate("shared/$id/$kind")},
                )
            }
            composable("profile"){
                SettingsHubV032(
                    themeMode=themeMode,
                    onThemeModeChange=onThemeModeChange,
                    appLanguage=appLanguage,
                    onLanguageChange=onLanguageChange,
                    accountDisplayName=me?.displayName,
                    accountUsername=me?.username,
                    onBack={nav.popBackStack()},
                    onEditProfile={nav.navigate("edit-profile")},
                    onPrivacy={nav.navigate("privacy")},
                    onAccount={nav.navigate("account")},
                    onChats={nav.navigate("settings-chats")},
                    onNotifications={nav.navigate("settings-notifications")},
                    onStorageData={nav.navigate("settings-storage-data")},
                    onHelp={nav.navigate("settings-help")},
                    onAbout={nav.navigate("about")},
                    onLogout={vm.logout(onSignedOut)},
                )
            }
            composable("settings-chats"){ ChatSettingsScreen { nav.popBackStack() } }
            composable("settings-notifications"){ NotificationSettingsScreen { nav.popBackStack() } }
            composable("settings-storage-data"){ StorageDataSettingsScreen { nav.popBackStack() } }
            composable("settings-help"){ HelpSettingsScreen(onBack={nav.popBackStack()},onAbout={nav.navigate("about")}) }
            composable("account"){
                AccountScreen(
                    api=vm.api,
                    e2ee=vm.e2ee,
                    username=me?.username.orEmpty(),
                    onBack={nav.popBackStack()},
                    onEditIdentity={nav.navigate("edit-profile")},
                    onLogout={vm.logout(onSignedOut)},
                    onDeleteAccount={vm.deleteAccount(onSignedOut)},
                )
            }
            composable("about"){ AboutScreen { nav.popBackStack() } }
            composable("edit-profile") { AccountIdentityScreen(vm.api,{nav.popBackStack()},{vm.bootstrap()}) }
            composable("privacy"){PrivacyV032Screen(vm.api){nav.popBackStack()}}
            composable("livecall/new/{peer}/{video}"){e->ProductionCallScreen(vm.api,e.arguments?.getString("peer"),null,e.arguments?.getString("video").toBoolean(),false){vm.refreshCallHistory();nav.popBackStack()}}
            composable("livecall/incoming/{callId}/{video}/{accept}"){e->ProductionCallScreen(vm.api,null,e.arguments?.getString("callId"),e.arguments?.getString("video").toBoolean(),e.arguments?.getString("accept").toBoolean()){vm.refreshCallHistory();nav.popBackStack()}}
        }
    }
}

@Composable
private fun ZoolBottomBar(nav:NavHostController){
    val items=listOf(
        BottomItem("chats",t("الدردشات"),Icons.Rounded.ChatBubble),
        BottomItem("stories",t("التحديثات"),Icons.Rounded.AutoStories),
        BottomItem("communities",t("المجتمعات"),Icons.Rounded.Groups),
        BottomItem("calls",t("المكالمات"),Icons.Rounded.PhoneInTalk),
        BottomItem("meet","Meet",Icons.Rounded.VideoCameraFront),
    )
    val current by nav.currentBackStackEntryAsState();val route=current?.destination?.route
    NavigationBar(tonalElevation=0.dp,containerColor=MaterialTheme.colorScheme.surface){items.forEach{item->NavigationBarItem(route==item.route,{nav.navigate(item.route){popUpTo(nav.graph.findStartDestination().id){saveState=true};launchSingleTop=true;restoreState=true}},{Icon(item.icon,item.label,Modifier.size(20.dp))},label={Text(item.label,fontSize=10.sp,maxLines=1)},alwaysShowLabel=false)}}
}
