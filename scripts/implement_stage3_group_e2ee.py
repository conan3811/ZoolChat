from pathlib import Path


def replace_once(path, old, new):
    p=Path(path); s=p.read_text()
    if old not in s:
        raise SystemExit(f'pattern not found in {path}: {old[:120]!r}')
    p.write_text(s.replace(old,new,1))

# Membership-scoped group key discovery. No schema/config changes are required: it reuses
# existing chat_members + user_crypto_keys and never returns private key material.
server='server/src/index.ts'
insert="""app.get('/api/groups/:id/e2ee-keys', requireAuth, async (req,res)=>{
  const chatId=String(req.params.id),uid=req.zoolUser!.id;
  if(!(await membership(chatId,uid)))return res.status(403).json({error:'not_member'});
  const r=await pool.query(`SELECT cm.user_id,k.algorithm,k.public_key_b64,k.fingerprint_sha256,k.updated_at
    FROM chat_members cm LEFT JOIN user_crypto_keys k ON k.user_id=cm.user_id
    WHERE cm.chat_id=$1 ORDER BY cm.joined_at,cm.user_id`,[chatId]);
  const keys=r.rows.filter(x=>x.public_key_b64).map(x=>({
    userId:String(x.user_id),algorithm:String(x.algorithm||'P256-ECDH'),publicKeyB64:String(x.public_key_b64),
    fingerprintSha256:String(x.fingerprint_sha256),updatedAt:x.updated_at?new Date(x.updated_at).toISOString():null,
  }));
  const missingUserIds=r.rows.filter(x=>!x.public_key_b64).map(x=>String(x.user_id));
  res.json({keys,missingUserIds});
});
"""
replace_once(server,"app.get('/api/groups/:id', requireAuth, async (req,res)=>{",insert+"app.get('/api/groups/:id', requireAuth, async (req,res)=>{")

# Group crypto is an independent v2 envelope. Direct zce1/call crypto remains untouched.
e2ee='android/app/src/main/java/com/zoolchat/app/crypto/E2eeManager.kt'
group_code=r'''    private fun groupMediaKeyPref(chatId:String,clientId:String):String {
        val digest=sha256("$chatId|$clientId".toByteArray(StandardCharsets.UTF_8)).take(16).joinToString(""){"%02x".format(it)}
        return "group_media_${accountTag()}_$digest"
    }

    private fun cachePeerKeyFromGroup(userId:String,publicKeyB64:String,fingerprintSha256:String) {
        if(userId==session.currentUserId)return
        val scope=accountTag();val fpKey="peer_fp_${scope}_$userId";val old=prefs.getString(fpKey,null)
        if(old!=null&&old!=fingerprintSha256)prefs.edit().putBoolean("peer_changed_${scope}_$userId",true).apply()
        prefs.edit().putString("peer_key_${scope}_$userId",publicKeyB64).putString(fpKey,fingerprintSha256).apply()
    }

    private fun groupPairwiseKey(peerUserId:String,peerPublicKeyB64:String,info:String):ByteArray {
        val me=session.currentUserId ?: error("missing_current_user")
        val publicKey=KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(b64d(peerPublicKeyB64)))
        return GroupE2eeProtocol.derivePairwiseKey(identity().private,publicKey,me,peerUserId,info)
    }

    suspend fun encryptGroupMessage(chatId:String,senderId:String,clientId:String,type:String,plain:String):Envelope {
        val me=session.currentUserId ?: error("missing_current_user")
        require(senderId==me){"group_sender_identity_mismatch"}
        val response=api.groupE2eeKeys(chatId)
        val missing=response.optJSONArray("missingUserIds") ?: org.json.JSONArray()
        check(missing.length()==0){"group_member_e2ee_key_missing"}
        val keys=response.optJSONArray("keys") ?: org.json.JSONArray()
        check(keys.length()>0){"group_member_e2ee_key_missing"}
        val contentKey=GroupE2eeProtocol.randomContentKey(rng)
        val wraps=JSONObject()
        var selfFound=false
        for(i in 0 until keys.length()){
            val row=keys.getJSONObject(i);val userId=row.getString("userId");val publicKey=row.getString("publicKeyB64");val fp=row.getString("fingerprintSha256")
            if(userId==me)selfFound=true else cachePeerKeyFromGroup(userId,publicKey,fp)
            val info="group-wrap-v2|$chatId|$senderId|$userId|$clientId|$type"
            val pairKey=groupPairwiseKey(userId,publicKey,info)
            wraps.put(userId,GroupE2eeProtocol.encrypt(pairKey,contentKey,info,rng))
        }
        check(selfFound){"group_sender_key_missing"}
        val payloadAad="group-payload-v2|$chatId|$senderId|$clientId|$type"
        val payload=GroupE2eeProtocol.encrypt(contentKey,plain.toByteArray(StandardCharsets.UTF_8),payloadAad,rng)
        val outer=JSONObject().put("v",2).put("payload",payload).put("wraps",wraps)
        return Envelope("zcg2:"+b64(outer.toString().toByteArray(StandardCharsets.UTF_8)),true,2)
    }

    suspend fun decryptGroupMessage(chatId:String,senderId:String,clientId:String,type:String,envelope:String):String {
        require(envelope.startsWith("zcg2:")){"unsupported_group_ciphertext"}
        val me=session.currentUserId ?: error("missing_current_user")
        val outer=JSONObject(b64d(envelope.removePrefix("zcg2:")).toString(StandardCharsets.UTF_8))
        require(outer.optInt("v")==2){"unsupported_group_ciphertext"}
        val wrapped=outer.getJSONObject("wraps").optString(me)
        check(wrapped.isNotBlank()){"group_message_not_for_member"}
        val payload=outer.getString("payload")
        val wrapInfo="group-wrap-v2|$chatId|$senderId|$me|$clientId|$type"
        val payloadAad="group-payload-v2|$chatId|$senderId|$clientId|$type"
        suspend fun attempt(force:Boolean):String {
            val peerPublic=if(senderId==me)b64(identity().public.encoded) else peerKey(senderId,force).publicKeyB64
            val pairKey=groupPairwiseKey(senderId,peerPublic,wrapInfo)
            val contentKey=GroupE2eeProtocol.decrypt(pairKey,wrapped,wrapInfo)
            return GroupE2eeProtocol.decrypt(contentKey,payload,payloadAad).toString(StandardCharsets.UTF_8)
        }
        return try{attempt(false)}catch(first:javax.crypto.AEADBadTagException){if(senderId==me)throw first;attempt(true)}
    }

    fun newGroupMediaKey():String=GroupE2eeProtocol.encode(GroupE2eeProtocol.randomContentKey(rng))

    fun cacheGroupMediaKey(chatId:String,clientId:String,keyB64:String) {
        val key=GroupE2eeProtocol.decode(keyB64);require(key.size==32){"bad_group_media_key"}
        prefs.edit().putString(groupMediaKeyPref(chatId,clientId),encryptPrivate(key)).apply()
    }

    private fun groupMediaKey(chatId:String,clientId:String):ByteArray {
        val encoded=prefs.getString(groupMediaKeyPref(chatId,clientId),null) ?: error("group_media_key_missing")
        return decryptPrivate(encoded).also{require(it.size==32){"bad_group_media_key"}}
    }

    fun encryptGroupMediaFile(chatId:String,clientId:String,type:String,input:File,output:File,keyB64:String) {
        cacheGroupMediaKey(chatId,clientId,keyB64);val key=groupMediaKey(chatId,clientId)
        val iv=ByteArray(12).also(rng::nextBytes);val aad="$chatId|$clientId|$type|group-media-v2".toByteArray(StandardCharsets.UTF_8)
        val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,iv));c.updateAAD(aad)
        FileOutputStream(output).use{raw->raw.write(byteArrayOf('Z'.code.toByte(),'C'.code.toByte(),'F'.code.toByte(),2));raw.write(iv);CipherOutputStream(raw,c).use{enc->FileInputStream(input).use{it.copyTo(enc)}}}
    }

    fun decryptGroupMediaFile(chatId:String,clientId:String,type:String,input:File,output:File) {
        output.parentFile?.mkdirs();val temp=File(output.parentFile ?: output.absoluteFile.parentFile,".${output.name}.${System.nanoTime()}.tmp")
        try{
            FileInputStream(input).use{raw->
                val magic=ByteArray(4);require(raw.read(magic)==4&&magic[0]=='Z'.code.toByte()&&magic[1]=='C'.code.toByte()&&magic[2]=='F'.code.toByte()&&magic[3].toInt()==2){"unsupported_group_media_ciphertext"}
                val iv=ByteArray(12);require(raw.read(iv)==12){"bad_group_media_ciphertext"};val key=groupMediaKey(chatId,clientId)
                val aad="$chatId|$clientId|$type|group-media-v2".toByteArray(StandardCharsets.UTF_8);val c=Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,iv));c.updateAAD(aad)
                FileOutputStream(temp).use{out->CipherInputStream(raw,c).use{it.copyTo(out)};out.fd.sync()}
            }
            if(output.exists())output.delete();check(temp.renameTo(output)){"media_cache_commit_failed"}
        }catch(t:Throwable){temp.delete();throw t}
    }

'''
replace_once(e2ee,'    fun clearLocalAccountKeys() {',group_code+'    fun clearLocalAccountKeys() {')
replace_once(e2ee,
'''        prefs.all.keys.filter { it.startsWith("peer_key_${scope}_") || it.startsWith("peer_fp_${scope}_") || it.startsWith("peer_changed_${scope}_") }.forEach(editor::remove)''',
'''        prefs.all.keys.filter { it.startsWith("peer_key_${scope}_") || it.startsWith("peer_fp_${scope}_") || it.startsWith("peer_changed_${scope}_") || it.startsWith("group_media_${scope}_") }.forEach(editor::remove)''')

sync='android/app/src/main/java/com/zoolchat/app/sync/MessageSyncRepository.kt'
old_decode='''    private suspend fun decode(message: RemoteMessage): RemoteMessage {
        if ((!message.encrypted && !message.body.startsWith("zce1:")) || message.deleted) return message
        val chat = db.chats().byId(message.chatId) ?: return message.copy(body = "🔒 رسالة مشفرة", decryptionFailed = true)
        val peer = chat.peerUserId ?: return message.copy(body = "🔒 تعذر فك تشفير هذه الرسالة", decryptionFailed = true)
        val clientId = message.clientId ?: return message.copy(body = "🔒 تعذر فك التشفير", decryptionFailed = true)
        return runCatching {
            val plain = crypto.decryptMessage(message.chatId, peer, message.senderId, clientId, message.type, message.body)
            if (message.type == "text") message.copy(body = plain, decryptionFailed = false)
            else {
                val meta = JSONObject(plain)
                val name = meta.optString("name").ifBlank { "مرفق" }
                message.copy(
                    body = name,
                    mediaName = name,
                    mediaMime = meta.optString("mime").takeIf { it.isNotBlank() } ?: message.mediaMime,
                    mediaSize = meta.optLong("size").takeIf { it > 0 } ?: message.mediaSize,
                    decryptionFailed = false,
                )
            }
        }.getOrElse { message.copy(body = "🔒 تعذر فك تشفير هذه الرسالة", decryptionFailed = true) }
    }'''
new_decode='''    private suspend fun decode(message: RemoteMessage): RemoteMessage {
        val directCipher=message.body.startsWith("zce1:")
        val groupCipher=message.body.startsWith("zcg2:") || message.cryptoVersion==2
        if ((!message.encrypted && !directCipher && !groupCipher) || message.deleted) return message
        val chat = db.chats().byId(message.chatId) ?: return message.copy(body = "🔒 رسالة مشفرة", decryptionFailed = true)
        val clientId = message.clientId ?: return message.copy(body = "🔒 تعذر فك التشفير", decryptionFailed = true)
        return runCatching {
            val plain = if(groupCipher) crypto.decryptGroupMessage(message.chatId,message.senderId,clientId,message.type,message.body)
                else crypto.decryptMessage(message.chatId,chat.peerUserId ?: error("peer_identity_missing"),message.senderId,clientId,message.type,message.body)
            if (message.type == "text") message.copy(body = plain, decryptionFailed = false)
            else {
                val meta = JSONObject(plain)
                if(groupCipher)meta.optString("mediaKeyB64").takeIf{it.isNotBlank()}?.let{crypto.cacheGroupMediaKey(message.chatId,clientId,it)}
                val name = meta.optString("name").ifBlank { "مرفق" }
                message.copy(
                    body = name,
                    mediaName = name,
                    mediaMime = meta.optString("mime").takeIf { it.isNotBlank() } ?: message.mediaMime,
                    mediaSize = meta.optLong("size").takeIf { it > 0 } ?: message.mediaSize,
                    decryptionFailed = false,
                )
            }
        }.getOrElse { failure ->
            val text=if(groupCipher && failure.message=="group_message_not_for_member") "🔒 هذه الرسالة سبقت انضمامك أو تفعيل التشفير لديك" else "🔒 تعذر فك تشفير هذه الرسالة"
            message.copy(body=text,decryptionFailed=true)
        }
    }'''
replace_once(sync,old_decode,new_decode)
replace_once(sync,
'''        message.type == "text" -> if (message.decryptionFailed || message.body.startsWith("zce1:")) "رسالة" else message.body''',
'''        message.type == "text" -> if (message.decryptionFailed || message.body.startsWith("zce1:") || message.body.startsWith("zcg2:")) "رسالة" else message.body''')
replace_once(sync,
'''            val needsClientPreview = preview.isBlank() || preview == "🔒 رسالة مشفرة" || preview == "📞 مكالمة" || preview.startsWith("zce1:") || preview.contains("callId\\\"")''',
'''            val needsClientPreview = preview.isBlank() || preview == "🔒 رسالة مشفرة" || preview == "📞 مكالمة" || preview.startsWith("zce1:") || preview.startsWith("zcg2:") || preview.contains("callId\\\"")''')
replace_once(sync,
'''        } else {
            // E2EE beta currently applies to direct chats. Group edits must use the authenticated
            // group transport instead of failing because there is no single peer identity.
            parseMessage(api.editMessage(chatId, messageId, newBody, encrypted = false, cryptoVersion = 0))
        }''',
'''        } else {
            val envelope=crypto.encryptGroupMessage(chatId,local.senderId,clientId,"text",newBody)
            parseMessage(api.editMessage(chatId,messageId,envelope.body,envelope.encrypted,envelope.cryptoVersion))
        }''')
replace_once(sync,
'''                            // Group E2EE is not part of the current direct-chat E2EE beta. Keep the
                            // local pending file encrypted at rest, reveal only a short-lived temp
                            // file for the authenticated member-only upload, and do not attempt a
                            // bogus one-peer ECDH derivation for the group.
                            val media = api.uploadMedia(
                                Uri.fromFile(sourceFile),
                                item.mediaMime ?: "application/octet-stream",
                                pendingMediaName ?: pendingBody.ifBlank { "attachment" },
                            )
                            mediaId = media.getString("id")
                            transportBody = pendingBody''',
'''                            val mediaKey=crypto.newGroupMediaKey()
                            transportTemp=File.createTempFile("zool_group_media_", ".zcg", applicationContext.cacheDir)
                            crypto.encryptGroupMediaFile(item.chatId,item.clientId,item.type,sourceFile,transportTemp!!,mediaKey)
                            val media=api.uploadMedia(Uri.fromFile(transportTemp),"application/octet-stream","zool-group-media.zcg")
                            mediaId=media.getString("id")
                            transportBody=JSONObject()
                                .put("name",pendingMediaName ?: pendingBody.ifBlank { "مرفق" })
                                .put("mime",item.mediaMime ?: "application/octet-stream")
                                .put("size",sourceFile.length())
                                .put("mediaEncrypted",true)
                                .put("mediaCryptoVersion",2)
                                .put("mediaKeyB64",mediaKey)
                                .toString()''')
replace_once(sync,
'''                } else {
                    MessageSyncRepository.parseMessage(
                        api.sendMessage(item.chatId, item.clientId, item.type, transportBody, mediaId, encrypted = false, cryptoVersion = 0),
                    )
                }''',
'''                } else {
                    val envelope=crypto.encryptGroupMessage(item.chatId,item.senderId,item.clientId,item.type,transportBody)
                    MessageSyncRepository.parseMessage(
                        api.sendMessage(item.chatId,item.clientId,item.type,envelope.body,mediaId,envelope.encrypted,envelope.cryptoVersion),
                    )
                }''')
replace_once(sync,
'''                } else {
                    remoteRaw.copy(
                        body = pendingBody,
                        mediaName = pendingMediaName ?: remoteRaw.mediaName,
                        mediaMime = item.mediaMime ?: remoteRaw.mediaMime,
                        encrypted = false,
                        cryptoVersion = 0,
                        decryptionFailed = false,
                    )
                }''',
'''                } else {
                    remoteRaw.copy(
                        body = pendingBody,
                        mediaName = pendingMediaName ?: remoteRaw.mediaName,
                        mediaMime = item.mediaMime ?: remoteRaw.mediaMime,
                        encrypted = true,
                        cryptoVersion = 2,
                        decryptionFailed = false,
                    )
                }''')
replace_once(sync,
'''            jsonStringOrNull(o, "lastMessage")?.takeUnless { it.startsWith("zce1:") || it == "🔒 رسالة مشفرة" },''',
'''            jsonStringOrNull(o, "lastMessage")?.takeUnless { it.startsWith("zce1:") || it.startsWith("zcg2:") || it == "🔒 رسالة مشفرة" },''')
replace_once(sync,
'''            encrypted = o.optBoolean("encrypted", false) || o.optString("body").startsWith("zce1:"),
            cryptoVersion = o.optInt("cryptoVersion", if (o.optString("body").startsWith("zce1:")) 1 else 0),''',
'''            encrypted = o.optBoolean("encrypted", false) || o.optString("body").startsWith("zce1:") || o.optString("body").startsWith("zcg2:"),
            cryptoVersion = o.optInt("cryptoVersion", if (o.optString("body").startsWith("zcg2:")) 2 else if (o.optString("body").startsWith("zce1:")) 1 else 0),''')

media='android/app/src/main/java/com/zoolchat/app/media/MediaRepository.kt'
replace_once(media,
'''        val raw = api.downloadMedia(url, if(message.encrypted) "blob.zce" else (message.mediaName ?: "attachment"))
        if(!message.encrypted) return raw
        val peer = peerUserId ?: error("peer_identity_missing")
        val clientId = message.clientId ?: error("message_client_id_missing")
        val safe=(message.mediaName ?: "attachment").replace(Regex("[^A-Za-z0-9._-]"),"_").takeLast(90).ifBlank{"attachment"}
        val cacheDir=File(context.cacheDir,"zool_media").apply{mkdirs()}
        val out=File(cacheDir,"zool_media_dec_${message.id.hashCode().toUInt()}_$safe")
        if(out.exists()&&out.length()>0){ raw.delete(); return out }
        try {
            e2ee.decryptMediaFile(message.chatId,peer,clientId,message.type,raw,out)
            return out
        } finally { raw.delete() }''',
'''        val raw = api.downloadMedia(url, if(message.encrypted) "blob.zce" else (message.mediaName ?: "attachment"))
        if(!message.encrypted) return raw
        val clientId = message.clientId ?: error("message_client_id_missing")
        val safe=(message.mediaName ?: "attachment").replace(Regex("[^A-Za-z0-9._-]"),"_").takeLast(90).ifBlank{"attachment"}
        val cacheDir=File(context.cacheDir,"zool_media").apply{mkdirs()}
        val out=File(cacheDir,"zool_media_dec_${message.id.hashCode().toUInt()}_$safe")
        if(out.exists()&&out.length()>0){ raw.delete(); return out }
        try {
            if(message.cryptoVersion==2)e2ee.decryptGroupMediaFile(message.chatId,clientId,message.type,raw,out)
            else e2ee.decryptMediaFile(message.chatId,peerUserId ?: error("peer_identity_missing"),clientId,message.type,raw,out)
            return out
        } finally { raw.delete() }''')

# Make the group encryption status visible without changing other group UI behavior.
ui='android/app/src/main/java/com/zoolchat/app/ui/screens/ProductionChatScreen.kt'
replace_once(ui,
'''                    if (isGroup) {
                        ChatMenuItem(Icons.Rounded.Timer,t("الرسائل المختفية")) { showMore=false; onOpenGroupInfo() }''',
'''                    if (isGroup) {
                        ChatMenuItem(Icons.Rounded.VerifiedUser,t("التشفير في المجموعة")) { showMore=false; showSecurity=true }
                        ChatMenuItem(Icons.Rounded.Timer,t("الرسائل المختفية")) { showMore=false; onOpenGroupInfo() }''')
replace_once(ui,
'''    if(showSecurity && !isGroup){''',
'''    if(showSecurity && isGroup){
        AlertDialog(onDismissRequest={showSecurity=false},title={Text(t("التشفير في المجموعة"))},text={Text(t("رسائل ومرفقات المجموعة الجديدة تستخدم Group E2EE v2. يتم تشفير محتوى كل رسالة بمفتاح عشوائي ثم يُشفّر المفتاح لكل عضو حالي باستخدام مفتاح هويته. إذا كان أحد الأعضاء بلا مفتاح تشفير فلن يتم إرسال الرسالة كنص مكشوف."))},confirmButton={TextButton(onClick={showSecurity=false}){Text(t("تم"))}})
    }
    if(showSecurity && !isGroup){''')

print('stage3 group E2EE integration patched')
