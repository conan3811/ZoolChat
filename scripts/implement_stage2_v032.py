from pathlib import Path


def patch(path, old, new):
    p=Path(path); s=p.read_text()
    if old not in s:
        raise SystemExit(f'pattern not found in {path}: {old[:100]!r}')
    p.write_text(s.replace(old,new,1))

# --- Server integration: real 2FA, session-aware devices, profile-link resolver. ---
patch('server/src/index.ts',
"import { emailDeliveryConfigured, installEmailRecovery } from './emailRecovery.js';",
"import { emailDeliveryConfigured, installEmailRecovery } from './emailRecovery.js';\nimport { beginLoginTwoFactorIfEnabled, installTwoFactor } from './twoFactor.js';")
patch('server/src/index.ts',
"installEmailRecovery(app, id => { for(const socket of sockets.get(id) ?? []) socket.close(4001, 'session_revoked'); sockets.delete(id); });",
"installEmailRecovery(app, id => { for(const socket of sockets.get(id) ?? []) socket.close(4001, 'session_revoked'); sockets.delete(id); });\ninstallTwoFactor(app);")
patch('server/src/index.ts',
"  const session=await createSession(r.rows[0].id,b.deviceName);\n  res.json({user:userJson(r.rows[0]),...session});",
"  try {\n    const secondFactor=await beginLoginTwoFactorIfEnabled(r.rows[0].id,b.deviceName);\n    if(secondFactor) return res.status(202).json(secondFactor);\n  } catch(e:any) {\n    if(e?.message==='two_factor_rate_limited') return res.status(429).json({error:'two_factor_rate_limited'});\n    return res.status(503).json({error:'email_delivery_unavailable'});\n  }\n  const session=await createSession(r.rows[0].id,b.deviceName);\n  res.json({user:userJson(r.rows[0]),...session});")

old_fcm="""  await pool.query(`INSERT INTO devices(user_id,fcm_token,platform,device_name,last_seen_at,revoked_at) VALUES($1,$2,$3,$4,now(),NULL)
    ON CONFLICT(fcm_token) DO UPDATE SET user_id=EXCLUDED.user_id,device_name=EXCLUDED.device_name,last_seen_at=now(),revoked_at=NULL,updated_at=now()`, [req.zoolUser!.id, b.token, b.platform, b.deviceName ?? null]);"""
new_fcm="""  await pool.query(`INSERT INTO devices(user_id,fcm_token,platform,device_name,auth_session_id,last_seen_at,revoked_at) VALUES($1,$2,$3,$4,$5,now(),NULL)
    ON CONFLICT(fcm_token) DO UPDATE SET user_id=EXCLUDED.user_id,device_name=EXCLUDED.device_name,auth_session_id=EXCLUDED.auth_session_id,last_seen_at=now(),revoked_at=NULL,updated_at=now()`, [req.zoolUser!.id, b.token, b.platform, b.deviceName ?? null, req.zoolUser!.sessionId ?? null]);"""
patch('server/src/index.ts',old_fcm,new_fcm)
old_devices="""app.get('/api/devices', requireAuth, async (req, res) => {
  const sessions = await pool.query(`SELECT id,'android'::text platform,device_name,created_at,last_seen_at FROM auth_sessions WHERE user_id=$1 AND revoked_at IS NULL AND refresh_expires_at>now() ORDER BY last_seen_at DESC`, [req.zoolUser!.id]);
  const pushDevices = await pool.query(`SELECT id,platform,device_name,created_at,last_seen_at FROM devices WHERE user_id=$1 AND revoked_at IS NULL ORDER BY last_seen_at DESC`, [req.zoolUser!.id]);
  const seen=new Set<string>();
  const rows=[...sessions.rows,...pushDevices.rows].filter(x=>{const key=`${x.device_name??''}|${x.platform??''}`;if(seen.has(key))return false;seen.add(key);return true;});
  res.json({ devices: rows.map(x => ({ id:x.id, platform:x.platform, deviceName:x.device_name, createdAt:new Date(x.created_at).toISOString(), lastSeenAt:new Date(x.last_seen_at).toISOString() })) });
});
app.delete('/api/devices/:id', requireAuth, async (req, res) => {
  await pool.query(`UPDATE auth_sessions SET revoked_at=now() WHERE id=$1 AND user_id=$2`, [req.params.id, req.zoolUser!.id]);
  await pool.query(`UPDATE devices SET revoked_at=now() WHERE id=$1 AND user_id=$2`, [req.params.id, req.zoolUser!.id]);
  res.json({ ok: true });
});"""
new_devices="""app.get('/api/devices', requireAuth, async (req, res) => {
  const sessions = await pool.query(`SELECT id,'android'::text platform,device_name,created_at,last_seen_at FROM auth_sessions WHERE user_id=$1 AND revoked_at IS NULL AND refresh_expires_at>now() ORDER BY last_seen_at DESC`, [req.zoolUser!.id]);
  const currentId=req.zoolUser!.sessionId ?? null;
  res.json({ devices: sessions.rows.map(x => ({
    id:String(x.id), platform:x.platform, deviceName:x.device_name ?? 'Android',
    createdAt:new Date(x.created_at).toISOString(), lastSeenAt:new Date(x.last_seen_at).toISOString(),
    current:Boolean(currentId && String(x.id)===String(currentId)),
  })) });
});
app.delete('/api/devices/:id', requireAuth, async (req, res) => {
  const owned=await pool.query(`SELECT id,device_name FROM auth_sessions WHERE id=$1 AND user_id=$2 AND revoked_at IS NULL`, [req.params.id, req.zoolUser!.id]);
  if(!owned.rowCount)return res.status(404).json({error:'device_not_found'});
  const current=Boolean(req.zoolUser!.sessionId && String(req.params.id)===String(req.zoolUser!.sessionId));
  await pool.query(`UPDATE auth_sessions SET revoked_at=now() WHERE id=$1 AND user_id=$2`, [req.params.id, req.zoolUser!.id]);
  await pool.query(`UPDATE devices SET revoked_at=now(),updated_at=now() WHERE user_id=$1 AND auth_session_id=$2`, [req.zoolUser!.id, req.params.id]);
  res.json({ ok: true, current });
});
app.post('/api/devices/logout-others', requireAuth, async (req,res)=>{
  const current=req.zoolUser!.sessionId;
  if(!current)return res.status(409).json({error:'session_management_unavailable'});
  const r=await pool.query(`UPDATE auth_sessions SET revoked_at=now() WHERE user_id=$1 AND id<>$2 AND revoked_at IS NULL RETURNING id`,[req.zoolUser!.id,current]);
  await pool.query(`UPDATE devices SET revoked_at=now(),updated_at=now() WHERE user_id=$1 AND auth_session_id IS NOT NULL AND auth_session_id<>$2 AND revoked_at IS NULL`,[req.zoolUser!.id,current]);
  res.json({ok:true,revoked:r.rowCount,currentSessionId:current});
});"""
patch('server/src/index.ts',old_devices,new_devices)

patch('server/src/auth.ts',
"zoolUser?: { id:string; firebaseUid?:string; phoneE164?:string; displayName:string; authMode?:'free'|'firebase'|'dev' }",
"zoolUser?: { id:string; firebaseUid?:string; phoneE164?:string; displayName:string; authMode?:'free'|'firebase'|'dev'; sessionId?:string }")
patch('server/src/auth.ts',
"req.zoolUser={id:free.id,firebaseUid:free.firebase_uid??undefined,phoneE164:free.phone_e164??undefined,displayName:free.display_name,authMode:'free'};",
"req.zoolUser={id:free.id,firebaseUid:free.firebase_uid??undefined,phoneE164:free.phone_e164??undefined,displayName:free.display_name,authMode:'free',sessionId:String(free.session_id)};")

profile_insert="""
 app.get('/api/users/by-username/:username',requireAuth,async(req,res)=>{
  const username=z.string().trim().min(3).max(32).regex(/^[A-Za-z0-9_.]+$/).safeParse(req.params.username);
  if(!username.success)return res.status(400).json({error:'invalid_request'});
  const viewer=req.zoolUser!.id;
  const r=await pool.query(`SELECT u.id,u.display_name,u.username,u.profile_photo_media_id,COALESCE(p.profile_photo_visibility,'contacts') profile_photo_visibility
    FROM users u LEFT JOIN user_privacy_settings p ON p.user_id=u.id
    WHERE lower(u.username)=lower($1) AND u.account_status='active' LIMIT 1`,[username.data]);
  if(!r.rowCount)return res.status(404).json({error:'user_not_found'});
  const u=r.rows[0];
  const blocked=await pool.query('SELECT 1 FROM blocks WHERE (blocker_id=$1 AND blocked_id=$2) OR (blocker_id=$2 AND blocked_id=$1)',[viewer,u.id]);
  if(blocked.rowCount)return res.status(404).json({error:'user_not_found'});
  const showPhoto=await canSee(viewer,u.id,u.profile_photo_visibility);
  res.json({user:{userId:u.id,displayName:u.display_name,username:u.username,photoUrl:showPhoto&&u.profile_photo_media_id?`/api/media/${u.profile_photo_media_id}`:null}});
 });

"""
patch('server/src/profileV032.ts'," app.put('/api/me',requireAuth,async(req,res)=>{",profile_insert+" app.put('/api/me',requireAuth,async(req,res)=>{")
patch('server/src/profileV032.ts',
"  if((viewer!==target&&!shared)||blocked.rowCount)return res.status(404).json({error:'profile_unavailable'});",
"  if(blocked.rowCount)return res.status(404).json({error:'profile_unavailable'});")

patch('server/src/emailRecovery.ts',
"      await c.query('UPDATE devices SET revoked_at=now() WHERE user_id=$1 AND revoked_at IS NULL',[userId]);\n      await c.query('DELETE FROM account_recovery WHERE user_id=$1',[userId]);",
"      await c.query('UPDATE devices SET revoked_at=now() WHERE user_id=$1 AND revoked_at IS NULL',[userId]);\n      await c.query('UPDATE user_two_factor_settings SET enabled=false,enabled_at=NULL,updated_at=now() WHERE user_id=$1',[userId]);\n      await c.query('UPDATE two_factor_challenges SET consumed_at=now() WHERE user_id=$1 AND consumed_at IS NULL',[userId]);\n      await c.query('DELETE FROM account_recovery WHERE user_id=$1',[userId]);")

# --- Android API/auth integration. ---
patch('android/app/src/main/java/com/zoolchat/app/network/ApiClient.kt',
"    suspend fun devices(): JSONArray = get(\"/api/devices\").optJSONArray(\"devices\") ?: JSONArray()\n    suspend fun revokeDevice(deviceId: String) = delete(\"/api/devices/$deviceId\")",
"    suspend fun devices(): JSONArray = get(\"/api/devices\").optJSONArray(\"devices\") ?: JSONArray()\n    suspend fun revokeDevice(deviceId: String) = delete(\"/api/devices/$deviceId\")\n    suspend fun logoutOtherDevices(): JSONObject = post(\"/api/devices/logout-others\")\n    suspend fun twoFactorStatus(): JSONObject = get(\"/api/me/2fa\")\n    suspend fun requestTwoFactorEnable(password: String): JSONObject = post(\"/api/me/2fa/enable/request\", JSONObject().put(\"password\", password))\n    suspend fun confirmTwoFactorEnable(challengeToken: String, code: String): JSONObject = post(\"/api/me/2fa/enable/confirm\", JSONObject().put(\"challengeToken\", challengeToken).put(\"code\", code))\n    suspend fun requestTwoFactorDisable(password: String): JSONObject = post(\"/api/me/2fa/disable/request\", JSONObject().put(\"password\", password))\n    suspend fun confirmTwoFactorDisable(challengeToken: String, code: String): JSONObject = post(\"/api/me/2fa/disable/confirm\", JSONObject().put(\"challengeToken\", challengeToken).put(\"code\", code))\n    suspend fun resolveUsernameProfile(username: String): JSONObject {\n        val encoded = URLEncoder.encode(username.trim().removePrefix(\"@\"), StandardCharsets.UTF_8.toString())\n        return get(\"/api/users/by-username/$encoded\").getJSONObject(\"user\")\n    }")

old_login="""    suspend fun loginFree(identifier: String, password: String): Result<Unit> = runCatching {
        val id = normalizePhone(identifier)
        require(id.length >= 3) { "أدخل رقم الجوال" }
        require(password.length >= 8) { "كلمة المرور غير صحيحة" }
        val o = authPost("/api/auth/login", JSONObject().put("identifier", id).put("password", password).put("deviceName", deviceName()))
        saveSession(o)
        RecentAuthSecret.put(password)
    }"""
new_login="""    suspend fun loginFree(identifier: String, password: String): Result<String?> = runCatching {
        val id = normalizePhone(identifier)
        require(id.length >= 3) { "أدخل رقم الجوال" }
        require(password.length >= 8) { "كلمة المرور غير صحيحة" }
        val o = authPost("/api/auth/login", JSONObject().put("identifier", id).put("password", password).put("deviceName", deviceName()))
        if (o.optBoolean("twoFactorRequired", false)) {
            RecentAuthSecret.put(password)
            return@runCatching o.getString("challengeToken")
        }
        saveSession(o)
        RecentAuthSecret.put(password)
        null
    }

    suspend fun completeTwoFactorLogin(challengeToken: String, code: String): Result<Unit> = runCatching {
        require(Regex("^\\d{8}$").matches(code)) { "رمز التحقق يجب أن يتكون من 8 أرقام" }
        val o = authPost("/api/auth/login/2fa", JSONObject().put("challengeToken", challengeToken).put("code", code).put("deviceName", deviceName()))
        saveSession(o)
    }"""
patch('android/app/src/main/java/com/zoolchat/app/auth/AuthRepository.kt',old_login,new_login)
patch('android/app/src/main/java/com/zoolchat/app/auth/AuthRepository.kt',
"            \"invalid_recovery_code\" -> \"اسم المستخدم أو رمز الاسترداد غير صحيح\"",
"            \"invalid_recovery_code\" -> \"اسم المستخدم أو رمز الاسترداد غير صحيح\"\n            \"invalid_two_factor_code\" -> \"رمز التحقق غير صحيح أو انتهت صلاحيته\"\n            \"verified_email_required\" -> \"يجب توثيق البريد الإلكتروني أولاً لاستخدام التحقق بخطوتين\"\n            \"two_factor_rate_limited\" -> \"انتظر قليلاً قبل طلب رمز تحقق جديد\"")

# Login screen: pause after password and require the emailed second factor before opening Chats.
patch('android/app/src/main/java/com/zoolchat/app/ui/screens/OnboardingAuthScreens.kt',
"    var codeSent by remember { mutableStateOf(false) }\n    var loading by remember { mutableStateOf(false) }",
"    var codeSent by remember { mutableStateOf(false) }\n    var twoFactorChallenge by remember { mutableStateOf<String?>(null) }\n    var twoFactorCode by remember { mutableStateOf(\"\") }\n    var loading by remember { mutableStateOf(false) }")
patch('android/app/src/main/java/com/zoolchat/app/ui/screens/OnboardingAuthScreens.kt',
"        codeSent = false\n        error = null",
"        codeSent = false\n        twoFactorChallenge = null\n        twoFactorCode = \"\"\n        com.zoolchat.app.auth.RecentAuthSecret.clear()\n        error = null")
patch('android/app/src/main/java/com/zoolchat/app/ui/screens/OnboardingAuthScreens.kt',
"    BackHandler(enabled = mode != \"login\" && !loading) { changeMode(\"login\") }",
"    BackHandler(enabled = (mode != \"login\" || twoFactorChallenge != null) && !loading) {\n        if (twoFactorChallenge != null) { twoFactorChallenge = null; twoFactorCode = \"\"; com.zoolchat.app.auth.RecentAuthSecret.clear() }\n        else changeMode(\"login\")\n    }")
patch('android/app/src/main/java/com/zoolchat/app/ui/screens/OnboardingAuthScreens.kt',
"        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }",
"        if (twoFactorChallenge != null) {\n            Text(authLabel(\"أرسلنا رمز التحقق إلى بريدك الإلكتروني الموثّق.\", \"We sent a verification code to your verified email.\"), color = MaterialTheme.colorScheme.primary)\n            Spacer(Modifier.height(8.dp))\n            OutlinedTextField(\n                value = twoFactorCode, onValueChange = { twoFactorCode = it.filter(Char::isDigit).take(8) },\n                label = { Text(authLabel(\"رمز التحقق بخطوتين\", \"Two-step verification code\")) },\n                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !loading,\n            )\n        }\n        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }")
patch('android/app/src/main/java/com/zoolchat/app/ui/screens/OnboardingAuthScreens.kt',
"                            else -> {\n                                passwordLengthError(password)?.let { throw IllegalArgumentException(it) }\n                                auth.loginFree(nationalNumberToE164(country.region, phone), password).getOrThrow()\n                                onSignedIn()\n                            }",
"                            else -> {\n                                if (twoFactorChallenge != null) {\n                                    auth.completeTwoFactorLogin(twoFactorChallenge!!, twoFactorCode).getOrThrow()\n                                    onSignedIn()\n                                } else {\n                                    passwordLengthError(password)?.let { throw IllegalArgumentException(it) }\n                                    val challenge = auth.loginFree(nationalNumberToE164(country.region, phone), password).getOrThrow()\n                                    if (challenge != null) {\n                                        twoFactorChallenge = challenge\n                                        twoFactorCode = \"\"\n                                    } else onSignedIn()\n                                }\n                            }")

# Account screen: session-aware device management and non-placeholder 2FA setup.
patch('android/app/src/main/java/com/zoolchat/app/ui/screens/AccountScreen.kt',
"private data class DeviceRow(val id:String,val name:String,val lastSeen:String)",
"private data class DeviceRow(val id:String,val name:String,val lastSeen:String,val current:Boolean)")
patch('android/app/src/main/java/com/zoolchat/app/ui/screens/AccountScreen.kt',
"    var showBackup by remember { mutableStateOf(false) }",
"    var showBackup by remember { mutableStateOf(false) }\n    var showTwoFactor by remember { mutableStateOf(false) }\n    var twoFactorEnabled by remember { mutableStateOf(false) }\n    var twoFactorAvailable by remember { mutableStateOf(false) }\n    var pendingRevoke by remember { mutableStateOf<DeviceRow?>(null) }")
patch('android/app/src/main/java/com/zoolchat/app/ui/screens/AccountScreen.kt',
"fun refreshDevices(){scope.launch{runCatching{api.devices()}.onSuccess{arr->devices=(0 until arr.length()).map{i->val o=arr.getJSONObject(i);DeviceRow(o.getString(\"id\"),o.optString(\"deviceName\",\"Android\"),o.optString(\"lastSeenAt\"))}}.onFailure{safeError()}}}",
"fun refreshDevices(){scope.launch{runCatching{api.devices()}.onSuccess{arr->devices=(0 until arr.length()).map{i->val o=arr.getJSONObject(i);DeviceRow(o.getString(\"id\"),o.optString(\"deviceName\",\"Android\"),o.optString(\"lastSeenAt\"),o.optBoolean(\"current\",false))}}.onFailure{safeError()}}}\n    fun refreshTwoFactor(){scope.launch{runCatching{api.twoFactorStatus()}.onSuccess{o->twoFactorEnabled=o.optBoolean(\"enabled\",false);twoFactorAvailable=o.optBoolean(\"available\",false)}.onFailure{safeError()}}}")
patch('android/app/src/main/java/com/zoolchat/app/ui/screens/AccountScreen.kt',
"    LaunchedEffect(Unit){refreshDevices();refreshMe()}",
"    LaunchedEffect(Unit){refreshDevices();refreshMe();refreshTwoFactor()}")
patch('android/app/src/main/java/com/zoolchat/app/ui/screens/AccountScreen.kt',
"                AccountRow(Icons.Rounded.EnhancedEncryption,t(\"التحقق بخطوتين\"),t(\"طبقة أمان إضافية للحساب\")){infoDialog=\"2fa\"}",
"                AccountRow(Icons.Rounded.EnhancedEncryption,t(\"التحقق بخطوتين\"),if(twoFactorEnabled)t(\"مفعّل — رمز بريد بعد كلمة المرور\") else t(\"غير مفعّل — أضف طبقة أمان للحساب\")){showTwoFactor=true}")
old_device_item="""            items(devices,key={it.id}){d->
                ListItem(
                    headlineContent={Text(d.name)},
                    supportingContent={Text(d.lastSeen.ifBlank{t("نشط")})},
                    leadingContent={Icon(Icons.Rounded.PhoneAndroid,null,modifier=Modifier.size(ACCOUNT_ICON_DP.dp))},
                    trailingContent={IconButton(onClick={scope.launch{runCatching{api.revokeDevice(d.id)}.onSuccess{refreshDevices()}.onFailure{safeError()}}}){Icon(Icons.Rounded.LinkOff,t("إلغاء ربط الجهاز"))}}
                )
            }
            item{
                AccountRow(Icons.Rounded.Download"""
new_device_item="""            items(devices,key={it.id}){d->
                ListItem(
                    headlineContent={Text(d.name + if(d.current) " • ${t("هذا الجهاز")}" else "")},
                    supportingContent={Text(d.lastSeen.ifBlank{t("نشط")})},
                    leadingContent={Icon(if(d.current) Icons.Rounded.PhoneAndroid else Icons.Rounded.Devices,null,modifier=Modifier.size(ACCOUNT_ICON_DP.dp))},
                    trailingContent={if(!d.current)IconButton(onClick={pendingRevoke=d}){Icon(Icons.Rounded.LinkOff,t("تسجيل خروج الجهاز"))}}
                )
            }
            item{
                if(devices.count{!it.current}>0) AccountRow(Icons.Rounded.Logout,t("تسجيل خروج الأجهزة الأخرى"),t("يبقي هذا الجهاز مسجلاً ويغلق بقية الجلسات")){\n                    scope.launch{runCatching{api.logoutOtherDevices()}.onSuccess{refreshDevices()}.onFailure{safeError()}}\n                }\n                AccountRow(Icons.Rounded.Download"""
patch('android/app/src/main/java/com/zoolchat/app/ui/screens/AccountScreen.kt',old_device_item,new_device_item)
patch('android/app/src/main/java/com/zoolchat/app/ui/screens/AccountScreen.kt',
"            else->t(\"التحقق بخطوتين يحتاج تحدياً حقيقياً أثناء تسجيل الدخول واسترداداً آمناً. لن نفعّل مفتاحاً شكلياً لا يحمي الحساب فعلياً.\")",
"            else->t(\"هذه الميزة متاحة من إعداد التحقق بخطوتين.\")")
# Insert dialogs before password dialog.
marker="    if(showPassword) PasswordChangeDialog("
insert="""    if(pendingRevoke!=null) AlertDialog(
        onDismissRequest={pendingRevoke=null},
        title={Text(t("تسجيل خروج هذا الجهاز؟"))},
        text={Text(t("سيتم إنهاء جلسة ZoolChat على الجهاز المحدد، ويمكن تسجيل الدخول عليه مرة أخرى لاحقاً."))},
        confirmButton={TextButton(onClick={val d=pendingRevoke;pendingRevoke=null;if(d!=null)scope.launch{runCatching{api.revokeDevice(d.id)}.onSuccess{refreshDevices()}.onFailure{safeError()}}}){Text(t("تسجيل خروج"))}},
        dismissButton={TextButton(onClick={pendingRevoke=null}){Text(t("إلغاء"))}},
    )

    if(showTwoFactor) TwoFactorDialog(
        api=api, enabled=twoFactorEnabled, available=twoFactorAvailable,
        onDismiss={showTwoFactor=false},
        onChanged={twoFactorEnabled=it;showTwoFactor=false;refreshTwoFactor()},
        onError={safeError(it)},
    )

"""
patch('android/app/src/main/java/com/zoolchat/app/ui/screens/AccountScreen.kt',marker,insert+marker)
# Add composable implementation.
marker2="@Composable\nprivate fun PasswordChangeDialog"
two_factor_dialog="""@Composable
private fun TwoFactorDialog(api:ApiClient,enabled:Boolean,available:Boolean,onDismiss:()->Unit,onChanged:(Boolean)->Unit,onError:(String?)->Unit){
    val scope=rememberCoroutineScope()
    var password by remember{mutableStateOf("")};var code by remember{mutableStateOf("")};var challenge by remember{mutableStateOf<String?>(null)};var busy by remember{mutableStateOf(false)}
    AlertDialog(
        onDismissRequest={if(!busy)onDismiss()},
        title={Text(if(enabled)t("إيقاف التحقق بخطوتين") else t("تفعيل التحقق بخطوتين"))},
        text={Column{
            if(!available) Text(t("يجب توثيق البريد الإلكتروني أولاً، كما يجب أن تكون خدمة البريد متاحة."),color=MaterialTheme.colorScheme.error)
            else if(challenge==null){
                Text(t("بعد التفعيل ستحتاج كلمة المرور ثم رمزاً لمرة واحدة يصل إلى بريدك الموثّق عند كل تسجيل دخول جديد."))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(password,{password=it},label={Text(t("كلمة المرور الحالية"))},visualTransformation=PasswordVisualTransformation(),singleLine=true,enabled=!busy)
            }else{
                Text(t("أدخل الرمز المكوّن من 8 أرقام المرسل إلى بريدك الموثّق."))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(code,{code=it.filter(Char::isDigit).take(8)},label={Text(t("رمز التحقق"))},singleLine=true,enabled=!busy)
            }
        }},
        confirmButton={Button(enabled=available&&!busy&&(if(challenge==null)password.length>=8 else code.length==8),onClick={
            busy=true
            scope.launch{
                if(challenge==null){
                    runCatching{if(enabled)api.requestTwoFactorDisable(password) else api.requestTwoFactorEnable(password)}
                        .onSuccess{challenge=it.getString("challengeToken");busy=false}
                        .onFailure{busy=false;onError(t("تعذر إرسال رمز التحقق. تحقق من كلمة المرور والبريد الموثّق."))}
                }else{
                    runCatching{if(enabled)api.confirmTwoFactorDisable(challenge!!,code) else api.confirmTwoFactorEnable(challenge!!,code)}
                        .onSuccess{busy=false;onChanged(!enabled)}
                        .onFailure{busy=false;onError(t("رمز التحقق غير صحيح أو انتهت صلاحيته."))}
                }
            }
        }){Text(if(challenge==null)t("إرسال الرمز") else t("تأكيد"))}},
        dismissButton={TextButton(enabled=!busy,onClick=onDismiss){Text(t("إلغاء"))}},
    )
}

"""
patch('android/app/src/main/java/com/zoolchat/app/ui/screens/AccountScreen.kt',marker2,two_factor_dialog+marker2)

# Share profile link from settings profile card.
old_qr="                Icon(Icons.Rounded.QrCode2, contentDescription = \"رمز QR\", modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)"
new_qr="""                if(!accountUsername.isNullOrBlank()) IconButton(onClick={
                    val link="zoolchat://profile/${Uri.encode(accountUsername)}"
                    val send=Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,link)
                    context.startActivity(Intent.createChooser(send,t("مشاركة رابط الملف الشخصي")))
                }) { Icon(Icons.Rounded.Share, contentDescription=t("مشاركة رابط الملف الشخصي"), modifier=Modifier.size(22.dp), tint=MaterialTheme.colorScheme.primary) }"""
patch('android/app/src/main/java/com/zoolchat/app/ui/screens/ProfileScreen.kt',old_qr,new_qr)

# Deep-link handoff from Android to the authenticated profile resolver.
patch('android/app/src/main/java/com/zoolchat/app/MainActivity.kt',
"    private var openChatId by mutableStateOf<String?>(null)",
"    private var openChatId by mutableStateOf<String?>(null)\n    private var openProfileUsername by mutableStateOf<String?>(null)")
patch('android/app/src/main/java/com/zoolchat/app/MainActivity.kt',
"                    ZoolChatApp(themeMode, { newMode -> themeMode = newMode; prefs.edit().putString(\"theme_mode\", newMode.name).apply() }, incomingCall, { incomingCall = null }, openChatId) { openChatId = null }",
"                    ZoolChatApp(themeMode, { newMode -> themeMode = newMode; prefs.edit().putString(\"theme_mode\", newMode.name).apply() }, incomingCall, { incomingCall = null }, openChatId, { openChatId = null }, openProfileUsername) { openProfileUsername = null }")
patch('android/app/src/main/java/com/zoolchat/app/MainActivity.kt',
"        val chat = intent?.getStringExtra(\"open_chat_id\"); if (!chat.isNullOrBlank()) openChatId = chat",
"        val chat = intent?.getStringExtra(\"open_chat_id\"); if (!chat.isNullOrBlank()) openChatId = chat\n        val data=intent?.data\n        if(intent?.action==Intent.ACTION_VIEW && data?.scheme==\"zoolchat\" && data.host==\"profile\") {\n            data.pathSegments.firstOrNull()?.trim()?.removePrefix(\"@\")?.takeIf{Regex(\"^[A-Za-z0-9_.]{3,32}$\").matches(it)}?.let{openProfileUsername=it}\n        }")

patch('android/app/src/main/AndroidManifest.xml',
"            <intent-filter>\n                <action android:name=\"android.intent.action.MAIN\" />\n                <category android:name=\"android.intent.category.LAUNCHER\" />\n            </intent-filter>",
"            <intent-filter>\n                <action android:name=\"android.intent.action.MAIN\" />\n                <category android:name=\"android.intent.category.LAUNCHER\" />\n            </intent-filter>\n            <intent-filter>\n                <action android:name=\"android.intent.action.VIEW\" />\n                <category android:name=\"android.intent.category.DEFAULT\" />\n                <category android:name=\"android.intent.category.BROWSABLE\" />\n                <data android:scheme=\"zoolchat\" android:host=\"profile\" />\n            </intent-filter>")

patch('android/app/src/main/java/com/zoolchat/app/ZoolChatApp.kt',
"    openChatId: String? = null,\n    onOpenChatConsumed: () -> Unit = {},",
"    openChatId: String? = null,\n    onOpenChatConsumed: () -> Unit = {},\n    openProfileUsername: String? = null,\n    onOpenProfileConsumed: () -> Unit = {},")
patch('android/app/src/main/java/com/zoolchat/app/ZoolChatApp.kt',
"            openChatId = openChatId,\n            onOpenChatConsumed = onOpenChatConsumed,\n            onSignedOut = {",
"            openChatId = openChatId,\n            onOpenChatConsumed = onOpenChatConsumed,\n            openProfileUsername = openProfileUsername,\n            onOpenProfileConsumed = onOpenProfileConsumed,\n            onSignedOut = {")
patch('android/app/src/main/java/com/zoolchat/app/ZoolChatApp.kt',
"    openChatId:String?,\n    onOpenChatConsumed:()->Unit,\n    onSignedOut:()->Unit,",
"    openChatId:String?,\n    onOpenChatConsumed:()->Unit,\n    openProfileUsername:String?,\n    onOpenProfileConsumed:()->Unit,\n    onSignedOut:()->Unit,")
patch('android/app/src/main/java/com/zoolchat/app/ZoolChatApp.kt',
"    LaunchedEffect(openChatId){if(!openChatId.isNullOrBlank()){nav.navigate(if(openChatId==\"__meet__\") \"meet\" else \"prodchat/$openChatId\");onOpenChatConsumed()}}",
"    LaunchedEffect(openChatId){if(!openChatId.isNullOrBlank()){nav.navigate(if(openChatId==\"__meet__\") \"meet\" else \"prodchat/$openChatId\");onOpenChatConsumed()}}\n    LaunchedEffect(openProfileUsername){\n        if(!openProfileUsername.isNullOrBlank()){\n            val resolved=runCatching{vm.api.resolveUsernameProfile(openProfileUsername)}.getOrNull()\n            if(resolved!=null){val id=resolved.optString(\"userId\");if(id.isNotBlank())nav.navigate(if(id==me?.id)\"profile\" else \"user/$id\")}\n            onOpenProfileConsumed()\n        }\n    }")

print('stage2 source integration patched')
