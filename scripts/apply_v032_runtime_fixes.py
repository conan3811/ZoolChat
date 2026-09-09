from pathlib import Path

p=Path('android/app/src/main/java/com/zoolchat/app/sync/MessageSyncRepository.kt')
s=p.read_text()
old='''        items.forEach { if (!it.id.startsWith("local:")) runCatching { api.markDelivered(chatId, it.id) } }
        return PageLoadResult(page.hasMore, page.nextCursor)
'''
new='''        items.forEach { if (!it.id.startsWith("local:")) runCatching { api.markDelivered(chatId, it.id) } }
        val myUserId = SessionStore(context).currentUserId
        items.lastOrNull { it.senderId != myUserId && !it.id.startsWith("local:") }?.let { latestIncoming ->
            runCatching { api.markRead(chatId, latestIncoming.id) }.onSuccess { clearUnread(chatId) }
        }
        return PageLoadResult(page.hasMore, page.nextCursor)
'''
assert old in s, 'refreshMessages anchor changed'
p.write_text(s.replace(old,new,1))

p=Path('android/app/src/main/java/com/zoolchat/app/MainActivity.kt')
s=p.read_text()
old='''import com.zoolchat.app.notifications.NotificationHelper
import com.zoolchat.app.ui.theme.ThemeMode
'''
new='''import com.zoolchat.app.notifications.NotificationHelper
import com.zoolchat.app.state.ZoolAppViewModel
import com.zoolchat.app.ui.theme.ThemeMode
'''
assert old in s, 'MainActivity import anchor changed'
s=s.replace(old,new,1)
old='''        if (requestCode == REQUEST_CONTACTS && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && !isFinishing) {
            // Authenticated bootstrap calls discoverContacts(); recreate once so it runs immediately
            // with READ_CONTACTS already granted and populates matching ZoolChat users.
            recreate()
        }
'''
new='''        if (requestCode == REQUEST_CONTACTS && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && !isFinishing) {
            androidx.lifecycle.ViewModelProvider(this)[ZoolAppViewModel::class.java].discoverContacts()
        }
'''
assert old in s, 'MainActivity permission callback changed'
p.write_text(s.replace(old,new,1))

p=Path('android/app/src/main/java/com/zoolchat/app/ui/screens/ProductionCallScreen.kt')
s=p.read_text()
start=s.index('    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions())')
end=s.index('\n\n    LaunchedEffect(Unit) {', start)
new='''    fun permissionGranted(permission: String): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    lateinit var cameraPermission: androidx.activity.result.ActivityResultLauncher<String>
    cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scope.launch { connectAfterPermission() }
        else {
            connectionStarted = false
            error = "يلزم السماح بالكاميرا لإجراء مكالمة فيديو"
        }
    }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            connectionStarted = false
            error = "يلزم السماح بالميكروفون لإجراء المكالمة"
        } else if (video && !permissionGranted(Manifest.permission.CAMERA)) {
            cameraPermission.launch(Manifest.permission.CAMERA)
        } else scope.launch { connectAfterPermission() }
    }

    fun requestAndConnect() {
        error = null
        if (incoming) stopIncomingAlert()
        when {
            !permissionGranted(Manifest.permission.RECORD_AUDIO) -> microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            video && !permissionGranted(Manifest.permission.CAMERA) -> cameraPermission.launch(Manifest.permission.CAMERA)
            else -> scope.launch { connectAfterPermission() }
        }
    }'''
p.write_text(s[:start]+new+s[end:])

p=Path('server/src/emailRecovery.ts')
s=p.read_text()
old='''    const u=await pool.query("SELECT id FROM users WHERE lower(email)=$1 AND email_verified_at IS NOT NULL AND account_status='active'",[b.data.email]);'''
new='''    const u=await pool.query("SELECT id FROM users WHERE lower(email)=$1 AND account_status='active'",[b.data.email]);'''
assert old in s, 'password request query changed'
s=s.replace(old,new,1)
old='''      const u=await c.query("SELECT id FROM users WHERE lower(email)=$1 AND email_verified_at IS NOT NULL AND account_status='active' FOR UPDATE",[b.data.email]);'''
new='''      const u=await c.query("SELECT id FROM users WHERE lower(email)=$1 AND account_status='active' FOR UPDATE",[b.data.email]);'''
assert old in s, 'password reset query changed'
s=s.replace(old,new,1)
old="""      await c.query('UPDATE user_credentials SET password_hash=$2,updated_at=now() WHERE user_id=$1',[userId,passwordHash]);"""
new="""      await c.query('UPDATE user_credentials SET password_hash=$2,updated_at=now() WHERE user_id=$1',[userId,passwordHash]);
      await c.query('UPDATE users SET email_verified_at=COALESCE(email_verified_at,now()),updated_at=now() WHERE id=$1',[userId]);"""
assert old in s, 'password reset success anchor changed'
p.write_text(s.replace(old,new,1))

print('scoped runtime fixes applied')
