from pathlib import Path

p = Path('server/src/index.ts')
s = p.read_text()

# New registrations should never receive the product name as a user-looking identity.
if 'username ?? "ZoolChat"]);' in s:
    s = s.replace('username ?? "ZoolChat"]);', 'username ?? "ZoolChat User"]);', 1)

# Older clients still call session/bootstrap. If they send the old product-name placeholder,
# repair the visible identity from the real username rather than overwriting it with "ZoolChat".
old_bootstrap = '''    const r = await pool.query(`UPDATE users SET display_name=$2,username=COALESCE($3,username),online=true,last_seen_at=now(),updated_at=now() WHERE id=$1 RETURNING *`, [req.zoolUser!.id, b.data.displayName, b.data.username ?? null]);'''
new_bootstrap = '''    const r = await pool.query(`UPDATE users SET display_name=CASE WHEN $2 IN ('ZoolChat','ZoolChat User') AND COALESCE($3,username) IS NOT NULL THEN COALESCE($3,username) ELSE $2 END,username=COALESCE($3,username),online=true,last_seen_at=now(),updated_at=now() WHERE id=$1 RETURNING *`, [req.zoolUser!.id, b.data.displayName, b.data.username ?? null]);'''
if old_bootstrap in s:
    s = s.replace(old_bootstrap, new_bootstrap, 1)

# Make profile update atomic and map duplicate usernames to 409 instead of a generic server error.
start = s.index("app.put('/api/me', requireAuth, async (req, res) => {")
end = s.index("app.post('/api/devices/fcm'", start)
route = '''app.put('/api/me', requireAuth, async (req, res) => {
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
s = s[:start] + route + s[end:]
p.write_text(s)
print('PASS: live server profile identity patch staged')
