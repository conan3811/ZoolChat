from pathlib import Path

repo = Path('android/app/src/main/java/com/zoolchat/app/sync/MessageSyncRepository.kt')
text = repo.read_text()
old = '''    suspend fun markRead(chatId: String, messageId: String) {
        clearUnread(chatId)
        api.markRead(chatId, messageId)
    }
'''
new = '''    suspend fun markRead(chatId: String, messageId: String) {
        api.markRead(chatId, messageId)
        clearUnread(chatId)
    }
'''
assert old in text, 'MessageSyncRepository markRead pattern changed'
repo.write_text(text.replace(old, new, 1))

vm = Path('android/app/src/main/java/com/zoolchat/app/state/ZoolAppViewModel.kt')
text = vm.read_text()
old = '''    fun markRead(chatId: String) {
        viewModelScope.launch { sync.clearUnread(chatId) }
        val myId = _currentUser.value?.id ?: return
        val lastIncoming = _messages.value[chatId].orEmpty().lastOrNull { it.senderId != myId && !it.id.startsWith("local:") } ?: return
        viewModelScope.launch { runCatching { sync.markRead(chatId, lastIncoming.id); sync.refreshChats() } }
    }
'''
new = '''    fun markRead(chatId: String) {
        val myId = _currentUser.value?.id ?: return
        val lastIncoming = _messages.value[chatId].orEmpty().lastOrNull { it.senderId != myId && !it.id.startsWith("local:") } ?: return
        viewModelScope.launch {
            runCatching { sync.markRead(chatId, lastIncoming.id); sync.refreshChats() }
                .onFailure { _error.value = it.message }
        }
    }
'''
assert old in text, 'ZoolAppViewModel markRead pattern changed'
vm.write_text(text.replace(old, new, 1))

ui = Path('android/app/src/main/java/com/zoolchat/app/ui/screens/OnboardingAuthScreens.kt')
text = ui.read_text()
assert 'import kotlinx.coroutines.launch\n' in text
text = text.replace('import kotlinx.coroutines.launch\n', 'import kotlinx.coroutines.delay\nimport kotlinx.coroutines.launch\n', 1)

old = '''    var code by remember { mutableStateOf("") }
    var codeSent by remember { mutableStateOf(false) }
    var twoFactorChallenge by remember { mutableStateOf<String?>(null) }
'''
new = '''    var code by remember { mutableStateOf("") }
    var codeSent by remember { mutableStateOf(false) }
    var resendCooldownSeconds by remember { mutableIntStateOf(0) }
    var twoFactorChallenge by remember { mutableStateOf<String?>(null) }
'''
assert old in text, 'recovery state pattern changed'
text = text.replace(old, new, 1)

old = '''        code = ""
        codeSent = false
        twoFactorChallenge = null
'''
new = '''        code = ""
        codeSent = false
        resendCooldownSeconds = 0
        twoFactorChallenge = null
'''
assert old in text, 'changeMode recovery reset pattern changed'
text = text.replace(old, new, 1)

anchor = '''    BackHandler(enabled = (mode != "login" || twoFactorChallenge != null) && !loading) {
        if (twoFactorChallenge != null) { twoFactorChallenge = null; twoFactorCode = ""; com.zoolchat.app.auth.RecentAuthSecret.clear() }
        else changeMode("login")
    }

'''
insert = anchor + '''    LaunchedEffect(resendCooldownSeconds) {
        if (resendCooldownSeconds > 0) {
            delay(1_000)
            resendCooldownSeconds -= 1
        }
    }

'''
assert anchor in text, 'BackHandler anchor changed'
text = text.replace(anchor, insert, 1)

old = '''            Spacer(Modifier.height(8.dp))
        }

        if (mode != "recover" || codeSent) {
'''
new = '''            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = {
                    loading = true
                    error = null
                    feedback = null
                    scope.launch {
                        try {
                            auth.requestPasswordCode(email).getOrThrow()
                            code = ""
                            resendCooldownSeconds = 60
                            feedback = authLabel(
                                "تم طلب رمز مؤقت جديد. إذا كان البريد موثقاً ومسجلاً فسيصلك عبر البريد الإلكتروني.",
                                "A new temporary code was requested. If the email is registered and verified, it will be sent by email.",
                            )
                        } catch (e: Exception) {
                            error = e.message ?: authLabel("تعذر إعادة إرسال الرمز", "Unable to resend code")
                        } finally {
                            loading = false
                        }
                    }
                },
                enabled = !loading && resendCooldownSeconds == 0,
            ) {
                Text(
                    if (resendCooldownSeconds > 0)
                        authLabel("إعادة إرسال الرمز بعد ${resendCooldownSeconds}ث", "Resend code in ${resendCooldownSeconds}s")
                    else authLabel("إعادة إرسال الرمز", "Resend code")
                )
            }
            Spacer(Modifier.height(4.dp))
        }

        if (mode != "recover" || codeSent) {
'''
assert old in text, 'recovery code field anchor changed'
text = text.replace(old, new, 1)

old = '''                                auth.requestPasswordCode(email).getOrThrow()
                                codeSent = true
                                feedback = authLabel(
'''
new = '''                                auth.requestPasswordCode(email).getOrThrow()
                                codeSent = true
                                resendCooldownSeconds = 60
                                feedback = authLabel(
'''
assert old in text, 'initial recovery request pattern changed'
text = text.replace(old, new, 1)
ui.write_text(text)

# Scoped invariants.
repo_text = repo.read_text()
block = repo_text[repo_text.index('    suspend fun markRead(chatId: String, messageId: String) {'):]
block = block[:block.index('    companion object')]
assert block.index('api.markRead(chatId, messageId)') < block.index('clearUnread(chatId)')
vm_text = vm.read_text()
block = vm_text[vm_text.index('    fun markRead(chatId: String) {'):]
block = block[:block.index('    private fun handleRealtime')]
assert 'sync.clearUnread(chatId)' not in block
ui_text = ui.read_text()
assert 'إعادة إرسال الرمز' in ui_text
assert ui_text.count('auth.requestPasswordCode(email).getOrThrow()') >= 2
assert 'resendCooldownSeconds = 60' in ui_text
print('PASS: only requested unread/read-order and recovery-resend behaviors applied')
