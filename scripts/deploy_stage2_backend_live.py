from pathlib import Path


def patch(path: str, old: str, new: str):
    p = Path(path)
    s = p.read_text()
    if new in s:
        return
    if old not in s:
        raise SystemExit(f'live stage2 patch guard failed for {path}: {old[:120]!r}')
    p.write_text(s.replace(old, new, 1))

# Install real email 2FA into password login without changing registration semantics.
patch(
    'server/src/index.ts',
    "import { emailDeliveryConfigured, installEmailRecovery } from './emailRecovery.js';",
    "import { emailDeliveryConfigured, installEmailRecovery } from './emailRecovery.js';\nimport { beginLoginTwoFactorIfEnabled, installTwoFactor } from './twoFactor.js';",
)
patch(
    'server/src/index.ts',
    "installEmailRecovery(app, id => { for(const socket of sockets.get(id) ?? []) socket.close(4001, 'session_revoked'); sockets.delete(id); });",
    "installEmailRecovery(app, id => { for(const socket of sockets.get(id) ?? []) socket.close(4001, 'session_revoked'); sockets.delete(id); });\ninstallTwoFactor(app);",
)
patch(
    'server/src/index.ts',
    "  const session=await createSession(r.rows[0].id,b.deviceName);\n  res.json({user:userJson(r.rows[0]),...session});",
    "  try {\n    const secondFactor=await beginLoginTwoFactorIfEnabled(r.rows[0].id,b.deviceName);\n    if(secondFactor) return res.status(202).json(secondFactor);\n  } catch(e:any) {\n    if(e?.message==='two_factor_rate_limited') return res.status(429).json({error:'two_factor_rate_limited'});\n    return res.status(503).json({error:'email_delivery_unavailable'});\n  }\n  const session=await createSession(r.rows[0].id,b.deviceName);\n  res.json({user:userJson(r.rows[0]),...session});",
)

old_fcm = """  await pool.query(`INSERT INTO devices(user_id,fcm_token,platform,device_name,last_seen_at,revoked_at) VALUES($1,$2,$3,$4,now(),NULL)
    ON CONFLICT(fcm_token) DO UPDATE SET user_id=EXCLUDED.user_id,device_name=EXCLUDED.device_name,last_seen_at=now(),revoked_at=NULL,updated_at=now()`, [req.zoolUser!.id, b.token, b.platform, b.deviceName ?? null]);"""
new_fcm = """  await pool.query(`INSERT INTO devices(user_id,fcm_token,platform,device_name,auth_session_id,last_seen_at,revoked_at) VALUES($1,$2,$3,$4,$5,now(),NULL)
    ON CONFLICT(fcm_token) DO UPDATE SET user_id=EXCLUDED.user_id,device_name=EXCLUDED.device_name,auth_session_id=EXCLUDED.auth_session_id,last_seen_at=now(),revoked_at=NULL,updated_at=now()`, [req.zoolUser!.id, b.token, b.platform, b.deviceName ?? null, req.zoolUser!.sessionId ?? null]);"""
patch('server/src/index.ts', old_fcm, new_fcm)

old_devices = """app.get('/api/devices', requireAuth, async (req, res) => {
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
new_devices = """app.get('/api/devices', requireAuth, async (req, res) => {
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
patch('server/src/index.ts', old_devices, new_devices)

# Bind authenticated requests to the concrete session so device revocation can identify current/other devices.
patch(
    'server/src/auth.ts',
    "zoolUser?: { id:string; firebaseUid?:string; phoneE164?:string; displayName:string; authMode?:'free'|'firebase'|'dev' }",
    "zoolUser?: { id:string; firebaseUid?:string; phoneE164?:string; displayName:string; authMode?:'free'|'firebase'|'dev'; sessionId?:string }",
)
patch(
    'server/src/auth.ts',
    "req.zoolUser={id:free.id,firebaseUid:free.firebase_uid??undefined,phoneE164:free.phone_e164??undefined,displayName:free.display_name,authMode:'free'};",
    "req.zoolUser={id:free.id,firebaseUid:free.firebase_uid??undefined,phoneE164:free.phone_e164??undefined,displayName:free.display_name,authMode:'free',sessionId:String(free.session_id)};",
)

# Username deep-link resolver and shareable safe profile. Blocking remains authoritative; privacy hides protected fields.
profile_insert = """
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
patch('server/src/profileV032.ts', " app.put('/api/me',requireAuth,async(req,res)=>{", profile_insert + " app.put('/api/me',requireAuth,async(req,res)=>{")
patch(
    'server/src/profileV032.ts',
    "  if((viewer!==target&&!shared)||blocked.rowCount)return res.status(404).json({error:'profile_unavailable'});",
    "  if(blocked.rowCount)return res.status(404).json({error:'profile_unavailable'});",
)

# Password recovery remains a safe escape hatch: a successful reset disables 2FA and consumes outstanding challenges.
patch(
    'server/src/emailRecovery.ts',
    "      await c.query('UPDATE devices SET revoked_at=now() WHERE user_id=$1 AND revoked_at IS NULL',[userId]);\n      await c.query('DELETE FROM account_recovery WHERE user_id=$1',[userId]);",
    "      await c.query('UPDATE devices SET revoked_at=now() WHERE user_id=$1 AND revoked_at IS NULL',[userId]);\n      await c.query('UPDATE user_two_factor_settings SET enabled=false,enabled_at=NULL,updated_at=now() WHERE user_id=$1',[userId]);\n      await c.query('UPDATE two_factor_challenges SET consumed_at=now() WHERE user_id=$1 AND consumed_at IS NULL',[userId]);\n      await c.query('DELETE FROM account_recovery WHERE user_id=$1',[userId]);",
)

print('live stage2 backend patch prepared')
