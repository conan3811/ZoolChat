from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"{label}: expected source block not found")
    return text.replace(old, new, 1)


# 1) Bootstrap must never collapse to a blank/ZoolChat identity just because a legacy backend
# cannot persist the display-name cleanup through PUT /api/me. Keep the authoritative username
# visible immediately, then try both the v0.32 profile route and the legacy bootstrap route.
p = Path("android/app/src/main/java/com/zoolchat/app/state/ZoolAppViewModel.kt")
s = p.read_text()
old = '''                if (username != null && displayName in setOf("ZoolChat", "ZoolChat User")) {
                    user = api.updateProfile(displayName = username)
                    displayName = user.optString("displayName", username).ifBlank { username }
                    username = user.optString("username").takeIf {
                        it.isNotBlank() && it != "null" && !it.startsWith("zc_tmp_")
                    } ?: username
                }
'''
new = '''                if (username != null && displayName in setOf("ZoolChat", "ZoolChat User")) {
                    val repaired = runCatching { api.updateProfile(displayName = username) }
                        .recoverCatching { api.bootstrap(username, username).getJSONObject("user") }
                        .getOrNull()
                    if (repaired != null) {
                        user = repaired
                        displayName = repaired.optString("displayName", username).takeIf {
                            it.isNotBlank() && it != "null" && it !in setOf("ZoolChat", "ZoolChat User")
                        } ?: username
                        username = repaired.optString("username").takeIf {
                            it.isNotBlank() && it != "null" && !it.startsWith("zc_tmp_")
                        } ?: username
                    } else {
                        // The server identity still contains the real username. A failed cosmetic
                        // display-name migration must not hide it or leave the current user null.
                        displayName = username
                    }
                }
'''
s = replace_once(s, old, new, "bootstrap placeholder identity repair")
p.write_text(s)


# 2) Initial profile setup must work while production is on either the legacy or v0.32 profile API.
# PUT /api/me is preferred; if that route is unavailable/server-incompatible, use the already
# supported /api/session/bootstrap compatibility path. A true username conflict (409) is preserved.
p = Path("android/app/src/main/java/com/zoolchat/app/ui/screens/InitialProfileSetupScreen.kt")
s = p.read_text()
old = '''                    try {
                        val result=api.put("/api/me",JSONObject().put("displayName",name).put("username",user)).getJSONObject("user")
                        val savedName=result.optString("displayName").takeUnless{it=="null"}.orEmpty()
                        val savedUsername=result.optString("username").takeUnless{it=="null"}.orEmpty().lowercase()
                        if(savedName!=name || !savedUsername.equals(user,ignoreCase=true)) {
                            throw IllegalStateException("profile_identity_not_persisted")
                        }
                        session.localDisplayName=savedName
                        session.accountUsername=savedUsername
                        usernameState=setupLabel("تم حفظ اسم المستخدم","Username saved")
                        stage=1
                    } catch(e:ApiException) {
                        val raw=e.message.orEmpty()
                        if(e.status==409||raw.contains("username_taken",true)) usernameState=setupLabel("اسم المستخدم مستخدم بالفعل","Username is already used")
                        else error=setupLabel("تعذر حفظ الملف الشخصي. حاول مرة أخرى.","Unable to save your profile. Try again.")
'''
new = '''                    try {
                        val body=JSONObject().put("displayName",name).put("username",user)
                        val result=try {
                            api.put("/api/me",body).getJSONObject("user")
                        } catch(primary:ApiException) {
                            val raw=primary.message.orEmpty()
                            if(primary.status==409||raw.contains("username_taken",true)) throw primary
                            // Production can temporarily be one backend revision behind Android.
                            // The legacy session bootstrap persists the same displayName/username.
                            api.bootstrap(name,user).getJSONObject("user")
                        }
                        val savedName=result.optString("displayName").takeUnless{it=="null"}.orEmpty()
                        val savedUsername=result.optString("username").takeUnless{it=="null"}.orEmpty().lowercase()
                        if(savedName!=name || !savedUsername.equals(user,ignoreCase=true)) {
                            throw IllegalStateException("profile_identity_not_persisted")
                        }
                        session.localDisplayName=savedName
                        session.accountUsername=savedUsername
                        usernameState=setupLabel("تم حفظ اسم المستخدم","Username saved")
                        stage=1
                    } catch(e:ApiException) {
                        val raw=e.message.orEmpty()
                        if(e.status==409||raw.contains("username_taken",true)) usernameState=setupLabel("اسم المستخدم مستخدم بالفعل","Username is already used")
                        else error=setupLabel("تعذر حفظ الملف الشخصي. حاول مرة أخرى.","Unable to save your profile. Try again.")
'''
s = replace_once(s, old, new, "legacy-compatible initial profile save")

# 3) If an account already has a real username, prefill it when this screen is reached because of
# stale local onboarding state. This avoids presenting an existing account as an empty/new profile.
needle = '''    fun finishSetup() {
        session.initialProfileSetupPending=false
        onComplete()
    }

'''
replacement = '''    fun finishSetup() {
        session.initialProfileSetupPending=false
        onComplete()
    }

    LaunchedEffect(Unit) {
        runCatching { api.get("/api/me").getJSONObject("user") }.onSuccess { existing ->
            val existingUsername=existing.optString("username").takeIf {
                it.isNotBlank() && it!="null" && !it.startsWith("zc_tmp_")
            }
            val existingDisplay=existing.optString("displayName").takeIf {
                it.isNotBlank() && it!="null" && it !in setOf("ZoolChat","ZoolChat User")
            }
            if(existingUsername!=null) {
                username=existingUsername
                displayName=existingDisplay ?: existingUsername
                session.accountUsername=existingUsername.lowercase()
                session.localDisplayName=displayName
            }
        }
    }

'''
s = replace_once(s, needle, replacement, "existing profile prefill")
p.write_text(s)

print("PASS: staged legacy-compatible profile setup and identity display fix")
