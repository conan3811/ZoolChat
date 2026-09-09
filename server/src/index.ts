import 'dotenv/config';
import http from 'node:http';
import express from 'express';
import cors from 'cors';
import helmet from 'helmet';
import { rateLimit } from 'express-rate-limit';
import multer from 'multer';
import path from 'node:path';
import fs from 'node:fs';
import { S3Client, PutObjectCommand, GetObjectCommand } from '@aws-sdk/client-s3';
import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { WebSocketServer, WebSocket } from 'ws';
import { AccessToken } from 'livekit-server-sdk';
import { z } from 'zod';
import { pool, tx } from './db.js';
import { requireAuth } from './auth.js';
import { createSession, hashPassword, revokeRefreshToken, rotateRefreshToken, verifyPassword, resolveAccessToken } from './freeAuth.js';
import { deleteFirebaseUser, sendData, verifyFirebaseToken } from './firebase.js';
import { installMeetingInvitations } from './meetingInvitations.js';
import { emailDeliveryConfigured, installEmailRecovery } from './emailRecovery.js';
import { beginLoginTwoFactorIfEnabled, installTwoFactor } from './twoFactor.js';
import { installAdmin } from './admin.js';

const app = express();
const corsOrigins = (process.env.CORS_ORIGINS ?? '').split(',').map(x => x.trim()).filter(Boolean);
if (process.env.TRUST_PROXY === 'true') app.set('trust proxy', 1);
app.use(helmet({ crossOriginResourcePolicy: false }));
app.use(cors({ origin: corsOrigins.length ? corsOrigins : false }));
app.use(express.json({ limit: '1mb' }));
app.use((req, res, next) => {
  const requestId = req.header('x-request-id')?.slice(0, 100) || randomUUID();
  res.setHeader('x-request-id', requestId);
  next();
});
app.use(rateLimit({
  windowMs: Number(process.env.RATE_LIMIT_WINDOW_MS ?? 15 * 60 * 1000),
  limit: Number(process.env.RATE_LIMIT_MAX ?? 300),
  standardHeaders: 'draft-8',
  legacyHeaders: false,
}));
const messageRateLimit = rateLimit({ windowMs: 60_000, limit: Number(process.env.MESSAGE_RATE_LIMIT_PER_MINUTE ?? 120), standardHeaders: 'draft-8', legacyHeaders: false });
const mediaRateLimit = rateLimit({ windowMs: 60_000, limit: Number(process.env.MEDIA_RATE_LIMIT_PER_MINUTE ?? 30), standardHeaders: 'draft-8', legacyHeaders: false });
const discoveryRateLimit = rateLimit({ windowMs: 60_000, limit: Number(process.env.DISCOVERY_RATE_LIMIT_PER_MINUTE ?? 20), standardHeaders: 'draft-8', legacyHeaders: false });
const callRateLimit = rateLimit({ windowMs: 5 * 60_000, limit: Number(process.env.CALL_RATE_LIMIT_PER_5_MINUTES ?? 30), standardHeaders: 'draft-8', legacyHeaders: false });
const typingRateLimit = rateLimit({ windowMs: 60_000, limit: Number(process.env.TYPING_RATE_LIMIT_PER_MINUTE ?? 180), standardHeaders: 'draft-8', legacyHeaders: false });
const authRateLimit = rateLimit({ windowMs: 15 * 60_000, limit: Number(process.env.AUTH_RATE_LIMIT_PER_15_MINUTES ?? 40), standardHeaders: 'draft-8', legacyHeaders: false });
const server = http.createServer(app);
const port = Number(process.env.PORT ?? 8080);
const wsUrl = process.env.LIVEKIT_URL;
const apiKey = process.env.LIVEKIT_API_KEY;
const apiSecret = process.env.LIVEKIT_API_SECRET;
const mediaDir = path.resolve(process.env.MEDIA_DIR ?? './data/media');
const pushMessagePreviews = process.env.PUSH_MESSAGE_PREVIEWS === 'true';
const maxMediaMb = Math.max(1, Math.min(Number(process.env.MAX_MEDIA_MB ?? 40), 250));
const r2Endpoint = process.env.R2_ENDPOINT?.trim();
const r2AccessKeyId = process.env.R2_ACCESS_KEY_ID?.trim();
const r2SecretAccessKey = process.env.R2_SECRET_ACCESS_KEY?.trim();
const r2Bucket = process.env.R2_BUCKET?.trim();
const r2Enabled = Boolean(r2Endpoint && r2AccessKeyId && r2SecretAccessKey && r2Bucket);
const r2 = r2Enabled ? new S3Client({
  region: 'auto',
  endpoint: r2Endpoint,
  credentials: { accessKeyId: r2AccessKeyId!, secretAccessKey: r2SecretAccessKey! },
}) : null;
fs.mkdirSync(mediaDir, { recursive: true });

const callRingTimeoutSeconds = Math.max(20, Math.min(Number(process.env.CALL_RING_TIMEOUT_SECONDS ?? 60), 180));
const sha256Hex = (value: string) => createHash('sha256').update(value, 'utf8').digest('hex');
const newRecoveryCode = () => `ZC-${randomBytes(18).toString('base64url')}`;

const upload = multer({
  storage: r2Enabled ? multer.memoryStorage() : multer.diskStorage({
    destination: (_req, _file, cb) => cb(null, mediaDir),
    filename: (_req, file, cb) => cb(null, `${randomUUID()}${path.extname(file.originalname).slice(0, 12)}`),
  }),
  limits: { fileSize: maxMediaMb * 1024 * 1024 },
  fileFilter: (_req, file, cb) => {
    const ok = /^(image|video|audio)\//.test(file.mimetype) || /^(application\/(pdf|zip|octet-stream)|text\/plain)$/.test(file.mimetype);
    if (ok) cb(null, true); else cb(new Error('unsupported_media_type'));
  },
});

const sockets = new Map<string, Set<WebSocket>>();
installEmailRecovery(app, id => { for(const socket of sockets.get(id) ?? []) socket.close(4001, 'session_revoked'); sockets.delete(id); });
installTwoFactor(app);
function emitTo(userId: string, event: unknown) {
  for (const socket of sockets.get(userId) ?? []) if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(event));
}
installMeetingInvitations(app,emitTo);
async function fcmTokens(userIds: string[]) {
  if (!userIds.length) return [];
  const r = await pool.query(`SELECT fcm_token FROM devices WHERE user_id=ANY($1::uuid[]) AND revoked_at IS NULL`, [userIds]);
  return r.rows.map(x => x.fcm_token);
}
async function fcmTargets(userIds: string[]) {
  if (!userIds.length) return [] as Array<{userId:string,token:string,messagePreviews:boolean}>;
  const r = await pool.query(`SELECT d.user_id,d.fcm_token,COALESCE(p.message_previews,true) message_previews FROM devices d LEFT JOIN user_privacy_settings p ON p.user_id=d.user_id WHERE d.user_id=ANY($1::uuid[]) AND d.revoked_at IS NULL`,[userIds]);
  return r.rows.map(x=>({userId:String(x.user_id),token:String(x.fcm_token),messagePreviews:x.message_previews!==false}));
}
async function membership(chatId: string, userId: string) {
  const r = await pool.query(`SELECT 1 FROM chat_members WHERE chat_id=$1 AND user_id=$2`, [chatId, userId]);
  return r.rowCount === 1;
}
async function memberRole(chatId: string, userId: string) {
  const r = await pool.query(`SELECT role FROM chat_members WHERE chat_id=$1 AND user_id=$2`, [chatId, userId]);
  return r.rows[0]?.role as string | undefined;
}
async function ensureDirectChat(userA: string, userB: string) {
  const [a,b] = [userA,userB].sort();
  const existing = await pool.query(`SELECT chat_id FROM direct_chats WHERE user_a=$1 AND user_b=$2`, [a,b]);
  if (existing.rowCount) return String(existing.rows[0].chat_id);
  return tx(async c => {
    const again = await c.query(`SELECT chat_id FROM direct_chats WHERE user_a=$1 AND user_b=$2`, [a,b]);
    if (again.rowCount) return String(again.rows[0].chat_id);
    const cr = await c.query(`INSERT INTO chats(type) VALUES('direct') RETURNING id`);
    const id = String(cr.rows[0].id);
    await c.query(`INSERT INTO direct_chats(user_a,user_b,chat_id) VALUES($1,$2,$3)`, [a,b,id]);
    await c.query(`INSERT INTO chat_members(chat_id,user_id) VALUES($1,$2),($1,$3)`, [id,a,b]);
    return id;
  });
}
async function syncCallTimeline(callId: string) {
  const cr = await pool.query(`SELECT c.*, cp.user_id peer_id FROM calls c JOIN call_participants cp ON cp.call_id=c.id AND cp.user_id<>c.initiator_id WHERE c.id=$1 LIMIT 1`, [callId]);
  if (!cr.rowCount) return;
  const c = cr.rows[0];
  const chatId = await ensureDirectChat(c.initiator_id, c.peer_id);
  const payload = JSON.stringify({callId,mode:c.type,status:c.status,startedAt:new Date(c.created_at).toISOString(),answeredAt:c.answered_at?new Date(c.answered_at).toISOString():null,endedAt:c.ended_at?new Date(c.ended_at).toISOString():null});
  const r = await pool.query(`INSERT INTO messages(chat_id,sender_id,client_id,type,body) VALUES($1,$2,$3,'call',$4)
    ON CONFLICT(sender_id,client_id) DO UPDATE SET body=EXCLUDED.body,edited_at=now() RETURNING *`, [chatId,c.initiator_id,`call:${callId}`,payload]);
  const msg = messageJson({...r.rows[0],status:'sent'});
  const peers = await pool.query(`SELECT user_id FROM chat_members WHERE chat_id=$1`,[chatId]);
  for (const p of peers.rows) emitTo(p.user_id,{type:'message.edited',data:msg});
  const tokens = await fcmTokens(peers.rows.map(x=>x.user_id));
  await sendData(tokens,{type:'chat_sync',chatId},false);
}
function newMeetingCode() {
  const alphabet='abcdefghjkmnpqrstuvwxyz23456789';
  const part=(n:number)=>Array.from({length:n},()=>alphabet[Math.floor(Math.random()*alphabet.length)]).join('');
  return `${part(3)}-${part(4)}-${part(3)}`;
}
function userJson(r: any) {
  return { id: r.id, displayName: r.display_name, username: r.username ?? null, email: r.email ?? null, emailVerified: Boolean(r.email_verified_at), phoneE164: r.phone_e164 ?? null, photoUrl: r.profile_photo_media_id ? `/api/media/${r.profile_photo_media_id}` : (r.photo_url ?? null), about: r.about ?? '' };
}
function mediaJson(r: any) {
  if (!r?.media_id) return null;
  return { id: r.media_id, url: `/api/media/${r.media_id}`, mime: r.media_mime, name: r.media_name, size: Number(r.media_size ?? 0) };
}
async function messageReactionMap(messageIds: string[], viewerId: string) {
  const out = new Map<string, Array<{emoji:string,count:number,reactedByMe:boolean}>>();
  if (!messageIds.length) return out;
  const r = await pool.query(`SELECT message_id,emoji,count(*)::int count,bool_or(user_id=$2) reacted_by_me FROM message_reactions WHERE message_id=ANY($1::uuid[]) GROUP BY message_id,emoji ORDER BY message_id,emoji`, [messageIds, viewerId]);
  for (const row of r.rows) {
    const id = String(row.message_id); const list = out.get(id) ?? [];
    list.push({emoji:String(row.emoji),count:Number(row.count),reactedByMe:Boolean(row.reacted_by_me)}); out.set(id,list);
  }
  return out;
}

function messageJson(r: any) {
  return {
    id: r.id,
    clientId: r.client_id,
    chatId: r.chat_id,
    senderId: r.sender_id,
    type: r.type,
    body: r.body,
    createdAt: new Date(r.created_at).toISOString(),
    editedAt: r.edited_at ? new Date(r.edited_at).toISOString() : null,
    deleted: Boolean(r.deleted_at),
    status: r.status ?? 'sent',
    encrypted: Boolean(r.encrypted),
    cryptoVersion: Number(r.crypto_version ?? 0),
    reactions: Array.isArray(r.reactions) ? r.reactions : [],
    media: mediaJson(r),
  };
}
async function livekitToken(userId: string, displayName: string, roomName: string) {
  if (!wsUrl || !apiKey || !apiSecret) throw new Error('livekit_not_configured');
  const token = new AccessToken(apiKey, apiSecret, { identity: userId, name: displayName, ttl: '20m' });
  token.addGrant({ roomJoin: true, room: roomName, canPublish: true, canSubscribe: true, canPublishData: true });
  return { serverUrl: wsUrl, participantToken: await token.toJwt() };
}

app.get('/health', async (_req, res) => {
  try { await pool.query('select 1'); res.json({ ok: true, service: 'zoolchat-server', version: '0.32.0-dev', adminConfigured: Boolean(process.env.ADMIN_PASSWORD && process.env.ADMIN_PASSWORD.length >= 12), db: true, livekitConfigured: Boolean(wsUrl && apiKey && apiSecret), firebaseConfigured: Boolean(process.env.FIREBASE_SERVICE_ACCOUNT_JSON || process.env.GOOGLE_APPLICATION_CREDENTIALS), emailConfigured: emailDeliveryConfigured(), mediaStorage: r2Enabled ? 'r2' : 'local' }); }
  catch { res.status(503).json({ ok: false, db: false }); }
});
app.get('/ready', async (_req, res) => {
  try {
    await pool.query('select 1');
    const livekit = Boolean(wsUrl && apiKey && apiSecret);
    const freeAuth = process.env.FREE_AUTH_ENABLED !== 'false';
    const firebase = Boolean(process.env.FIREBASE_SERVICE_ACCOUNT_JSON || process.env.GOOGLE_APPLICATION_CREDENTIALS);
    const authReady = freeAuth || firebase || process.env.DEV_AUTH_ENABLED === 'true';
    const ready = livekit && authReady;
    res.status(ready ? 200 : 503).json({ ok: ready, db: true, livekit, auth: authReady, freeAuth, firebase, mediaStorage: r2Enabled ? 'r2' : 'local', devAuth: process.env.DEV_AUTH_ENABLED === 'true' });
  } catch { res.status(503).json({ ok: false, db: false, livekit: false, auth: false }); }
});


app.post('/api/auth/register', authRateLimit, async (req,res)=>{
  if(process.env.FREE_AUTH_ENABLED==='false') return res.status(404).json({error:'free_auth_disabled'});
  const parsed=z.object({
    email:z.string().trim().email().max(254),
    username:z.string().trim().min(3).max(32).regex(/^[A-Za-z0-9_.]+$/).optional(),
    password:z.string().min(8).max(128),
    phoneE164:z.string().regex(/^\+[1-9]\d{5,14}$/),
    deviceName:z.string().trim().max(100).optional(),
  }).safeParse(req.body);
  if(!parsed.success) return res.status(400).json({error:'invalid_request'});
  const b=parsed.data; const username=b.username?.toLowerCase() ?? null; const email=b.email.toLowerCase();
  try{
    const result=await tx(async c=>{
      const discoveryHash=sha256Hex(b.phoneE164);
      const user=await c.query(`INSERT INTO users(username,email,phone_e164,phone_discovery_hash,display_name) VALUES($1,$2,$3,$4,$5) RETURNING *`,[username,email,b.phoneE164,discoveryHash,username ?? "ZoolChat User"]);
      const passwordHash=await hashPassword(b.password);
      await c.query(`INSERT INTO user_credentials(user_id,password_hash) VALUES($1,$2)`,[user.rows[0].id,passwordHash]);
      const recoveryCode=newRecoveryCode();
      await c.query(`INSERT INTO account_recovery(user_id,recovery_hash) VALUES($1,$2)`,[user.rows[0].id,sha256Hex(recoveryCode)]);
      const session=await createSession(user.rows[0].id,b.deviceName,c);
      return {user:user.rows[0],session,recoveryCode};
    });
    res.status(201).json({user:userJson(result.user),...result.session,recoveryCode:result.recoveryCode});
  }catch(e:any){
    if(e?.code==='23505') {
      const constraint=String(e?.constraint??'').toLowerCase();
      if(constraint.includes('email')) return res.status(409).json({error:'email_in_use'});
      if(constraint.includes('phone')) return res.status(409).json({error:'phone_in_use'});
      return res.status(409).json({error:'username_taken'});
    }
    throw e;
  }
});

app.post('/api/auth/login', authRateLimit, async (req,res)=>{
  if(process.env.FREE_AUTH_ENABLED==='false') return res.status(404).json({error:'free_auth_disabled'});
  const parsed=z.object({identifier:z.string().trim().min(3).max(254),password:z.string().min(8).max(128),deviceName:z.string().trim().max(100).optional()}).safeParse(req.body);
  if(!parsed.success) return res.status(400).json({error:'invalid_request'});
  const b=parsed.data; const identifier=b.identifier.replace(/^@/,'').toLowerCase();
  const r=await pool.query(`SELECT u.*,uc.password_hash FROM users u JOIN user_credentials uc ON uc.user_id=u.id WHERE lower(u.username)=lower($1) OR lower(u.email)=lower($1) OR u.phone_e164=$1 LIMIT 1`,[identifier]);
  if(!r.rowCount || !(await verifyPassword(b.password,r.rows[0].password_hash))) return res.status(401).json({error:'invalid_credentials'});
  if(r.rows[0].account_status==='suspended') return res.status(403).json({error:'account_suspended'});
  try {
    const secondFactor=await beginLoginTwoFactorIfEnabled(r.rows[0].id,b.deviceName);
    if(secondFactor) return res.status(202).json(secondFactor);
  } catch(e:any) {
    if(e?.message==='two_factor_rate_limited') return res.status(429).json({error:'two_factor_rate_limited'});
    return res.status(503).json({error:'email_delivery_unavailable'});
  }
  const session=await createSession(r.rows[0].id,b.deviceName);
  res.json({user:userJson(r.rows[0]),...session});
});

app.post('/api/auth/recover', authRateLimit, async (req,res)=>{
  if(process.env.FREE_AUTH_ENABLED==='false') return res.status(404).json({error:'free_auth_disabled'});
  const parsed=z.object({username:z.string().trim().min(3).max(32),recoveryCode:z.string().min(16).max(200),newPassword:z.string().min(8).max(128),deviceName:z.string().trim().max(100).optional()}).safeParse(req.body);
  if(!parsed.success) return res.status(400).json({error:'invalid_request'});
  const b=parsed.data;
  const r=await pool.query(`SELECT u.*,ar.recovery_hash FROM users u JOIN account_recovery ar ON ar.user_id=u.id WHERE lower(u.username)=lower($1) LIMIT 1`,[b.username]);
  if(!r.rowCount || sha256Hex(b.recoveryCode)!==r.rows[0].recovery_hash) return res.status(401).json({error:'invalid_recovery_code'});
  if(r.rows[0].account_status==='suspended') return res.status(403).json({error:'account_suspended'});
  const nextRecoveryCode=newRecoveryCode();
  const passwordHash=await hashPassword(b.newPassword);
  const session=await tx(async c=>{
    await c.query(`UPDATE user_credentials SET password_hash=$2,updated_at=now() WHERE user_id=$1`,[r.rows[0].id,passwordHash]);
    await c.query(`UPDATE auth_sessions SET revoked_at=now() WHERE user_id=$1 AND revoked_at IS NULL`,[r.rows[0].id]);
    await c.query(`UPDATE account_recovery SET recovery_hash=$2,rotated_at=now() WHERE user_id=$1`,[r.rows[0].id,sha256Hex(nextRecoveryCode)]);
    return createSession(r.rows[0].id,b.deviceName,c);
  });
  res.json({user:userJson(r.rows[0]),...session,recoveryCode:nextRecoveryCode});
});

app.post('/api/auth/refresh', authRateLimit, async (req,res)=>{
  const parsed=z.object({refreshToken:z.string().min(20).max(300),deviceName:z.string().trim().max(100).optional()}).safeParse(req.body);
  if(!parsed.success) return res.status(400).json({error:'invalid_request'});
  const session=await rotateRefreshToken(parsed.data.refreshToken,parsed.data.deviceName);
  if(!session) return res.status(401).json({error:'invalid_or_expired_refresh_token'});
  res.json(session);
});

app.post('/api/auth/logout', async (req,res)=>{
  const parsed=z.object({refreshToken:z.string().min(20).max(300)}).safeParse(req.body);
  if(parsed.success) await revokeRefreshToken(parsed.data.refreshToken);
  res.json({ok:true});
});

app.post('/api/session/bootstrap', requireAuth, async (req, res) => {
  const b = z.object({ displayName: z.string().trim().min(1).max(80), username: z.string().trim().min(3).max(32).regex(/^[A-Za-z0-9_.]+$/).optional() }).safeParse(req.body);
  if (!b.success) return res.status(400).json({ error: 'invalid_request' });
  try {
    const r = await pool.query(`UPDATE users SET display_name=CASE WHEN $2 IN ('ZoolChat','ZoolChat User') AND COALESCE($3,username) IS NOT NULL THEN COALESCE($3,username) ELSE $2 END,username=COALESCE($3,username),online=true,last_seen_at=now(),updated_at=now() WHERE id=$1 RETURNING *`, [req.zoolUser!.id, b.data.displayName, b.data.username ?? null]);
    res.json({ user: userJson(r.rows[0]) });
  } catch (e: any) { if (e.code === '23505') return res.status(409).json({ error: 'username_taken' }); throw e; }
});
app.get('/api/me', requireAuth, async (req, res) => { const r = await pool.query(`SELECT * FROM users WHERE id=$1`, [req.zoolUser!.id]); res.json({ user: userJson(r.rows[0]) }); });
app.put('/api/me', requireAuth, async (req, res) => {
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
app.post('/api/devices/fcm', requireAuth, async (req, res) => {
  const b = z.object({ token: z.string().min(10).max(4096), platform: z.literal('android'), deviceName: z.string().trim().max(100).optional() }).parse(req.body);
  await pool.query(`INSERT INTO devices(user_id,fcm_token,platform,device_name,auth_session_id,last_seen_at,revoked_at) VALUES($1,$2,$3,$4,$5,now(),NULL)
    ON CONFLICT(fcm_token) DO UPDATE SET user_id=EXCLUDED.user_id,device_name=EXCLUDED.device_name,auth_session_id=EXCLUDED.auth_session_id,last_seen_at=now(),revoked_at=NULL,updated_at=now()`, [req.zoolUser!.id, b.token, b.platform, b.deviceName ?? null, req.zoolUser!.sessionId ?? null]);
  res.json({ ok: true });
});
app.get('/api/devices', requireAuth, async (req, res) => {
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
});
app.post('/api/contacts/discover', discoveryRateLimit, requireAuth, async (req, res) => {
  const b = z.object({ phoneHashes: z.array(z.string().regex(/^[a-f0-9]{64}$/)).max(10000) }).parse(req.body);
  if (!b.phoneHashes.length) return res.json({ contacts: [] });
  const r = await pool.query(`SELECT u.id,u.phone_discovery_hash,u.display_name,u.username,
    CASE WHEN COALESCE(p.profile_photo_visibility,'contacts')='everyone' OR (COALESCE(p.profile_photo_visibility,'contacts')='contacts' AND EXISTS(SELECT 1 FROM chat_members a JOIN chat_members b ON b.chat_id=a.chat_id WHERE a.user_id=$2 AND b.user_id=u.id)) THEN COALESCE('/api/media/'||u.profile_photo_media_id::text,u.photo_url) ELSE NULL END photo_url
    FROM users u LEFT JOIN user_privacy_settings p ON p.user_id=u.id
    WHERE u.phone_discovery_hash=ANY($1::text[]) AND u.id<>$2 AND NOT EXISTS(SELECT 1 FROM blocks bl WHERE (bl.blocker_id=$2 AND bl.blocked_id=u.id) OR (bl.blocker_id=u.id AND bl.blocked_id=$2))`, [b.phoneHashes, req.zoolUser!.id]);
  res.json({ contacts: r.rows.map(x => ({ userId: x.id, discoveryHash: x.phone_discovery_hash, displayName: x.display_name, username: x.username, photoUrl: x.photo_url })) });
});
app.get('/api/users/search', requireAuth, async (req, res) => {
  const q = String(req.query.q ?? '').trim();
  if (q.length < 3 || q.length > 80) return res.json({ users: [] });
  const normalizedPhone = /^\+[1-9]\d{5,14}$/.test(q) ? q : null;
  const username = q.replace(/^@/, '').toLowerCase();
  const r = await pool.query(`SELECT u.id,u.phone_e164,u.display_name,u.username,
    CASE WHEN COALESCE(p.profile_photo_visibility,'contacts')='everyone' OR (COALESCE(p.profile_photo_visibility,'contacts')='contacts' AND EXISTS(SELECT 1 FROM chat_members a JOIN chat_members b ON b.chat_id=a.chat_id WHERE a.user_id=$1 AND b.user_id=u.id)) THEN COALESCE('/api/media/'||u.profile_photo_media_id::text,u.photo_url) ELSE NULL END photo_url
    FROM users u LEFT JOIN user_privacy_settings p ON p.user_id=u.id
    WHERE u.id<>$1 AND ((u.username IS NOT NULL AND lower(u.username) LIKE lower($2) || '%') OR ($3::text IS NOT NULL AND u.phone_e164=$3))
      AND NOT EXISTS(SELECT 1 FROM blocks bl WHERE (bl.blocker_id=$1 AND bl.blocked_id=u.id) OR (bl.blocker_id=u.id AND bl.blocked_id=$1))
    ORDER BY CASE WHEN lower(u.username)=lower($2) THEN 0 ELSE 1 END, lower(u.username) ASC NULLS LAST LIMIT 20`, [req.zoolUser!.id, username, normalizedPhone]);
  res.json({ users: r.rows.map(x => ({ userId:x.id, phoneE164: normalizedPhone && x.phone_e164===normalizedPhone ? normalizedPhone : null, displayName:x.display_name, username:x.username, photoUrl:x.photo_url })) });
});

app.put('/api/e2ee/key', requireAuth, async (req,res)=>{
  const b=z.object({
    algorithm:z.literal('P256-ECDH').default('P256-ECDH'),
    publicKeyB64:z.string().min(80).max(4096),
    fingerprintSha256:z.string().regex(/^[A-Fa-f0-9:]{32,200}$/),
  }).parse(req.body);
  await pool.query(`INSERT INTO user_crypto_keys(user_id,algorithm,public_key_b64,fingerprint_sha256)
    VALUES($1,$2,$3,$4)
    ON CONFLICT(user_id) DO UPDATE SET algorithm=EXCLUDED.algorithm,public_key_b64=EXCLUDED.public_key_b64,fingerprint_sha256=EXCLUDED.fingerprint_sha256,updated_at=now()`,
    [req.zoolUser!.id,b.algorithm,b.publicKeyB64,b.fingerprintSha256]);
  res.json({ok:true,algorithm:b.algorithm,fingerprintSha256:b.fingerprintSha256});
});
app.get('/api/users/:id/e2ee-key', requireAuth, async (req,res)=>{
  const id=z.string().uuid().parse(req.params.id);
  if(id===req.zoolUser!.id) return res.status(400).json({error:'self_key_not_required'});
  const r=await pool.query(`SELECT u.id,u.display_name,k.algorithm,k.public_key_b64,k.fingerprint_sha256,k.updated_at
    FROM users u LEFT JOIN user_crypto_keys k ON k.user_id=u.id WHERE u.id=$1`,[id]);
  if(!r.rowCount) return res.status(404).json({error:'user_not_found'});
  if(!r.rows[0].public_key_b64) return res.status(409).json({error:'peer_e2ee_key_missing'});
  const x=r.rows[0];res.json({userId:x.id,displayName:x.display_name,algorithm:x.algorithm,publicKeyB64:x.public_key_b64,fingerprintSha256:x.fingerprint_sha256,updatedAt:new Date(x.updated_at).toISOString()});
});


app.post('/api/media', mediaRateLimit, requireAuth, upload.single('file'), async (req, res) => {
  if (!req.file) return res.status(400).json({ error: 'file_required' });
  const storageName = req.file.filename || `${randomUUID()}${path.extname(req.file.originalname).slice(0, 12)}`;
  if (r2Enabled) {
    if (!r2 || !r2Bucket || !req.file.buffer) return res.status(503).json({ error: 'media_storage_unavailable' });
    await r2.send(new PutObjectCommand({ Bucket: r2Bucket, Key: storageName, Body: req.file.buffer, ContentType: req.file.mimetype }));
  }
  const r = await pool.query(`INSERT INTO media_assets(owner_id,storage_name,original_name,mime_type,size_bytes) VALUES($1,$2,$3,$4,$5) RETURNING *`, [req.zoolUser!.id, storageName, req.file.originalname, req.file.mimetype, req.file.size]);
  const m = r.rows[0];
  res.status(201).json({ media: { id: m.id, url: `/api/media/${m.id}`, mime: m.mime_type, name: m.original_name, size: Number(m.size_bytes) } });
});
app.get('/api/media/:id', requireAuth, async (req, res) => {
  const r = await pool.query(`SELECT ma.* FROM media_assets ma WHERE ma.id=$1 AND (
  ma.owner_id=$2 OR
  EXISTS(SELECT 1 FROM messages m JOIN chat_members cm ON cm.chat_id=m.chat_id WHERE m.media_id=ma.id AND cm.user_id=$2) OR
  EXISTS(SELECT 1 FROM users u LEFT JOIN user_privacy_settings p ON p.user_id=u.id WHERE u.profile_photo_media_id=ma.id AND u.account_status='active'
    AND NOT EXISTS(SELECT 1 FROM blocks bl WHERE (bl.blocker_id=$2 AND bl.blocked_id=u.id) OR (bl.blocker_id=u.id AND bl.blocked_id=$2))
    AND (COALESCE(p.profile_photo_visibility,'contacts')='everyone' OR (COALESCE(p.profile_photo_visibility,'contacts')='contacts' AND EXISTS(SELECT 1 FROM chat_members a JOIN chat_members b ON b.chat_id=a.chat_id WHERE a.user_id=$2 AND b.user_id=u.id))))
)`, [req.params.id, req.zoolUser!.id]);
  if (!r.rowCount) return res.status(404).json({ error: 'media_not_found' });
  const m = r.rows[0];
  res.type(m.mime_type);
  res.setHeader('Cache-Control','private, max-age=86400');
  res.setHeader('Content-Disposition', `inline; filename*=UTF-8''${encodeURIComponent(m.original_name)}`);
  if (r2Enabled) {
    if (!r2 || !r2Bucket) return res.status(503).json({ error: 'media_storage_unavailable' });
    const object = await r2.send(new GetObjectCommand({ Bucket: r2Bucket, Key: m.storage_name }));
    const body: any = object.Body;
    if (!body || typeof body.pipe !== 'function') return res.status(404).json({ error: 'media_not_found' });
    body.on('error', (err: unknown) => { console.error('r2_stream_failed', err); if (!res.headersSent) res.status(502).end(); else res.destroy(); });
    body.pipe(res);
    return;
  }
  res.sendFile(path.join(mediaDir, m.storage_name));
});

app.get('/api/chats', requireAuth, async (req, res) => {
  const uid = req.zoolUser!.id;
  const r = await pool.query(`
    SELECT c.id,c.type,c.title,dc.user_a,dc.user_b,
      (SELECT CASE WHEN m.encrypted THEN NULL WHEN m.type='text' THEN m.body WHEN m.type='call' THEN '📞 مكالمة' WHEN m.type='image' THEN '📷 صورة' WHEN m.type='video' THEN '🎥 فيديو' WHEN m.type='audio' THEN '🎤 رسالة صوتية' WHEN m.type='sticker' THEN '🖼️ ملصق' WHEN m.type='document' THEN '📄 '||COALESCE(ma.original_name,'مستند') ELSE '📎 مرفق' END FROM messages m LEFT JOIN media_assets ma ON ma.id=m.media_id WHERE m.chat_id=c.id AND m.deleted_at IS NULL ORDER BY m.created_at DESC LIMIT 1) last_message,
      (SELECT created_at FROM messages m WHERE m.chat_id=c.id AND m.deleted_at IS NULL ORDER BY created_at DESC LIMIT 1) last_message_at,
      (SELECT count(*)::int FROM messages m WHERE m.chat_id=c.id AND m.sender_id<>$1 AND m.deleted_at IS NULL AND (cm.last_read_at IS NULL OR m.created_at>cm.last_read_at)) unread
    FROM chats c JOIN chat_members cm ON cm.chat_id=c.id AND cm.user_id=$1 LEFT JOIN direct_chats dc ON dc.chat_id=c.id
    ORDER BY last_message_at DESC NULLS LAST,c.created_at DESC`, [uid]);
  const out = [];
  for (const row of r.rows) {
    let title = row.title ?? 'محادثة', peerUserId = null;
    if (row.type === 'direct') { peerUserId = row.user_a === uid ? row.user_b : row.user_a; const p = await pool.query(`SELECT display_name FROM users WHERE id=$1`, [peerUserId]); title = p.rows[0]?.display_name ?? title; }
    out.push({ id: row.id, type: row.type, title, peerUserId, lastMessage: row.last_message, lastMessageAt: row.last_message_at ? new Date(row.last_message_at).toISOString() : null, unread: Number(row.unread ?? 0) });
  }
  res.json({ chats: out });
});
app.post('/api/chats/direct', requireAuth, async (req, res) => {
  const peer = z.object({ userId: z.string().uuid() }).parse(req.body).userId, uid = req.zoolUser!.id;
  if (peer === uid) return res.status(400).json({ error: 'cannot_chat_self' });
  const peerExists = await pool.query(`SELECT 1 FROM users WHERE id=$1 AND account_status='active'`, [peer]);
  if (!peerExists.rowCount) return res.status(404).json({ error: 'peer_not_found' });
  const blocked = await pool.query(`SELECT 1 FROM blocks WHERE (blocker_id=$1 AND blocked_id=$2) OR (blocker_id=$2 AND blocked_id=$1)`, [uid, peer]);
  if (blocked.rowCount) return res.status(403).json({ error: 'blocked' });
  // ensureDirectChat rechecks inside a transaction, so rapid/double taps cannot race into
  // duplicate direct chats or a unique-constraint 500 response.
  const chatId = await ensureDirectChat(uid, peer);
  res.json({ chat: { id: chatId, type: 'direct', peerUserId: peer } });
});
app.get('/api/chats/:id/messages', requireAuth, async (req, res) => {
  const id = String(req.params.id), uid = req.zoolUser!.id; if (!(await membership(id, uid))) return res.status(403).json({ error: 'not_member' });
  const limit = Math.max(1, Math.min(Number(req.query.limit ?? 50), 100));
  const beforeRaw = typeof req.query.before === 'string' ? req.query.before : undefined;
  const before = beforeRaw ? new Date(beforeRaw) : null;
  if (before && Number.isNaN(before.getTime())) return res.status(400).json({ error:'invalid_cursor' });
  const r = await pool.query(`
    SELECT m.*,ma.id media_id,ma.mime_type media_mime,ma.original_name media_name,ma.size_bytes media_size,
      CASE WHEN m.sender_id=$2 THEN CASE
        WHEN EXISTS(SELECT 1 FROM message_receipts mr WHERE mr.message_id=m.id) AND NOT EXISTS(SELECT 1 FROM message_receipts mr WHERE mr.message_id=m.id AND mr.read_at IS NULL) THEN 'read'
        WHEN EXISTS(SELECT 1 FROM message_receipts mr WHERE mr.message_id=m.id) AND NOT EXISTS(SELECT 1 FROM message_receipts mr WHERE mr.message_id=m.id AND mr.delivered_at IS NULL) THEN 'delivered'
        ELSE 'sent' END ELSE 'received' END status
    FROM messages m LEFT JOIN media_assets ma ON ma.id=m.media_id
    WHERE m.chat_id=$1 AND ($3::timestamptz IS NULL OR m.created_at<$3)
    ORDER BY m.created_at DESC,m.id DESC LIMIT $4`, [id, uid, before ? before.toISOString() : null, limit + 1]);
  const hasMore = r.rows.length > limit;
  const pageRows = r.rows.slice(0, limit).reverse();
  const reactionMap = await messageReactionMap(pageRows.map(x=>String(x.id)), uid);
  const messages = pageRows.map(row=>messageJson({...row,reactions:reactionMap.get(String(row.id))??[]}));
  const nextCursor = hasMore && pageRows.length ? new Date(pageRows[0].created_at).toISOString() : null;
  res.json({ messages, hasMore, nextCursor });
});
app.get('/api/chats/:id/search', requireAuth, async (req, res) => {
  const id=String(req.params.id),uid=req.zoolUser!.id;if(!(await membership(id,uid)))return res.status(403).json({error:'not_member'});
  const q=String(req.query.q??'').trim();if(q.length<2)return res.json({messages:[]});
  const r=await pool.query(`SELECT m.*,NULL::uuid media_id,NULL::text media_mime,NULL::text media_name,NULL::bigint media_size,'sent' status FROM messages m WHERE m.chat_id=$1 AND m.deleted_at IS NULL AND m.type='text' AND m.encrypted=false AND m.body ILIKE $2 ORDER BY m.created_at DESC LIMIT 50`,[id,`%${q.replace(/[%_]/g,'\\$&')}%`]);
  res.json({messages:r.rows.map(messageJson)});
});

app.post('/api/chats/:id/messages', messageRateLimit, requireAuth, async (req, res) => {
  const chatId = String(req.params.id), uid = req.zoolUser!.id; if (!(await membership(chatId, uid))) return res.status(403).json({ error: 'not_member' });
  const b = z.object({ clientId: z.string().min(8).max(100), type: z.enum(['text','image','video','audio','document','sticker']).default('text'), body: z.string().max(20000).default(''), mediaId: z.string().uuid().optional(), encrypted:z.boolean().default(false), cryptoVersion:z.number().int().min(0).max(10).default(0) }).parse(req.body);
  if (b.type === 'text' && !b.body.trim()) return res.status(400).json({ error: 'text_required' });
  if (b.type !== 'text' && !b.mediaId) return res.status(400).json({ error: 'media_required' });
  if (b.mediaId) { const owned = await pool.query(`SELECT 1 FROM media_assets WHERE id=$1 AND owner_id=$2`, [b.mediaId, uid]); if (!owned.rowCount) return res.status(403).json({ error: 'media_not_owned' }); }
  const sendPolicy = await pool.query(`SELECT c.type,COALESCE(gs.only_admins_send,false) only_admins_send,cm.role FROM chats c JOIN chat_members cm ON cm.chat_id=c.id AND cm.user_id=$2 LEFT JOIN group_settings gs ON gs.chat_id=c.id WHERE c.id=$1`,[chatId,uid]);
  if(sendPolicy.rows[0]?.type==='group' && sendPolicy.rows[0]?.only_admins_send && !['owner','admin'].includes(sendPolicy.rows[0]?.role)) return res.status(403).json({error:'admins_only_send'});
  const peers = await pool.query(`SELECT user_id FROM chat_members WHERE chat_id=$1 AND user_id<>$2`, [chatId, uid]);
  const peerIds = peers.rows.map(x => x.user_id);
  const blocked = await pool.query(`SELECT 1 FROM blocks WHERE (blocker_id=$1 AND blocked_id=ANY($2::uuid[])) OR (blocked_id=$1 AND blocker_id=ANY($2::uuid[]))`, [uid, peerIds]); if (blocked.rowCount) return res.status(403).json({ error: 'blocked' });
  const r = await tx(async c => {
    const inserted = await c.query(`INSERT INTO messages(chat_id,sender_id,client_id,type,body,media_id,encrypted,crypto_version) VALUES($1,$2,$3,$4,$5,$6,$7,$8) ON CONFLICT(sender_id,client_id) DO UPDATE SET client_id=EXCLUDED.client_id RETURNING *`, [chatId, uid, b.clientId, b.type, b.body, b.mediaId ?? null, b.encrypted, b.cryptoVersion]);
    const msg = inserted.rows[0];
    for (const peerId of peerIds) await c.query(`INSERT INTO message_receipts(message_id,user_id) VALUES($1,$2) ON CONFLICT DO NOTHING`, [msg.id, peerId]);
    if (b.mediaId) { const ma = await c.query(`SELECT id media_id,mime_type media_mime,original_name media_name,size_bytes media_size FROM media_assets WHERE id=$1`, [b.mediaId]); Object.assign(msg, ma.rows[0]); }
    msg.status = 'sent'; return msg;
  });
  const msg = messageJson(r);
  for (const p of peers.rows) emitTo(p.user_id, { type: 'message.new', data: { ...msg, senderName: req.zoolUser!.displayName } });
  const pushTargets=await fcmTargets(peerIds);
  const detailedPreview=!b.encrypted ? (b.type==='text' ? b.body.slice(0,180) : `📎 ${r.media_name ?? b.type}`) : 'رسالة جديدة';
  const detailedTokens=pushMessagePreviews&&!b.encrypted ? pushTargets.filter(x=>x.messagePreviews).map(x=>x.token) : [];
  const genericTokens=pushTargets.filter(x=>!detailedTokens.includes(x.token)).map(x=>x.token);
  const pushBase={type:'message_new',chatId,chatType:String(sendPolicy.rows[0]?.type??'direct'),senderName:req.zoolUser!.displayName};
  if(detailedTokens.length)await sendData(detailedTokens,{...pushBase,body:detailedPreview});
  if(genericTokens.length)await sendData(genericTokens,{...pushBase,body:'رسالة جديدة'});
  res.status(201).json({ message: msg });
});

app.put('/api/chats/:id/messages/:messageId', requireAuth, async (req,res)=>{
  const chatId=String(req.params.id),uid=req.zoolUser!.id;if(!(await membership(chatId,uid)))return res.status(403).json({error:'not_member'});
  const b=z.object({body:z.string().min(1).max(20000),encrypted:z.boolean().default(false),cryptoVersion:z.number().int().min(0).max(10).default(0)}).parse(req.body);
  const current=await pool.query(`SELECT * FROM messages WHERE id=$1 AND chat_id=$2 AND sender_id=$3 AND type='text' AND deleted_at IS NULL`,[req.params.messageId,chatId,uid]);
  if(!current.rowCount)return res.status(404).json({error:'message_not_found'});
  if(Date.now()-new Date(current.rows[0].created_at).getTime()>15*60*1000)return res.status(409).json({error:'edit_window_expired'});
  const r=await pool.query(`UPDATE messages SET body=$4,encrypted=$5,crypto_version=$6,edited_at=now() WHERE id=$1 AND chat_id=$2 AND sender_id=$3 RETURNING *`,[req.params.messageId,chatId,uid,b.body,b.encrypted,b.cryptoVersion]);
  const payload=messageJson({...r.rows[0],status:'sent',reactions:[]});
  const peers=await pool.query(`SELECT user_id FROM chat_members WHERE chat_id=$1 AND user_id<>$2`,[chatId,uid]);for(const p of peers.rows)emitTo(p.user_id,{type:'message.edited',data:payload});
  const editTokens=await fcmTokens(peers.rows.map(x=>x.user_id)); await sendData(editTokens,{type:'chat_sync',chatId},false);
  res.json({message:payload});
});
app.delete('/api/chats/:id/messages/:messageId', requireAuth, async (req,res)=>{
  const chatId=String(req.params.id),uid=req.zoolUser!.id;if(!(await membership(chatId,uid)))return res.status(403).json({error:'not_member'});
  const current=await pool.query(`SELECT m.*,c.type chat_type,cm.role FROM messages m JOIN chats c ON c.id=m.chat_id JOIN chat_members cm ON cm.chat_id=m.chat_id AND cm.user_id=$3 WHERE m.id=$1 AND m.chat_id=$2 AND m.deleted_at IS NULL`,[req.params.messageId,chatId,uid]);
  if(!current.rowCount)return res.status(404).json({error:'message_not_found'});
  const m=current.rows[0];
  const adminCanDelete=m.chat_type==='group'&&['owner','admin'].includes(String(m.role));
  if(m.sender_id!==uid&&!adminCanDelete)return res.status(403).json({error:'delete_not_allowed'});
  if(Date.now()-new Date(m.created_at).getTime()>2*24*60*60*1000)return res.status(409).json({error:'delete_window_expired'});
  const r=await pool.query(`UPDATE messages SET body='',deleted_at=now() WHERE id=$1 AND chat_id=$2 RETURNING *`,[req.params.messageId,chatId]);
  const peers=await pool.query(`SELECT user_id FROM chat_members WHERE chat_id=$1 AND user_id<>$2`,[chatId,uid]);for(const p of peers.rows)emitTo(p.user_id,{type:'message.deleted',data:{chatId,messageId:req.params.messageId,deletedBy:uid,admin:m.sender_id!==uid}});
  const deleteTokens=await fcmTokens(peers.rows.map(x=>x.user_id)); await sendData(deleteTokens,{type:'chat_sync',chatId},false);
  res.json({ok:true,message:messageJson({...r.rows[0],status:'sent'})});
});

app.put('/api/chats/:id/messages/:messageId/reaction', requireAuth, async (req,res)=>{
  const chatId=String(req.params.id),uid=req.zoolUser!.id;if(!(await membership(chatId,uid)))return res.status(403).json({error:'not_member'});
  const emoji=z.object({emoji:z.string().trim().min(1).max(16)}).parse(req.body).emoji;
  const exists=await pool.query(`SELECT 1 FROM messages WHERE id=$1 AND chat_id=$2 AND deleted_at IS NULL`,[req.params.messageId,chatId]);if(!exists.rowCount)return res.status(404).json({error:'message_not_found'});
  await pool.query(`INSERT INTO message_reactions(message_id,user_id,emoji) VALUES($1,$2,$3) ON CONFLICT(message_id,user_id) DO UPDATE SET emoji=EXCLUDED.emoji,created_at=now()`,[req.params.messageId,uid,emoji]);
  const reactions=(await messageReactionMap([String(req.params.messageId)],uid)).get(String(req.params.messageId))??[];
  const members=await pool.query(`SELECT user_id FROM chat_members WHERE chat_id=$1`,[chatId]);for(const p of members.rows)emitTo(p.user_id,{type:'message.reaction',data:{chatId,messageId:req.params.messageId}});
  const tokens=await fcmTokens(members.rows.filter(x=>x.user_id!==uid).map(x=>x.user_id));await sendData(tokens,{type:'chat_sync',chatId},false);
  res.json({ok:true,reactions});
});
app.delete('/api/chats/:id/messages/:messageId/reaction', requireAuth, async (req,res)=>{
  const chatId=String(req.params.id),uid=req.zoolUser!.id;if(!(await membership(chatId,uid)))return res.status(403).json({error:'not_member'});
  await pool.query(`DELETE FROM message_reactions WHERE message_id=$1 AND user_id=$2`,[req.params.messageId,uid]);
  const reactions=(await messageReactionMap([String(req.params.messageId)],uid)).get(String(req.params.messageId))??[];
  const members=await pool.query(`SELECT user_id FROM chat_members WHERE chat_id=$1`,[chatId]);for(const p of members.rows)emitTo(p.user_id,{type:'message.reaction',data:{chatId,messageId:req.params.messageId}});
  const tokens=await fcmTokens(members.rows.filter(x=>x.user_id!==uid).map(x=>x.user_id));await sendData(tokens,{type:'chat_sync',chatId},false);
  res.json({ok:true,reactions});
});

app.post('/api/chats/:id/delivered', requireAuth, async (req, res) => {
  const uid = req.zoolUser!.id, chatId = String(req.params.id); if (!(await membership(chatId, uid))) return res.status(403).json({ error: 'not_member' });
  const { messageId } = z.object({ messageId: z.string().uuid() }).parse(req.body);
  const r = await pool.query(`UPDATE message_receipts mr SET delivered_at=COALESCE(delivered_at,now()) FROM messages m WHERE mr.message_id=m.id AND mr.message_id=$1 AND mr.user_id=$2 AND m.chat_id=$3 RETURNING m.sender_id`, [messageId, uid, chatId]);
  if (r.rowCount) emitTo(r.rows[0].sender_id, { type: 'receipt.update', data: { messageId, chatId, status: 'delivered' } });
  res.json({ ok: true });
});
app.post('/api/chats/:id/read', requireAuth, async (req, res) => {
  const uid = req.zoolUser!.id, chatId = String(req.params.id); if (!(await membership(chatId, uid))) return res.status(403).json({ error: 'not_member' });
  const { messageId } = z.object({ messageId: z.string().uuid() }).parse(req.body);
  const target = await pool.query(`SELECT created_at FROM messages WHERE id=$1 AND chat_id=$2`, [messageId, chatId]); if (!target.rowCount) return res.status(404).json({ error: 'message_not_found' });
  const privacy = await pool.query(`SELECT read_receipts FROM user_privacy_settings WHERE user_id=$1`, [uid]);
  const shareRead = privacy.rows[0]?.read_receipts !== false;
  const updated = shareRead ? await pool.query(`UPDATE message_receipts mr SET delivered_at=COALESCE(delivered_at,now()),read_at=COALESCE(read_at,now()) FROM messages m WHERE mr.message_id=m.id AND mr.user_id=$1 AND m.chat_id=$2 AND m.created_at<=$3 RETURNING m.id,m.sender_id`, [uid, chatId, target.rows[0].created_at]) : { rows: [] };
  await pool.query(`UPDATE chat_members SET last_read_at=GREATEST(last_read_at,$3) WHERE chat_id=$1 AND user_id=$2`, [chatId, uid, target.rows[0].created_at]);
  for (const row of updated.rows) emitTo(row.sender_id, { type: 'receipt.update', data: { messageId: row.id, chatId, status: 'read' } });
  res.json({ ok: true });
});


app.get('/api/users/:id/profile', requireAuth, async(req,res)=>{
  const uid=req.zoolUser!.id,id=z.string().uuid().parse(req.params.id);
  const shared=await pool.query(`SELECT 1 FROM chat_members a JOIN chat_members b ON b.chat_id=a.chat_id WHERE a.user_id=$1 AND b.user_id=$2 LIMIT 1`,[uid,id]);
  const blocked=await pool.query(`SELECT 1 FROM blocks WHERE (blocker_id=$1 AND blocked_id=$2) OR (blocker_id=$2 AND blocked_id=$1)`,[uid,id]);
  if((uid!==id&&!shared.rowCount)||blocked.rowCount)return res.status(404).json({error:'profile_unavailable'});
  const r=await pool.query(`SELECT u.id,u.display_name,u.username,u.about,u.photo_url,u.profile_photo_media_id,u.online,u.last_seen_at,COALESCE(p.show_online,true) show_online,COALESCE(p.show_last_seen,true) show_last_seen,COALESCE(p.profile_photo_visibility,'contacts') profile_photo_visibility,COALESCE(p.about_visibility,'contacts') about_visibility FROM users u LEFT JOIN user_privacy_settings p ON p.user_id=u.id WHERE u.id=$1 AND u.account_status='active'`,[id]);
  if(!r.rowCount)return res.status(404).json({error:'profile_unavailable'});
  const u=r.rows[0];
  const related=uid===id||Boolean(shared.rowCount);
  const photoVisible=uid===id||u.profile_photo_visibility==='everyone'||(u.profile_photo_visibility==='contacts'&&related);
  const aboutVisible=uid===id||u.about_visibility==='everyone'||(u.about_visibility==='contacts'&&related);
  const photoUrl=photoVisible?(u.profile_photo_media_id?`/api/media/${u.profile_photo_media_id}`:(u.photo_url??null)):null;
  const groups=await pool.query(`SELECT c.id,c.title FROM chats c JOIN chat_members a ON a.chat_id=c.id AND a.user_id=$1 JOIN chat_members b ON b.chat_id=c.id AND b.user_id=$2 WHERE c.type='group'`,[uid,id]);
  res.json({profile:{id:u.id,displayName:u.display_name,username:u.username,about:aboutVisible?u.about:null,photoUrl,online:u.show_online?u.online:null,lastSeenAt:u.show_last_seen?u.last_seen_at:null,groupsInCommon:groups.rows}});
});

app.get('/api/users/:id/presence', requireAuth, async (req,res)=>{
  const uid=req.zoolUser!.id; const peerId=req.params.id;
  if(peerId!==uid){
    const shared=await pool.query(`SELECT 1 FROM chat_members me JOIN chat_members peer ON peer.chat_id=me.chat_id WHERE me.user_id=$1 AND peer.user_id=$2 LIMIT 1`,[uid,peerId]);
    if(!shared.rowCount) return res.status(403).json({error:'presence_not_available'});
  }
  const r=await pool.query(`SELECT u.online,u.last_seen_at,COALESCE(p.show_online,true) show_online,COALESCE(p.show_last_seen,true) show_last_seen FROM users u LEFT JOIN user_privacy_settings p ON p.user_id=u.id WHERE u.id=$1`,[peerId]);
  if(!r.rowCount) return res.status(404).json({error:'user_not_found'});
  const x=r.rows[0];
  res.json({presence:{online:x.show_online?Boolean(x.online):null,lastSeenAt:x.show_last_seen&&x.last_seen_at?new Date(x.last_seen_at).toISOString():null}});
});

app.post('/api/chats/:id/typing', typingRateLimit, requireAuth, async (req,res)=>{
  const chatId=String(req.params.id),uid=req.zoolUser!.id;
  if(!(await membership(chatId,uid))) return res.status(403).json({error:'not_member'});
  const b=z.object({active:z.boolean()}).parse(req.body);
  const peers=await pool.query(`SELECT user_id FROM chat_members WHERE chat_id=$1 AND user_id<>$2`,[chatId,uid]);
  for(const p of peers.rows) emitTo(p.user_id,{type:'typing.update',data:{chatId,userId:uid,displayName:req.zoolUser!.displayName,active:b.active}});
  res.json({ok:true});
});


app.get('/api/privacy', requireAuth, async (req,res)=>{
  const r=await pool.query(`INSERT INTO user_privacy_settings(user_id) VALUES($1) ON CONFLICT(user_id) DO UPDATE SET user_id=EXCLUDED.user_id RETURNING *`,[req.zoolUser!.id]);
  const x=r.rows[0];res.json({privacy:{readReceipts:x.read_receipts,showLastSeen:x.show_last_seen,showOnline:x.show_online,profilePhotoVisibility:x.profile_photo_visibility,aboutVisibility:x.about_visibility,messagePreviews:x.message_previews}});
});
app.put('/api/privacy', requireAuth, async (req,res)=>{
  const visibility=z.enum(['everyone','contacts','nobody']);
  const b=z.object({readReceipts:z.boolean().optional(),showLastSeen:z.boolean().optional(),showOnline:z.boolean().optional(),profilePhotoVisibility:visibility.optional(),aboutVisibility:visibility.optional(),messagePreviews:z.boolean().optional()}).parse(req.body);
  const r=await pool.query(`INSERT INTO user_privacy_settings(user_id,read_receipts,show_last_seen,show_online,profile_photo_visibility,about_visibility,message_previews) VALUES($1,COALESCE($2,true),COALESCE($3,true),COALESCE($4,true),COALESCE($5,'contacts'),COALESCE($6,'contacts'),COALESCE($7,true)) ON CONFLICT(user_id) DO UPDATE SET read_receipts=COALESCE($2,user_privacy_settings.read_receipts),show_last_seen=COALESCE($3,user_privacy_settings.show_last_seen),show_online=COALESCE($4,user_privacy_settings.show_online),profile_photo_visibility=COALESCE($5,user_privacy_settings.profile_photo_visibility),about_visibility=COALESCE($6,user_privacy_settings.about_visibility),message_previews=COALESCE($7,user_privacy_settings.message_previews),updated_at=now() RETURNING *`,[req.zoolUser!.id,b.readReceipts??null,b.showLastSeen??null,b.showOnline??null,b.profilePhotoVisibility??null,b.aboutVisibility??null,b.messagePreviews??null]);
  const x=r.rows[0];res.json({privacy:{readReceipts:x.read_receipts,showLastSeen:x.show_last_seen,showOnline:x.show_online,profilePhotoVisibility:x.profile_photo_visibility,aboutVisibility:x.about_visibility,messagePreviews:x.message_previews}});
});
app.get('/api/account/export', requireAuth, async (req,res)=>{
  const uid=req.zoolUser!.id;const user=(await pool.query(`SELECT id,phone_e164,username,display_name,about,created_at,updated_at FROM users WHERE id=$1`,[uid])).rows[0];
  const memberships=await pool.query(`SELECT chat_id,joined_at,last_read_at FROM chat_members WHERE user_id=$1`,[uid]);
  const sent=await pool.query(`SELECT id,chat_id,type,body,encrypted,crypto_version,created_at,edited_at,deleted_at FROM messages WHERE sender_id=$1 ORDER BY created_at`,[uid]);
  res.setHeader('Content-Disposition','attachment; filename="zoolchat-export.json"');res.json({exportedAt:new Date().toISOString(),user,chats:memberships.rows,messages:sent.rows});
});
app.delete('/api/account', requireAuth, async (req,res)=>{
  const uid=req.zoolUser!.id;const firebaseUid=req.zoolUser!.firebaseUid;
  if(firebaseUid && !firebaseUid.startsWith('dev:')) await deleteFirebaseUser(firebaseUid);
  await pool.query(`DELETE FROM users WHERE id=$1`,[uid]);res.json({ok:true});
});

app.post('/api/users/:id/block', requireAuth, async (req, res) => { await pool.query(`INSERT INTO blocks(blocker_id,blocked_id) VALUES($1,$2) ON CONFLICT DO NOTHING`, [req.zoolUser!.id, req.params.id]); res.json({ ok: true }); });
app.delete('/api/users/:id/block', requireAuth, async (req, res) => { await pool.query(`DELETE FROM blocks WHERE blocker_id=$1 AND blocked_id=$2`, [req.zoolUser!.id, req.params.id]); res.json({ ok: true }); });
app.post('/api/reports', requireAuth, async (req, res) => { const b = z.object({ userId: z.string().uuid().optional(), chatId: z.string().uuid().optional(), reason: z.string().min(3).max(500) }).parse(req.body); await pool.query(`INSERT INTO reports(reporter_id,reported_user_id,chat_id,reason) VALUES($1,$2,$3,$4)`, [req.zoolUser!.id, b.userId ?? null, b.chatId ?? null, b.reason]); res.json({ ok: true }); });


// --- ZoolChat v0.30 groups -------------------------------------------------
app.post('/api/groups', requireAuth, async (req,res)=>{
  const b=z.object({title:z.string().trim().min(1).max(100),description:z.string().max(512).default(''),memberUserIds:z.array(z.string().uuid()).max(1023).default([])}).parse(req.body);
  const uid=req.zoolUser!.id; const members=[...new Set(b.memberUserIds.filter(x=>x!==uid))];
  if(members.length){ const valid=await pool.query(`SELECT id FROM users WHERE id=ANY($1::uuid[])`,[members]); if(valid.rowCount!==members.length)return res.status(400).json({error:'invalid_group_members'}); }
  const result=await tx(async c=>{
    const chat=(await c.query(`INSERT INTO chats(type,title) VALUES('group',$1) RETURNING id`,[b.title])).rows[0];
    await c.query(`INSERT INTO chat_members(chat_id,user_id,role) VALUES($1,$2,'owner')`,[chat.id,uid]);
    for(const m of members)await c.query(`INSERT INTO chat_members(chat_id,user_id,role) VALUES($1,$2,'member') ON CONFLICT DO NOTHING`,[chat.id,m]);
    let code=newMeetingCode().replaceAll('-','');
    await c.query(`INSERT INTO group_settings(chat_id,owner_id,description,invite_code) VALUES($1,$2,$3,$4)`,[chat.id,uid,b.description,code]);
    return {id:String(chat.id),code};
  });
  for(const m of members)emitTo(m,{type:'group.added',data:{chatId:result.id,title:b.title}});
  const tokens=await fcmTokens(members);await sendData(tokens,{type:'chat_sync',chatId:result.id},false);
  res.status(201).json({group:{id:result.id,type:'group',title:b.title,description:b.description,inviteCode:result.code}});
});
app.get('/api/groups/:id', requireAuth, async (req,res)=>{
  const chatId=String(req.params.id),uid=req.zoolUser!.id;if(!(await membership(chatId,uid)))return res.status(403).json({error:'not_member'});
  const g=await pool.query(`SELECT c.id,c.title,gs.*,cm.role my_role FROM chats c JOIN group_settings gs ON gs.chat_id=c.id JOIN chat_members cm ON cm.chat_id=c.id AND cm.user_id=$2 WHERE c.id=$1 AND c.type='group'`,[chatId,uid]);if(!g.rowCount)return res.status(404).json({error:'group_not_found'});
  const members=await pool.query(`SELECT u.id user_id,u.display_name,u.username,
  CASE WHEN COALESCE(p.profile_photo_visibility,'contacts')<>'nobody' AND NOT EXISTS(SELECT 1 FROM blocks bl WHERE (bl.blocker_id=$2 AND bl.blocked_id=u.id) OR (bl.blocker_id=u.id AND bl.blocked_id=$2)) THEN COALESCE('/api/media/'||u.profile_photo_media_id::text,u.photo_url) ELSE NULL END photo_url,
  cm.role,cm.joined_at FROM chat_members cm JOIN users u ON u.id=cm.user_id LEFT JOIN user_privacy_settings p ON p.user_id=u.id WHERE cm.chat_id=$1 ORDER BY CASE cm.role WHEN 'owner' THEN 0 WHEN 'admin' THEN 1 ELSE 2 END,u.display_name`,[chatId,uid]);
  const x=g.rows[0];res.json({group:{id:x.id,title:x.title,description:x.description,photoUrl:x.photo_url,inviteCode:x.invite_code,myRole:x.my_role,onlyAdminsSend:x.only_admins_send,onlyAdminsEdit:x.only_admins_edit,onlyAdminsAdd:x.only_admins_add,approveNewMembers:x.approve_new_members,disappearingSeconds:Number(x.disappearing_seconds||0),members:members.rows.map(m=>({userId:m.user_id,displayName:m.display_name,username:m.username,photoUrl:m.photo_url,role:m.role,joinedAt:new Date(m.joined_at).toISOString()}))}});
});
app.put('/api/groups/:id', requireAuth, async (req,res)=>{
  const chatId=String(req.params.id),uid=req.zoolUser!.id,role=await memberRole(chatId,uid);if(!role)return res.status(403).json({error:'not_member'});
  const current=await pool.query(`SELECT only_admins_edit FROM group_settings WHERE chat_id=$1`,[chatId]);if(!current.rowCount)return res.status(404).json({error:'group_not_found'});
  if(current.rows[0].only_admins_edit&&!['owner','admin'].includes(role))return res.status(403).json({error:'admins_only_edit'});
  const b=z.object({title:z.string().trim().min(1).max(100).optional(),description:z.string().max(512).optional(),onlyAdminsSend:z.boolean().optional(),onlyAdminsEdit:z.boolean().optional(),onlyAdminsAdd:z.boolean().optional(),approveNewMembers:z.boolean().optional(),disappearingSeconds:z.union([z.literal(0),z.literal(86400),z.literal(604800),z.literal(7776000)]).optional()}).parse(req.body);
  if((b.onlyAdminsSend!==undefined||b.onlyAdminsEdit!==undefined||b.onlyAdminsAdd!==undefined||b.approveNewMembers!==undefined||b.disappearingSeconds!==undefined)&&!['owner','admin'].includes(role))return res.status(403).json({error:'admin_required'});
  if(b.title!==undefined)await pool.query(`UPDATE chats SET title=$2 WHERE id=$1`,[chatId,b.title]);
  await pool.query(`UPDATE group_settings SET description=COALESCE($2,description),only_admins_send=COALESCE($3,only_admins_send),only_admins_edit=COALESCE($4,only_admins_edit),only_admins_add=COALESCE($5,only_admins_add),approve_new_members=COALESCE($6,approve_new_members),disappearing_seconds=COALESCE($7,disappearing_seconds),updated_at=now() WHERE chat_id=$1`,[chatId,b.description??null,b.onlyAdminsSend??null,b.onlyAdminsEdit??null,b.onlyAdminsAdd??null,b.approveNewMembers??null,b.disappearingSeconds??null]);
  res.json({ok:true});
});
app.post('/api/groups/:id/members', requireAuth, async (req,res)=>{
  const chatId=String(req.params.id),uid=req.zoolUser!.id,role=await memberRole(chatId,uid);if(!role)return res.status(403).json({error:'not_member'});
  const gs=await pool.query(`SELECT only_admins_add FROM group_settings WHERE chat_id=$1`,[chatId]);if(!gs.rowCount)return res.status(404).json({error:'group_not_found'});if(gs.rows[0].only_admins_add&&!['owner','admin'].includes(role))return res.status(403).json({error:'admins_only_add'});
  const ids=[...new Set(z.object({userIds:z.array(z.string().uuid()).min(1).max(100)}).parse(req.body).userIds)];
  const count=(await pool.query(`SELECT count(*)::int n FROM chat_members WHERE chat_id=$1`,[chatId])).rows[0].n;if(Number(count)+ids.length>1024)return res.status(409).json({error:'group_full'});
  for(const id of ids)await pool.query(`INSERT INTO chat_members(chat_id,user_id,role) SELECT $1,id,'member' FROM users WHERE id=$2 ON CONFLICT DO NOTHING`,[chatId,id]);res.json({ok:true});
});
app.delete('/api/groups/:id/members/:userId', requireAuth, async (req,res)=>{
  const chatId=String(req.params.id),uid=req.zoolUser!.id,target=String(req.params.userId),role=await memberRole(chatId,uid);if(!['owner','admin'].includes(role??''))return res.status(403).json({error:'admin_required'});
  const targetRole=await memberRole(chatId,target);if(targetRole==='owner')return res.status(409).json({error:'cannot_remove_owner'});if(role==='admin'&&targetRole==='admin')return res.status(403).json({error:'owner_required'});
  await pool.query(`DELETE FROM chat_members WHERE chat_id=$1 AND user_id=$2`,[chatId,target]);res.json({ok:true});
});
app.post('/api/groups/:id/members/:userId/role', requireAuth, async (req,res)=>{
  const chatId=String(req.params.id),uid=req.zoolUser!.id,target=String(req.params.userId),myRole=await memberRole(chatId,uid);if(myRole!=='owner')return res.status(403).json({error:'owner_required'});
  const role=z.object({role:z.enum(['admin','member'])}).parse(req.body).role;await pool.query(`UPDATE chat_members SET role=$3 WHERE chat_id=$1 AND user_id=$2 AND role<>'owner'`,[chatId,target,role]);res.json({ok:true});
});
app.post('/api/groups/:id/leave', requireAuth, async (req,res)=>{
  const chatId=String(req.params.id),uid=req.zoolUser!.id,role=await memberRole(chatId,uid);if(!role)return res.status(404).json({error:'not_member'});if(role==='owner')return res.status(409).json({error:'owner_must_transfer_or_delete'});await pool.query(`DELETE FROM chat_members WHERE chat_id=$1 AND user_id=$2`,[chatId,uid]);res.json({ok:true});
});
app.post('/api/groups/join/:code', requireAuth, async (req,res)=>{
  const code=String(req.params.code).trim().toLowerCase();const g=await pool.query(`SELECT chat_id,approve_new_members FROM group_settings WHERE lower(invite_code)=lower($1)`,[code]);if(!g.rowCount)return res.status(404).json({error:'invalid_invite'});const chatId=String(g.rows[0].chat_id),uid=req.zoolUser!.id;if(await membership(chatId,uid))return res.json({ok:true,chatId,status:'joined'});
  if(g.rows[0].approve_new_members){await pool.query(`INSERT INTO group_join_requests(chat_id,user_id,status) VALUES($1,$2,'pending') ON CONFLICT(chat_id,user_id) DO UPDATE SET status='pending',created_at=now(),decided_at=NULL,decided_by=NULL`,[chatId,uid]);return res.status(202).json({ok:true,chatId,status:'pending'});}await pool.query(`INSERT INTO chat_members(chat_id,user_id,role) VALUES($1,$2,'member') ON CONFLICT DO NOTHING`,[chatId,uid]);res.json({ok:true,chatId,status:'joined'});
});
app.get('/api/groups/:id/requests', requireAuth, async (req,res)=>{const chatId=String(req.params.id),uid=req.zoolUser!.id,role=await memberRole(chatId,uid);if(!['owner','admin'].includes(role??''))return res.status(403).json({error:'admin_required'});const r=await pool.query(`SELECT r.id,r.user_id,u.display_name,u.username,r.created_at FROM group_join_requests r JOIN users u ON u.id=r.user_id WHERE r.chat_id=$1 AND r.status='pending' ORDER BY r.created_at`,[chatId]);res.json({requests:r.rows.map(x=>({id:x.id,userId:x.user_id,displayName:x.display_name,username:x.username,createdAt:new Date(x.created_at).toISOString()}))});});
app.post('/api/groups/:id/requests/:requestId', requireAuth, async (req,res)=>{const chatId=String(req.params.id),uid=req.zoolUser!.id,role=await memberRole(chatId,uid);if(!['owner','admin'].includes(role??''))return res.status(403).json({error:'admin_required'});const decision=z.object({approve:z.boolean()}).parse(req.body).approve;const r=await pool.query(`UPDATE group_join_requests SET status=$4,decided_at=now(),decided_by=$3 WHERE id=$1 AND chat_id=$2 AND status='pending' RETURNING user_id`,[req.params.requestId,chatId,uid,decision?'approved':'denied']);if(!r.rowCount)return res.status(404).json({error:'request_not_found'});if(decision)await pool.query(`INSERT INTO chat_members(chat_id,user_id,role) VALUES($1,$2,'member') ON CONFLICT DO NOTHING`,[chatId,r.rows[0].user_id]);res.json({ok:true});});

// --- Zool Meet v0.30 --------------------------------------------------------
app.post('/api/meetings', requireAuth, async (req,res)=>{
  const b=z.object({title:z.string().trim().min(1).max(120).default('Zool Meet'),requireApproval:z.boolean().default(true),scheduledFor:z.string().datetime().optional()}).parse(req.body);const uid=req.zoolUser!.id;
  let code='';for(let i=0;i<8;i++){const candidate=newMeetingCode();const exists=await pool.query(`SELECT 1 FROM meetings WHERE code=$1`,[candidate]);if(!exists.rowCount){code=candidate;break;}}if(!code)return res.status(503).json({error:'meeting_code_unavailable'});
  const roomName=`meet_${randomUUID().replaceAll('-','')}`;const m=(await pool.query(`INSERT INTO meetings(room_name,code,host_id,title,require_approval,scheduled_for) VALUES($1,$2,$3,$4,$5,$6) RETURNING *`,[roomName,code,uid,b.title,b.requireApproval,b.scheduledFor?new Date(b.scheduledFor):null])).rows[0];await pool.query(`INSERT INTO meeting_participants(meeting_id,user_id,role,status,admitted_at,joined_at) VALUES($1,$2,'host','admitted',now(),now())`,[m.id,uid]);res.status(201).json({meeting:{id:m.id,code:m.code,title:m.title,hostUserId:uid,requireApproval:m.require_approval,status:m.status,scheduledFor:m.scheduled_for?new Date(m.scheduled_for).toISOString():null}});
});
app.post('/api/meetings/join', requireAuth, async (req,res)=>{
  try{const code=z.object({code:z.string().trim().min(5).max(40)}).parse(req.body).code.toLowerCase();const r=await pool.query(`SELECT * FROM meetings WHERE lower(code)=lower($1)`,[code]);if(!r.rowCount)return res.status(404).json({error:'meeting_not_found'});const m=r.rows[0];if(m.status!=='active')return res.status(409).json({error:'meeting_ended'});const uid=req.zoolUser!.id;const existing=await pool.query(`SELECT status FROM meeting_participants WHERE meeting_id=$1 AND user_id=$2`,[m.id,uid]);if(existing.rows[0]?.status==='denied')return res.status(403).json({error:'meeting_join_denied'});const immediate=uid===m.host_id||existing.rows[0]?.status==='admitted'||!m.require_approval;const status=immediate?'admitted':'waiting';await pool.query(`INSERT INTO meeting_participants(meeting_id,user_id,role,status,admitted_at,joined_at,left_at) VALUES($1,$2,$3,$4,CASE WHEN $4='admitted' THEN now() ELSE NULL END,CASE WHEN $4='admitted' THEN now() ELSE NULL END,NULL) ON CONFLICT(meeting_id,user_id) DO UPDATE SET status=$4,requested_at=now(),admitted_at=CASE WHEN $4='admitted' THEN now() ELSE NULL END,joined_at=CASE WHEN $4='admitted' THEN now() ELSE NULL END,left_at=NULL`,[m.id,uid,uid===m.host_id?'host':'member',status]);if(!immediate){emitTo(m.host_id,{type:'meeting.join_request',data:{meetingId:m.id,userId:uid,displayName:req.zoolUser!.displayName}});return res.status(202).json({meetingId:m.id,code:m.code,title:m.title,status:'waiting',hostUserId:m.host_id});}const conn=await livekitToken(uid,req.zoolUser!.displayName,m.room_name);res.json({meetingId:m.id,code:m.code,title:m.title,status:'admitted',hostUserId:m.host_id,...conn});}catch(e:any){if(e.message==='livekit_not_configured')return res.status(503).json({error:'livekit_not_configured'});throw e;}
});
app.get('/api/meetings/:id/join-status', requireAuth, async (req,res)=>{try{const uid=req.zoolUser!.id;const r=await pool.query(`SELECT m.*,mp.status participant_status FROM meetings m JOIN meeting_participants mp ON mp.meeting_id=m.id AND mp.user_id=$2 WHERE m.id=$1`,[req.params.id,uid]);if(!r.rowCount)return res.status(404).json({error:'meeting_not_found'});const m=r.rows[0];if(m.status==='ended')return res.json({meetingId:m.id,status:'ended'});if(m.participant_status!=='admitted')return res.json({meetingId:m.id,status:m.participant_status});const conn=await livekitToken(uid,req.zoolUser!.displayName,m.room_name);res.json({meetingId:m.id,code:m.code,title:m.title,status:'admitted',hostUserId:m.host_id,...conn});}catch(e:any){if(e.message==='livekit_not_configured')return res.status(503).json({error:'livekit_not_configured'});throw e;}});
app.get('/api/meetings/:id', requireAuth, async (req,res)=>{const uid=req.zoolUser!.id;const m=await pool.query(`SELECT * FROM meetings WHERE id=$1`,[req.params.id]);if(!m.rowCount)return res.status(404).json({error:'meeting_not_found'});const self=await pool.query(`SELECT 1 FROM meeting_participants WHERE meeting_id=$1 AND user_id=$2`,[req.params.id,uid]);if(!self.rowCount)return res.status(403).json({error:'not_participant'});const ps=await pool.query(`SELECT mp.user_id,u.display_name,u.username,mp.role,mp.status,mp.requested_at,mp.joined_at FROM meeting_participants mp JOIN users u ON u.id=mp.user_id WHERE mp.meeting_id=$1 ORDER BY CASE mp.role WHEN 'host' THEN 0 ELSE 1 END,u.display_name`,[req.params.id]);const x=m.rows[0];res.json({meeting:{id:x.id,code:x.code,title:x.title,hostUserId:x.host_id,requireApproval:x.require_approval,status:x.status,participants:ps.rows.map(p=>({userId:p.user_id,displayName:p.display_name,username:p.username,role:p.role,status:p.status,requestedAt:new Date(p.requested_at).toISOString(),joinedAt:p.joined_at?new Date(p.joined_at).toISOString():null}))}});});
app.post('/api/meetings/:id/participants/:userId/admit', requireAuth, async (req,res)=>{const m=await pool.query(`SELECT host_id FROM meetings WHERE id=$1`,[req.params.id]);if(!m.rowCount)return res.status(404).json({error:'meeting_not_found'});if(m.rows[0].host_id!==req.zoolUser!.id)return res.status(403).json({error:'host_required'});await pool.query(`UPDATE meeting_participants SET status='admitted',admitted_at=now(),joined_at=COALESCE(joined_at,now()) WHERE meeting_id=$1 AND user_id=$2 AND status='waiting'`,[req.params.id,req.params.userId]);emitTo(req.params.userId as string,{type:'meeting.admitted',data:{meetingId:String(req.params.id)}});res.json({ok:true});});
app.post('/api/meetings/:id/participants/:userId/deny', requireAuth, async (req,res)=>{const m=await pool.query(`SELECT host_id FROM meetings WHERE id=$1`,[req.params.id]);if(!m.rowCount)return res.status(404).json({error:'meeting_not_found'});if(m.rows[0].host_id!==req.zoolUser!.id)return res.status(403).json({error:'host_required'});await pool.query(`UPDATE meeting_participants SET status='denied',left_at=now() WHERE meeting_id=$1 AND user_id=$2`,[req.params.id,req.params.userId]);emitTo(req.params.userId as string,{type:'meeting.denied',data:{meetingId:String(req.params.id)}});res.json({ok:true});});
app.post('/api/meetings/:id/leave', requireAuth, async (req,res)=>{await pool.query(`UPDATE meeting_participants SET status='left',left_at=now() WHERE meeting_id=$1 AND user_id=$2`,[req.params.id,req.zoolUser!.id]);res.json({ok:true});});
app.post('/api/meetings/:id/end', requireAuth, async (req,res)=>{const m=await pool.query(`UPDATE meetings SET status='ended',ended_at=COALESCE(ended_at,now()) WHERE id=$1 AND host_id=$2 RETURNING id`,[req.params.id,req.zoolUser!.id]);if(!m.rowCount)return res.status(403).json({error:'host_required'});const ps=await pool.query(`SELECT user_id FROM meeting_participants WHERE meeting_id=$1`,[req.params.id]);for(const p of ps.rows)emitTo(p.user_id,{type:'meeting.ended',data:{meetingId:req.params.id}});res.json({ok:true});});

app.post('/api/calls', callRateLimit, requireAuth, async (req, res) => {
  try {
    const b = z.object({ peerUserIds: z.array(z.string().uuid()).min(1).max(1), type: z.enum(['audio','video']) }).parse(req.body);
    const peers = [...new Set(b.peerUserIds)].filter(id => id !== req.zoolUser!.id);
    if (!peers.length) return res.status(400).json({ error: 'invalid_call_peers' });
    const existingPeers = await pool.query(`SELECT id FROM users WHERE id=ANY($1::uuid[]) AND account_status='active'`, [peers]);
    if (existingPeers.rowCount !== peers.length) return res.status(404).json({ error: 'peer_not_found' });
    const blocked = await pool.query(`SELECT 1 FROM blocks WHERE (blocker_id=$1 AND blocked_id=ANY($2::uuid[])) OR (blocked_id=$1 AND blocker_id=ANY($2::uuid[])) LIMIT 1`, [req.zoolUser!.id, peers]);
    if (blocked.rowCount) return res.status(403).json({ error: 'blocked' });
    const roomName = `call_${randomUUID().replaceAll('-', '')}`;

    // Validate/generate LiveKit connection material before persisting a ringing call. If the
    // media service is unavailable, the caller receives a clean failure and no stale call row is
    // left behind for the callee.
    const conn = await livekitToken(req.zoolUser!.id, req.zoolUser!.displayName, roomName);
    const callId = await tx(async c => {
      const cr = await c.query(`INSERT INTO calls(room_name,initiator_id,type) VALUES($1,$2,$3) RETURNING id`, [roomName, req.zoolUser!.id, b.type]);
      const id = String(cr.rows[0].id);
      await c.query(`INSERT INTO call_participants(call_id,user_id,status,joined_at) VALUES($1,$2,'joined',now())`, [id, req.zoolUser!.id]);
      for (const peer of peers) await c.query(`INSERT INTO call_participants(call_id,user_id,status) VALUES($1,$2,'invited') ON CONFLICT DO NOTHING`, [id, peer]);
      return id;
    });

    // A chat-timeline refresh or push outage must never turn an already-created call into a 500
    // on the caller. Realtime delivery is immediate; timeline and FCM are best-effort side effects.
    for (const peer of peers) emitTo(peer,{type:'call.invite',data:{callId,callerUserId:req.zoolUser!.id,callerName:req.zoolUser!.displayName,mode:b.type}});
    void syncCallTimeline(callId).catch(() => console.warn('call_timeline_sync_failed'));
    void (async () => {
      const tokens = await fcmTokens(peers);
      await sendData(tokens, { type: 'call_invite', callId, callerName: req.zoolUser!.displayName, mode: b.type }, true);
    })().catch(() => console.warn('call_invite_delivery_failed'));
    res.status(201).json({ callId, mode: b.type, peerUserId: peers[0], e2eeMode:'client-derived-p256', ...conn });
  } catch (e: any) { if (e.message === 'livekit_not_configured') return res.status(503).json({ error: 'livekit_not_configured' }); throw e; }
});
app.post('/api/calls/:id/accept', requireAuth, async (req, res) => {
  try {
    const r = await pool.query(`SELECT c.* FROM calls c JOIN call_participants cp ON cp.call_id=c.id WHERE c.id=$1 AND cp.user_id=$2`, [req.params.id, req.zoolUser!.id]); if (!r.rowCount) return res.status(404).json({ error: 'call_not_found' });
    if (!['ringing','active'].includes(r.rows[0].status)) return res.status(409).json({ error: 'call_no_longer_available' });
    // Obtain media credentials before announcing acceptance, otherwise a LiveKit configuration
    // failure could leave the call marked active while the callee received an error.
    const conn = await livekitToken(req.zoolUser!.id, req.zoolUser!.displayName, r.rows[0].room_name);
    await tx(async c => {
      await c.query(`UPDATE call_participants SET status='joined',joined_at=COALESCE(joined_at,now()) WHERE call_id=$1 AND user_id=$2`, [req.params.id, req.zoolUser!.id]);
      await c.query(`UPDATE calls SET status='active',answered_at=COALESCE(answered_at,now()) WHERE id=$1`,[req.params.id]);
    });
    emitTo(r.rows[0].initiator_id,{type:'call.accepted',data:{callId:req.params.id}});
    void syncCallTimeline(String(req.params.id)).catch(() => console.warn('call_timeline_sync_failed'));
    res.json({ callId: req.params.id, mode: r.rows[0].type, peerUserId: r.rows[0].initiator_id, e2eeMode:'client-derived-p256', ...conn });
  } catch (e: any) { if (e.message === 'livekit_not_configured') return res.status(503).json({ error: 'livekit_not_configured' }); throw e; }
});
app.post('/api/calls/:id/decline', requireAuth, async (req, res) => {
  const uid=req.zoolUser!.id;const r=await pool.query(`SELECT c.initiator_id FROM calls c JOIN call_participants cp ON cp.call_id=c.id WHERE c.id=$1 AND cp.user_id=$2`,[req.params.id,uid]);
  if(!r.rowCount)return res.status(404).json({error:'call_not_found'});
  await pool.query(`UPDATE call_participants SET status='declined',left_at=now() WHERE call_id=$1 AND user_id=$2`, [req.params.id, uid]);
  await pool.query(`UPDATE calls SET status='declined',ended_at=COALESCE(ended_at,now()),ended_by=$2 WHERE id=$1 AND status='ringing'`,[req.params.id,uid]);
  void syncCallTimeline(String(req.params.id)).catch(() => console.warn('call_timeline_sync_failed')); emitTo(r.rows[0].initiator_id,{type:'call.declined',data:{callId:req.params.id}});res.json({ok:true});
});
app.post('/api/calls/:id/end', requireAuth, async (req,res)=>{
  const uid=req.zoolUser!.id;const r=await pool.query(`SELECT c.id FROM calls c JOIN call_participants cp ON cp.call_id=c.id WHERE c.id=$1 AND cp.user_id=$2`,[req.params.id,uid]);if(!r.rowCount)return res.status(404).json({error:'call_not_found'});
  await pool.query(`UPDATE call_participants SET status='left',left_at=COALESCE(left_at,now()) WHERE call_id=$1 AND user_id=$2`,[req.params.id,uid]);
  await pool.query(`UPDATE calls SET status='ended',ended_at=COALESCE(ended_at,now()),ended_by=$2 WHERE id=$1`,[req.params.id,uid]);
  const callId=String(req.params.id);void syncCallTimeline(callId).catch(() => console.warn('call_timeline_sync_failed'));const peers=await pool.query(`SELECT user_id FROM call_participants WHERE call_id=$1 AND user_id<>$2`,[callId,uid]);for(const p of peers.rows)emitTo(p.user_id,{type:'call.ended',data:{callId}});void (async()=>{const tokens=await fcmTokens(peers.rows.map(x=>x.user_id));await sendData(tokens,{type:'call_cancelled',callId},true);})().catch(()=>console.warn('call_cancel_delivery_failed'));res.json({ok:true});
});
app.get('/api/calls', requireAuth, async (req,res)=>{
  const uid=req.zoolUser!.id;const r=await pool.query(`SELECT c.id,c.type,c.status,c.created_at,c.answered_at,c.ended_at,c.initiator_id,cp.status participant_status,
    (SELECT u.id FROM call_participants cp2 JOIN users u ON u.id=cp2.user_id WHERE cp2.call_id=c.id AND cp2.user_id<>$1 LIMIT 1) peer_user_id,
    (SELECT u.display_name FROM call_participants cp2 JOIN users u ON u.id=cp2.user_id WHERE cp2.call_id=c.id AND cp2.user_id<>$1 LIMIT 1) peer_name
    FROM calls c JOIN call_participants cp ON cp.call_id=c.id WHERE cp.user_id=$1 ORDER BY c.created_at DESC LIMIT 100`,[uid]);
  res.json({calls:r.rows.map(x=>({id:x.id,type:x.type,status:x.status,participantStatus:x.participant_status,initiatedByMe:x.initiator_id===uid,peerUserId:x.peer_user_id,peerName:x.peer_name??'ZoolChat',createdAt:new Date(x.created_at).toISOString(),answeredAt:x.answered_at?new Date(x.answered_at).toISOString():null,endedAt:x.ended_at?new Date(x.ended_at).toISOString():null}))});
});

const callExpiryTimer = setInterval(async () => {
  try {
    const expired = await pool.query(`UPDATE calls SET status='missed',ended_at=now() WHERE status='ringing' AND created_at < now() - ($1::text || ' seconds')::interval RETURNING id`, [String(callRingTimeoutSeconds)]);
    for (const row of expired.rows) {
      await syncCallTimeline(String(row.id));
      const participants = await pool.query(`SELECT user_id FROM call_participants WHERE call_id=$1`, [row.id]);
      for (const p of participants.rows) emitTo(p.user_id,{type:'call.ended',data:{callId:row.id,reason:'missed'}});
      const tokens=await fcmTokens(participants.rows.map(x=>x.user_id));
      await sendData(tokens,{type:'call_cancelled',callId:row.id,reason:'missed'},true);
    }
  } catch (e) { console.error('call_expiry_failed', e); }
}, 15_000);
callExpiryTimer.unref();

const wss = new WebSocketServer({ server, path: '/ws' });
wss.on('connection', ws => {
  let uid: string | undefined;
  ws.on('message', async raw => {
    try {
      const m = JSON.parse(raw.toString()); if (m.type !== 'auth' || uid) return;
      let userId: string | undefined;
      if (process.env.DEV_AUTH_ENABLED === 'true' && m.devUserId) { const r = await pool.query(`SELECT id FROM users WHERE firebase_uid=$1`, [`dev:${m.devUserId}`]); userId = r.rows[0]?.id; }
      else if (m.token) {
        const free = await resolveAccessToken(String(m.token));
        if (free) userId = free.id;
        else if (process.env.FIREBASE_AUTH_ENABLED !== 'false') { const d = await verifyFirebaseToken(m.token); const r = await pool.query(`SELECT id FROM users WHERE firebase_uid=$1 AND account_status='active'`, [d.uid]); userId = r.rows[0]?.id; }
      }
      if (!userId) { ws.close(4001, 'unauthorized'); return; }
      uid = userId; const set = sockets.get(uid) ?? new Set<WebSocket>(); set.add(ws); sockets.set(uid, set); await pool.query(`UPDATE users SET online=true,last_seen_at=now() WHERE id=$1`,[uid]); ws.send(JSON.stringify({ type: 'auth.ok' }));
    } catch { ws.close(4002, 'bad_auth'); }
  });
  ws.on('close', async () => { if (uid) { const set = sockets.get(uid); set?.delete(ws); if (set?.size === 0) { sockets.delete(uid); await pool.query(`UPDATE users SET online=false,last_seen_at=now() WHERE id=$1`,[uid]).catch(()=>{}); } } });
});

installAdmin(app,{disconnectUser:(userId:string)=>{const set=sockets.get(userId);if(set){for(const socket of set){try{socket.close(4003,'account_admin_action')}catch{}}sockets.delete(userId)}}});

app.use((_req, res) => res.status(404).json({ error: 'not_found' }));
app.use((err: any, _req: any, res: any, _next: any) => {
  console.error(err);
  if (err?.message === 'unsupported_media_type') return res.status(415).json({ error: 'unsupported_media_type' });
  if (err?.code === 'LIMIT_FILE_SIZE') return res.status(413).json({ error: 'media_too_large' });
  if (err?.issues) return res.status(400).json({ error: 'invalid_request', details: err.issues });
  res.status(500).json({ error: 'internal_error' });
});
server.listen(port, () => console.log(`ZoolChat server v0.31 listening on :${port}`));
