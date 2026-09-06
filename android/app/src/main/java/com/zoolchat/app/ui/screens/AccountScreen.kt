package com.zoolchat.app.ui.screens

import com.zoolchat.app.ui.i18n.t

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.zoolchat.app.network.ApiClient
import kotlinx.coroutines.launch

private data class DeviceRow(val id:String,val name:String,val lastSeen:String)

@Composable
fun AccountScreen(
    api: ApiClient,
    username: String,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    onDeleteAccount: () -> Unit,
) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<DeviceRow>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var showDelete by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf<String?>(null) }
    val createExport=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->
        val payload=exporting; if(uri!=null&&payload!=null) runCatching { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use{it.write(payload)} }
        exporting=null
    }
    fun refreshDevices(){scope.launch{runCatching{api.devices()}.onSuccess{arr->devices=(0 until arr.length()).map{i->val o=arr.getJSONObject(i);DeviceRow(o.getString("id"),o.optString("deviceName","Android"),o.optString("lastSeenAt"))}}.onFailure{error=it.message}}}
    LaunchedEffect(Unit){refreshDevices()}

    Column(Modifier.fillMaxSize().padding(horizontal=14.dp)){
        Row(Modifier.fillMaxWidth().heightIn(min=56.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton(onClick=onBack){Icon(Icons.Rounded.ArrowBack,"رجوع")}
            Text(t("الحساب والأجهزة"),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
        }
        Text(if(username.isBlank()) t("حساب ZoolChat") else "@$username",color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=6.dp))
        error?.let{Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(vertical=6.dp))}
        Text(t("الأجهزة المرتبطة"),fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=12.dp,bottom=6.dp))
        LazyColumn(Modifier.weight(1f)){
            items(devices,key={it.id}){d->
                ListItem(
                    headlineContent={Text(d.name)},
                    supportingContent={Text(d.lastSeen.ifBlank{t("نشط")})},
                    leadingContent={Icon(Icons.Rounded.PhoneAndroid,null)},
                    trailingContent={IconButton(onClick={scope.launch{runCatching{api.revokeDevice(d.id)}.onSuccess{refreshDevices()}.onFailure{error=it.message}}}){Icon(Icons.Rounded.LinkOff,"إلغاء ربط الجهاز")}}
                )
                HorizontalDivider()
            }
        }
        Button(onClick={scope.launch{runCatching{api.exportAccount().toString(2)}.onSuccess{exporting=it;createExport.launch("zoolchat-export.json")}.onFailure{error=it.message}}},modifier=Modifier.fillMaxWidth()){
            Icon(Icons.Rounded.Download,null);Spacer(Modifier.width(8.dp));Text(t("تصدير بياناتي"))
        }
        OutlinedButton(onClick=onLogout,modifier=Modifier.fillMaxWidth()){
            Icon(Icons.Rounded.Logout,null);Spacer(Modifier.width(8.dp));Text(t("تسجيل الخروج"))
        }
        TextButton(onClick={showDelete=true},modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)){
            Icon(Icons.Rounded.DeleteForever,null);Spacer(Modifier.width(8.dp));Text(t("حذف حساب ZoolChat نهائياً"))
        }
        Spacer(Modifier.height(8.dp))
    }

    if(showDelete) AlertDialog(
        onDismissRequest={showDelete=false},
        title={Text(t("حذف الحساب نهائياً؟"))},
        text={Text(t("سيُحذف الحساب والرسائل والبيانات المرتبطة به من خادم ZoolChat. لا يمكن التراجع عن هذه العملية."))},
        confirmButton={TextButton(onClick={showDelete=false;onDeleteAccount()},colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)){Text(t("حذف نهائي"))}},
        dismissButton={TextButton(onClick={showDelete=false}){Text(t("إلغاء"))}},
    )
}
