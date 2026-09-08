from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"{label}: expected source block not found")
    return text.replace(old, new, 1)


# Backend: never surface a username uniqueness conflict as a generic HTTP 500,
# and stop creating new no-username accounts with the confusing literal display name "ZoolChat".
p = Path("server/src/index.ts")
s = p.read_text()
s = replace_once(
    s,
    'username ?? "ZoolChat"]);',
    'username ?? "ZoolChat User"]);',
    "registration display-name placeholder",
)
start = s.index("app.put('/api/me', requireAuth, async (req, res) => {")
end = s.index("app.post('/api/devices/fcm'", start)
profile_route = '''app.put('/api/me', requireAuth, async (req, res) => {
  const parsed = z.object({
    displayName: z.string().trim().min(1).max(80).optional(),
    username: z.string().trim().min(3).max(32).regex(/^[A-Za-z0-9_.]+$/).nullable().optional(),
    about: z.string().max(160).optional(),
    photoUrl: z.string().url().nullable().optional(),
    photoMediaId: z.string().uuid().nullable().optional(),
  }).safeParse(req.body);
  if (!parsed.success) return res.status(400).json({ error: 'invalid_request' });
  const b = parsed.data;
  const uid = req.zoolUser!.id;
  if (b.photoMediaId) {
    const owned = await pool.query(`SELECT 1 FROM media_assets WHERE id=$1 AND owner_id=$2 AND mime_type LIKE 'image/%'`, [b.photoMediaId, uid]);
    if (!owned.rowCount) return res.status(400).json({ error: 'invalid_profile_photo' });
  }
  try {
    let r = await pool.query(`UPDATE users SET
      display_name=COALESCE($2,display_name),
      username=CASE WHEN $3::boolean THEN $4::text ELSE username END,
      about=COALESCE($5,about),
      photo_url=CASE WHEN $6::boolean THEN $7::text ELSE photo_url END,
      updated_at=now()
      WHERE id=$1 RETURNING *`, [
        uid,
        b.displayName ?? null,
        b.username !== undefined,
        b.username === undefined ? null : (b.username?.toLowerCase() ?? null),
        b.about ?? null,
        b.photoUrl !== undefined,
        b.photoUrl ?? null,
      ]);
    if (b.photoMediaId !== undefined) {
      r = await pool.query(`UPDATE users SET profile_photo_media_id=$2,photo_url=NULL,updated_at=now() WHERE id=$1 RETURNING *`, [uid, b.photoMediaId]);
    }
    res.json({ user: userJson(r.rows[0]) });
  } catch (e: any) {
    if (e?.code === '23505') return res.status(409).json({ error: 'username_taken' });
    throw e;
  }
});
'''
s = s[:start] + profile_route + s[end:]
p.write_text(s)


# Android bootstrap: the backend is authoritative. Do not overwrite server identity with a stale
# local display name on every startup. Also repair legacy placeholder display names when a real
# username already exists, and keep the cached username synchronized with the server.
p = Path("android/app/src/main/java/com/zoolchat/app/state/ZoolAppViewModel.kt")
s = p.read_text()
start = s.index("    fun bootstrap(")
end = s.index("    private fun startRealtime()", start)
bootstrap = '''    fun bootstrap() {
        viewModelScope.launch {
            runCatching {
                var user = api.get("/api/me").getJSONObject("user")
                var username = user.optString("username").takeIf {
                    it.isNotBlank() && it != "null" && !it.startsWith("zc_tmp_")
                }
                var displayName = user.optString("displayName").takeIf {
                    it.isNotBlank() && it != "null"
                }.orEmpty()

                // Older registration builds used a product-name placeholder as the display name.
                // If the account already has a real username (for example "finance"), use that as
                // the initial display name instead of showing "ZoolChat" as though it were identity.
                if (username != null && displayName in setOf("ZoolChat", "ZoolChat User")) {
                    user = api.updateProfile(displayName = username)
                    displayName = user.optString("displayName", username).ifBlank { username }
                    username = user.optString("username").takeIf {
                        it.isNotBlank() && it != "null" && !it.startsWith("zc_tmp_")
                    } ?: username
                }

                _currentUser.value = CurrentUser(
                    user.getString("id"),
                    displayName.ifBlank { username ?: "ZoolChat User" },
                    username,
                    user.optString("phoneE164").takeIf { it.isNotBlank() && it != "null" },
                    user.optString("photoUrl").takeIf { it.isNotBlank() && it != "null" },
                )
                session.localDisplayName = displayName.ifBlank { username ?: "ZoolChat User" }
                session.accountUsername = username.orEmpty()
                session.currentUserId = user.getString("id")
                runCatching { e2ee.registerLocalKey() }.onFailure { _error.value = "تعذر تسجيل مفتاح التشفير: ${it.message}" }
                startRealtime()
                sync.refreshChats()
                refreshCallHistoryInternal()
                MessageSyncRepository.scheduleSend(getApplication())
                registerFcmIfAvailable()
            }.onFailure { _error.value = it.message }
        }
    }

'''
s = s[:start] + bootstrap + s[end:]
needle = '''                session.localDisplayName = user.getString("displayName")
                session.currentUserId = user.getString("id")
            }.onFailure { _error.value = it.message }
'''
replacement = '''                session.localDisplayName = user.getString("displayName")
                session.accountUsername = user.optString("username").takeIf { it.isNotBlank() && it != "null" && !it.startsWith("zc_tmp_") }.orEmpty()
                session.currentUserId = user.getString("id")
            }.onFailure { _error.value = it.message }
'''
s = replace_once(s, needle, replacement, "explicit profile identity cache sync")
p.write_text(s)


# Initial profile setup: use the atomic profile update itself as the username check. A separate
# availability request can be rate-limited or race with the save and was leaving the UI stuck on
# "Checking..." followed by a generic failure. HTTP 409 is already mapped to "username used".
p = Path("android/app/src/main/java/com/zoolchat/app/ui/screens/InitialProfileSetupScreen.kt")
s = p.read_text()
old = '''                busy=true;error=null;usernameState=setupLabel("جاري التحقق…","Checking…")
                scope.launch {
                    try {
                        if(!usernameAvailableCompatible(api,user)) {
                            usernameState=setupLabel("اسم المستخدم مستخدم بالفعل","Username is already used")
                            return@launch
                        }
                        val result=api.put("/api/me",JSONObject().put("displayName",name).put("username",user)).getJSONObject("user")
                        session.localDisplayName=result.optString("displayName",name)
                        session.accountUsername=result.optString("username",user).takeUnless{it=="null"}.orEmpty().lowercase()
                        usernameState=setupLabel("اسم المستخدم متاح","Username is available")
                        stage=1
'''
new = '''                busy=true;error=null;usernameState=setupLabel("جاري الحفظ…","Saving…")
                scope.launch {
                    try {
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
'''
s = replace_once(s, old, new, "initial profile save")
p.write_text(s)

print("PASS: staged ZoolChat v0.32 profile/username fixes")
