package com.zoolchat.app.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.zoolchat.app.auth.SessionStore
import com.zoolchat.app.network.ApiClient
import com.zoolchat.app.network.ApiException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * ZoolChat direct-chat E2EE beta.
 *
 * P-256 ECDH + HKDF-SHA256 + AES-256-GCM. The server stores only the public identity key and,
 * when enabled, a password-derived encrypted backup of the private identity key. It never receives
 * the plaintext private key or the account password. This remains a one-device identity design and
 * is not a Signal Double Ratchet / forward-secrecy implementation.
 */
class E2eeManager(
    private val context: Context,
    private val api: ApiClient,
    private val session: SessionStore,
) {
    data class PeerKey(val userId:String,val publicKeyB64:String,val fingerprint:String)
    data class Envelope(val body:String,val encrypted:Boolean=true,val cryptoVersion:Int=1)

    private val prefs=context.getSharedPreferences("zoolchat_e2ee",Context.MODE_PRIVATE)
    private val mutex=Mutex()
    private val rng=SecureRandom()
    private val backupIterations=210_000

    private fun accountTag():String {
        val raw=(session.currentUserId ?: session.accountUsername).ifBlank{"anonymous"}
        return sha256(raw.toByteArray()).take(12).joinToString(""){"%02x".format(it)}
    }
    private fun pubKeyPref()="identity_public_${accountTag()}"
    private fun privKeyPref()="identity_private_${accountTag()}"
    private fun recoveryBlockedPref()="identity_recovery_blocked_${accountTag()}"
    private fun vaultAlias()="zoolchat_e2ee_vault_${accountTag()}"

    private fun getOrCreateVaultKey():SecretKey {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        val alias=vaultAlias()
        (ks.getKey(alias,null) as? SecretKey)?.let{return it}
        val kg=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
        kg.init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return kg.generateKey()
    }

    private fun encryptPrivate(bytes:ByteArray):String {
        val c=Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE,getOrCreateVaultKey())
        val iv=c.iv
        require(iv.size==12){"bad_generated_gcm_iv"}
        return b64(iv+c.doFinal(bytes))
    }
    private fun decryptPrivate(encoded:String):ByteArray {
        val all=b64d(encoded);require(all.size>28){"bad_identity_key"};val iv=all.copyOfRange(0,12);val ct=all.copyOfRange(12,all.size)
        val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,getOrCreateVaultKey(),GCMParameterSpec(128,iv));return c.doFinal(ct)
    }

    private fun persistIdentity(kp:KeyPair) {
        prefs.edit()
            .putString(pubKeyPref(),b64(kp.public.encoded))
            .putString(privKeyPref(),encryptPrivate(kp.private.encoded))
            .putBoolean(recoveryBlockedPref(),false)
            .apply()
    }

    private fun loadIdentityOrNull():KeyPair? {
        val pub=prefs.getString(pubKeyPref(),null)?:return null
        val encPriv=prefs.getString(privKeyPref(),null)?:return null
        return runCatching {
            val kf=KeyFactory.getInstance("EC")
            KeyPair(kf.generatePublic(X509EncodedKeySpec(b64d(pub))),kf.generatePrivate(PKCS8EncodedKeySpec(decryptPrivate(encPriv))))
        }.getOrNull()
    }

    private fun generateIdentity():KeyPair {
        check(!prefs.getBoolean(recoveryBlockedPref(),false)){"e2ee_identity_recovery_required"}
        val gen=KeyPairGenerator.getInstance("EC");gen.initialize(ECGenParameterSpec("secp256r1"),rng)
        return gen.generateKeyPair().also(::persistIdentity)
    }

    private fun identity():KeyPair=loadIdentityOrNull()?:generateIdentity()

    fun hasLocalIdentity():Boolean=loadIdentityOrNull()!=null
    fun localPublicKeyB64():String=b64(identity().public.encoded)
    fun localFingerprint():String=fingerprint(identity().public.encoded)

    suspend fun registerLocalKey(){
        val kp=loadIdentityOrNull() ?: if(prefs.getBoolean(recoveryBlockedPref(),false)) error("e2ee_identity_recovery_required") else generateIdentity()
        api.putE2eeKey(b64(kp.public.encoded),fingerprint(kp.public.encoded))
    }

    private fun backupAad(publicKeyB64:String,fingerprint:String):ByteArray {
        val uid=session.currentUserId ?: error("missing_current_user")
        return "ZoolChat-E2EE-Backup-v1|$uid|$publicKeyB64|$fingerprint".toByteArray(StandardCharsets.UTF_8)
    }

    private fun deriveBackupKey(password:String,salt:ByteArray):SecretKeySpec {
        require(password.length in 8..128){"invalid_backup_password"}
        val spec=PBEKeySpec(password.toCharArray(),salt,backupIterations,256)
        return try {
            val raw=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            SecretKeySpec(raw,"AES")
        } finally { spec.clearPassword() }
    }

    private fun encryptedBackupBody(kp:KeyPair,password:String):JSONObject {
        val publicKey=b64(kp.public.encoded)
        val fp=fingerprint(kp.public.encoded)
        val salt=ByteArray(16).also(rng::nextBytes)
        val iv=ByteArray(12).also(rng::nextBytes)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,deriveBackupKey(password,salt),GCMParameterSpec(128,iv))
        cipher.updateAAD(backupAad(publicKey,fp))
        val ciphertext=cipher.doFinal(kp.private.encoded)
        return JSONObject()
            .put("version",1)
            .put("publicKeyB64",publicKey)
            .put("fingerprintSha256",fp)
            .put("saltB64",b64(salt))
            .put("ivB64",b64(iv))
            .put("ciphertextB64",b64(ciphertext))
    }

    private fun restoreBackup(o:JSONObject,password:String):KeyPair {
        require(o.optInt("version")==1){"unsupported_e2ee_backup"}
        val publicKey=o.getString("publicKeyB64")
        val fp=o.getString("fingerprintSha256")
        val salt=b64d(o.getString("saltB64"))
        val iv=b64d(o.getString("ivB64"))
        val ciphertext=b64d(o.getString("ciphertextB64"))
        require(salt.size>=16&&iv.size==12&&ciphertext.size>32){"bad_e2ee_backup"}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,deriveBackupKey(password,salt),GCMParameterSpec(128,iv))
        cipher.updateAAD(backupAad(publicKey,fp))
        val privateBytes=cipher.doFinal(ciphertext)
        val kf=KeyFactory.getInstance("EC")
        val kp=KeyPair(kf.generatePublic(X509EncodedKeySpec(b64d(publicKey))),kf.generatePrivate(PKCS8EncodedKeySpec(privateBytes)))
        require(fingerprint(kp.public.encoded).equals(fp,true)){"e2ee_backup_fingerprint_mismatch"}
        val probe=ByteArray(32).also(rng::nextBytes)
        val signer=Signature.getInstance("SHA256withECDSA").apply{initSign(kp.private);update(probe)}.sign()
        val valid=Signature.getInstance("SHA256withECDSA").run{initVerify(kp.public);update(probe);verify(signer)}
        require(valid){"e2ee_backup_key_mismatch"}
        return kp
    }

    private suspend fun remoteBackupOrNull():JSONObject? = try {
        api.get("/api/e2ee/backup").getJSONObject("backup")
    } catch(e:ApiException) {
        if(e.status==404)null else throw e
    }

    private suspend fun serverIdentityExists():Boolean = try {
        api.get("/api/e2ee/self-key");true
    } catch(e:ApiException) {
        if(e.status==404)false else throw e
    }

    suspend fun backupRequired():Boolean = loadIdentityOrNull()!=null && remoteBackupOrNull()==null

    suspend fun backupCurrentIdentityWithVerifiedPassword(password:String) {
        api.post("/api/me/password/verify",JSONObject().put("password",password))
        val kp=loadIdentityOrNull() ?: error("local_e2ee_identity_missing")
        api.put("/api/e2ee/backup",encryptedBackupBody(kp,password))
    }

    /**
     * Called immediately after password authentication and before bootstrap publishes any key.
     * Returns false only when an older server identity exists but no decryptable backup is
     * available; in that case the client deliberately refuses to silently rotate the key.
     */
    suspend fun prepareIdentityAfterPasswordAuth(password:String):Boolean {
        loadIdentityOrNull()?.let { local ->
            return runCatching { api.put("/api/e2ee/backup",encryptedBackupBody(local,password));true }.getOrDefault(false)
        }
        val backup=remoteBackupOrNull()
        if(backup!=null){
            return runCatching {
                val restored=restoreBackup(backup,password)
                persistIdentity(restored)
                true
            }.getOrElse {
                prefs.edit().putBoolean(recoveryBlockedPref(),true).apply()
                false
            }
        }
        if(serverIdentityExists()){
            prefs.edit().putBoolean(recoveryBlockedPref(),true).apply()
            return false
        }
        val fresh=generateIdentity()
        return runCatching { api.put("/api/e2ee/backup",encryptedBackupBody(fresh,password));true }.getOrDefault(false)
    }

    private suspend fun peerKey(userId:String,force:Boolean=false):PeerKey=mutex.withLock {
        val scope=accountTag(); val key="peer_key_${scope}_$userId"; val fp="peer_fp_${scope}_$userId"
        if(!force){ val cached=prefs.getString(key,null);val cachedFp=prefs.getString(fp,null);if(cached!=null&&cachedFp!=null)return@withLock PeerKey(userId,cached,cachedFp) }
        val o=api.getE2eeKey(userId);val p=o.getString("publicKeyB64");val f=o.getString("fingerprintSha256")
        val old=prefs.getString(fp,null)
        if(old!=null&&old!=f) prefs.edit().putBoolean("peer_changed_${accountTag()}_$userId",true).apply()
        prefs.edit().putString(key,p).putString(fp,f).apply();PeerKey(userId,p,f)
    }

    fun peerKeyChanged(userId:String):Boolean=prefs.getBoolean("peer_changed_${accountTag()}_$userId",false)
    fun acknowledgePeerKey(userId:String){prefs.edit().putBoolean("peer_changed_${accountTag()}_$userId",false).apply()}
    suspend fun peerFingerprint(userId:String):String=peerKey(userId).fingerprint

    private suspend fun sharedKey(peerUserId:String,contextInfo:String):ByteArray {
        val me=session.currentUserId ?: error("missing_current_user")
        val peer=peerKey(peerUserId)
        val kf=KeyFactory.getInstance("EC");val peerPublic=kf.generatePublic(X509EncodedKeySpec(b64d(peer.publicKeyB64)))
        val ka=KeyAgreement.getInstance("ECDH");ka.init(identity().private);ka.doPhase(peerPublic,true);val secret=ka.generateSecret()
        val sorted=listOf(me,peerUserId).sorted().joinToString("|")
        val salt=sha256("ZoolChat-E2EE-v1|$sorted".toByteArray(StandardCharsets.UTF_8))
        return hkdf(secret,salt,contextInfo.toByteArray(StandardCharsets.UTF_8),32)
    }

    suspend fun encryptMessage(chatId:String,peerUserId:String,senderId:String,clientId:String,type:String,plain:String):Envelope {
        val key=sharedKey(peerUserId,"chat:$chatId")
        val iv=ByteArray(12).also(rng::nextBytes)
        val aad="$chatId|$senderId|$clientId|$type".toByteArray(StandardCharsets.UTF_8)
        val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,iv));c.updateAAD(aad)
        return Envelope("zce1:"+b64(iv+c.doFinal(plain.toByteArray(StandardCharsets.UTF_8))))
    }

    suspend fun decryptMessage(chatId:String,peerUserId:String,senderId:String,clientId:String,type:String,envelope:String):String {
        require(envelope.startsWith("zce1:")){"unsupported_ciphertext"};val all=b64d(envelope.removePrefix("zce1:"));require(all.size>28){"bad_ciphertext"}
        val iv=all.copyOfRange(0,12);val ct=all.copyOfRange(12,all.size)
        val aad="$chatId|$senderId|$clientId|$type".toByteArray(StandardCharsets.UTF_8)
        suspend fun decrypt(): String {
            val key=sharedKey(peerUserId,"chat:$chatId")
            val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,iv));c.updateAAD(aad)
            return c.doFinal(ct).toString(StandardCharsets.UTF_8)
        }
        return try { decrypt() } catch (failure: javax.crypto.AEADBadTagException) {
            peerKey(peerUserId, force=true)
            decrypt()
        }
    }

    suspend fun callKey(callId:String,peerUserId:String):String { peerKey(peerUserId,force=true);return b64(sharedKey(peerUserId,"call:$callId")) }

    suspend fun encryptMediaFile(chatId:String,peerUserId:String,clientId:String,type:String,input:File,output:File) {
        val key=sharedKey(peerUserId,"media:$chatId")
        val iv=ByteArray(12).also(rng::nextBytes)
        val aad="$chatId|$clientId|$type|media-v1".toByteArray(StandardCharsets.UTF_8)
        val c=Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,iv));c.updateAAD(aad)
        FileOutputStream(output).use { raw ->
            raw.write(byteArrayOf('Z'.code.toByte(),'C'.code.toByte(),'F'.code.toByte(),1))
            raw.write(iv)
            CipherOutputStream(raw,c).use { encrypted -> FileInputStream(input).use { it.copyTo(encrypted) } }
        }
    }

    suspend fun decryptMediaFile(chatId:String,peerUserId:String,clientId:String,type:String,input:File,output:File) {
        output.parentFile?.mkdirs()
        val temp=File(output.parentFile ?: output.absoluteFile.parentFile,".${output.name}.${System.nanoTime()}.tmp")
        try {
            FileInputStream(input).use { raw ->
                val magic=ByteArray(4);require(raw.read(magic)==4 && magic[0]=='Z'.code.toByte() && magic[1]=='C'.code.toByte() && magic[2]=='F'.code.toByte() && magic[3].toInt()==1){"unsupported_media_ciphertext"}
                val iv=ByteArray(12);require(raw.read(iv)==12){"bad_media_ciphertext"}
                val key=sharedKey(peerUserId,"media:$chatId")
                val aad="$chatId|$clientId|$type|media-v1".toByteArray(StandardCharsets.UTF_8)
                val c=Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,iv));c.updateAAD(aad)
                FileOutputStream(temp).use { out -> CipherInputStream(raw,c).use { it.copyTo(out) }; out.fd.sync() }
            }
            if(output.exists()) output.delete()
            check(temp.renameTo(output)){"media_cache_commit_failed"}
        } catch(t:Throwable) { temp.delete(); throw t }
    }

    fun clearLocalAccountKeys() {
        val scope=accountTag()
        val editor=prefs.edit().remove(pubKeyPref()).remove(privKeyPref()).remove(recoveryBlockedPref())
        prefs.all.keys.filter { it.startsWith("peer_key_${scope}_") || it.startsWith("peer_fp_${scope}_") || it.startsWith("peer_changed_${scope}_") }.forEach(editor::remove)
        editor.apply()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(vaultAlias()) }
    }

    private fun hkdf(ikm:ByteArray,salt:ByteArray,info:ByteArray,length:Int):ByteArray {
        val mac=Mac.getInstance("HmacSHA256");mac.init(SecretKeySpec(salt,"HmacSHA256"));val prk=mac.doFinal(ikm)
        val out=ByteArray(length);var previous=ByteArray(0);var offset=0;var counter=1
        while(offset<length){mac.init(SecretKeySpec(prk,"HmacSHA256"));mac.update(previous);mac.update(info);mac.update(counter.toByte());previous=mac.doFinal();val n=minOf(previous.size,length-offset);System.arraycopy(previous,0,out,offset,n);offset+=n;counter++}
        return out
    }
    private fun sha256(data:ByteArray)=MessageDigest.getInstance("SHA-256").digest(data)
    private fun fingerprint(data:ByteArray)=sha256(data).joinToString(":"){"%02X".format(it)}
    private fun b64(data:ByteArray)=Base64.encodeToString(data,Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    private fun b64d(text:String)=Base64.decode(text,Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
}
