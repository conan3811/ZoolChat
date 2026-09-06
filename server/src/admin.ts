import type { Express, Request, Response } from 'express';
import { createHash, createHmac, randomBytes, timingSafeEqual } from 'node:crypto';
import { rateLimit } from 'express-rate-limit';
import { pool, tx } from './db.js';
import { hashPassword } from './freeAuth.js';
import { deleteFirebaseUser } from './firebase.js';
import { ADMIN_HTML } from './adminPage.js';

type InstallOptions = { disconnectUser?: (userId:string)=>void };

const adminUser = () => (process.env.ADMIN_USERNAME || 'admin').trim();
const adminPassword = () => process.env.ADMIN_PASSWORD || '';
const configured = () => adminPassword().length >= 12;
const sessionSecret = () => process.env.ADMIN_SESSION_SECRET || createHash('sha256').update(`zoolchat-admin:${adminPassword()}`).digest('hex');
const sessionHours = () => Math.max(1, Math.min(Number(process.env.ADMIN_SESSION_HOURS ?? 8), 72));
const cookieName = 'zc_admin';

function safeEq(a:string,b:string){
  const aa=Buffer.from(createHash('sha256').update(a).digest('hex'));
  const bb=Buffer.from(createHash('sha256').update(b).digest('hex'));
  return aa.length===bb.length && timingSafeEqual(aa,bb);
}
function b64url(v:string){return Buffer.from(v,'utf8').toString('base64url')}
function sign(payload:string){return createHmac('sha256',sessionSecret()).update(payload).digest('base64url')}
function makeSession(){const payload=b64url(JSON.stringify({u:adminUser(),exp:Date.now()+sessionHours()*3600_000,n:randomBytes(12).toString('base64url')}));return `${payload}.${sign(payload)}`}
function readCookie(req:Request,name:string){const raw=req.headers.cookie||'';for(const part of raw.split(';')){const i=part.indexOf('=');if(i<0)continue;if(part.slice(0,i).trim()===name)return decodeURIComponent(part.slice(i+1).trim())}return null}
function validSession(req:Request){if(!configured())return false;const token=readCookie(req,cookieName);if(!token)return false;const [payload,sig]=token.split('.');if(!payload||!sig||!safeEq(sig,sign(payload)))return false;try{const j=JSON.parse(Buffer.from(payload,'base64url').toString('utf8'));return j.u===adminUser()&&Number(j.exp)>Date.now()}catch{return false}}
function setSessionCookie(res:Response,value:string,maxAgeSeconds:number){res.setHeader('Set-Cookie',`${cookieName}=${encodeURIComponent(value)}; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=${maxAgeSeconds}`)}
function adminOnly(req:Request,res:Response,next:()=>void){if(!configured())return res.status(503).json({error:'admin_not_configured'});if(!validSession(req))return res.status(401).json({error:'admin_auth_required'});next()}
async function audit(req:Request,action:string,targetUserId?:string|null,targetReportId?:string|null,metadata:Record<string,unknown>={}){
  await pool.query(`INSERT INTO admin_audit_log(admin_name,action,target_user_id,target_report_id,metadata,ip) VALUES($1,$2,$3,$4,$5::jsonb,$6)`,[adminUser(),action,targetUserId??null,targetReportId??null,JSON.stringify(metadata),req.ip||null]).catch(e=>console.error('admin_audit_failed',e));
}
const sha256=(v:string)=>createHash('sha256').update(v,'utf8').digest('hex');
const newRecoveryCode=()=>`ZC-${randomBytes(18).toString('base64url')}`;

export function installAdmin(app:Express, options:InstallOptions={}){
  const loginLimiter=rateLimit({windowMs:15*60_000,limit:10,standardHeaders:'draft-8',legacyHeaders:false});

  app.get('/admin',(_req,res)=>{
    res.setHeader('Cache-Control','no-store');
    res.setHeader('Content-Security-Policy',"default-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
    res.type('html').send(ADMIN_HTML);
  });
  app.get('/admin/',(_req,res)=>res.redirect(302,'/admin'));

  app.get('/api/admin/session',(req,res)=>res.json({authenticated:validSession(req),configured:configured(),adminName:validSession(req)?adminUser():null}));
  app.post('/api/admin/login',loginLimiter,(req,res)=>{
    if(!configured())return res.status(503).json({error:'admin_not_configured'});
    const username=String(req.body?.username??'').trim(); const password=String(req.body?.password??'');
    if(!safeEq(username,adminUser())||!safeEq(password,adminPassword()))return res.status(401).json({error:'invalid_admin_credentials'});
    const token=makeSession(); setSessionCookie(res,token,sessionHours()*3600); void audit(req,'admin.login',null,null,{}); res.json({ok:true,adminName:adminUser()});
  });
  app.post('/api/admin/logout',adminOnly,(req,res)=>{setSessionCookie(res,'',0);void audit(req,'admin.logout');res.json({ok:true})});

  app.get('/api/admin/summary',adminOnly,async(_req,res)=>{
    const r=await pool.query(`SELECT
      (SELECT count(*)::int FROM users) users,
      (SELECT count(*)::int FROM users WHERE account_status='active') active_users,
      (SELECT count(*)::int FROM users WHERE account_status='suspended') suspended_users,
      (SELECT count(*)::int FROM auth_sessions WHERE revoked_at IS NULL AND refresh_expires_at>now()) active_sessions,
      (SELECT count(*)::int FROM reports WHERE status='open') open_reports,
      (SELECT count(*)::int FROM messages) messages,
      (SELECT count(*)::int FROM calls) calls`);
    const x=r.rows[0];res.json({users:x.users,activeUsers:x.active_users,suspendedUsers:x.suspended_users,activeSessions:x.active_sessions,openReports:x.open_reports,messages:x.messages,calls:x.calls});
  });

  app.get('/api/admin/users',adminOnly,async(req,res)=>{
    const page=Math.max(1,Number(req.query.page||1)); const limit=Math.max(1,Math.min(Number(req.query.limit||30),100)); const offset=(page-1)*limit; const q=String(req.query.q||'').trim();
    const like=`%${q}%`;
    const where=q?`WHERE u.id::text ILIKE $1 OR COALESCE(u.username,'') ILIKE $1 OR COALESCE(u.email,'') ILIKE $1 OR COALESCE(u.phone_e164,'') ILIKE $1 OR COALESCE(u.display_name,'') ILIKE $1`:'';
    const args=q?[like]:[]; const count=await pool.query(`SELECT count(*)::int n FROM users u ${where}`,args);
    const r=await pool.query(`SELECT u.id,u.username,u.email,u.phone_e164,u.display_name,u.photo_url,u.about,u.created_at,u.updated_at,u.last_seen_at,u.online,u.account_status,u.suspended_at,u.suspension_reason,
      (SELECT count(*)::int FROM auth_sessions s WHERE s.user_id=u.id AND s.revoked_at IS NULL AND s.refresh_expires_at>now()) active_sessions
      FROM users u ${where} ORDER BY u.created_at DESC LIMIT $${args.length+1} OFFSET $${args.length+2}`,[...args,limit,offset]);
    const total=count.rows[0].n;res.json({page,limit,total,pages:Math.max(1,Math.ceil(total/limit)),users:r.rows.map(x=>({id:x.id,username:x.username,email:x.email,phoneE164:x.phone_e164,displayName:x.display_name,photoUrl:x.photo_url,about:x.about,createdAt:x.created_at,updatedAt:x.updated_at,lastSeenAt:x.last_seen_at,online:x.online,accountStatus:x.account_status,suspendedAt:x.suspended_at,suspensionReason:x.suspension_reason,activeSessions:x.active_sessions}))});
  });

  app.get('/api/admin/users/:id',adminOnly,async(req,res)=>{
    const id=String(req.params.id); const ur=await pool.query(`SELECT id,firebase_uid,username,email,phone_e164,display_name,photo_url,about,created_at,updated_at,last_seen_at,online,account_status,suspended_at,suspension_reason FROM users WHERE id=$1`,[id]);
    if(!ur.rowCount)return res.status(404).json({error:'user_not_found'}); const u=ur.rows[0];
    const [sessions,counts]=await Promise.all([
      pool.query(`SELECT id,device_name,created_at,last_seen_at,access_expires_at,refresh_expires_at FROM auth_sessions WHERE user_id=$1 AND revoked_at IS NULL AND refresh_expires_at>now() ORDER BY last_seen_at DESC`,[id]),
      pool.query(`SELECT
        (SELECT count(*)::int FROM messages WHERE sender_id=$1) messages,
        (SELECT count(*)::int FROM call_participants WHERE user_id=$1) calls,
        (SELECT count(*)::int FROM chat_members WHERE user_id=$1) chats,
        (SELECT count(*)::int FROM reports WHERE reported_user_id=$1) reports_against,
        (SELECT count(*)::int FROM reports WHERE reporter_id=$1) reports_by`,[id])
    ]);
    const c=counts.rows[0];res.json({user:{id:u.id,username:u.username,email:u.email,phoneE164:u.phone_e164,displayName:u.display_name,photoUrl:u.photo_url,about:u.about,createdAt:u.created_at,updatedAt:u.updated_at,lastSeenAt:u.last_seen_at,online:u.online,accountStatus:u.account_status,suspendedAt:u.suspended_at,suspensionReason:u.suspension_reason,authType:u.firebase_uid?'firebase-or-dev':'password'},counts:{messages:c.messages,calls:c.calls,chats:c.chats,reportsAgainst:c.reports_against,reportsBy:c.reports_by},sessions:sessions.rows.map(s=>({id:s.id,deviceName:s.device_name,createdAt:s.created_at,lastSeenAt:s.last_seen_at,accessExpiresAt:s.access_expires_at,refreshExpiresAt:s.refresh_expires_at}))});
  });

  app.post('/api/admin/users/:id/reset-password',adminOnly,async(req,res)=>{
    const id=String(req.params.id), newPassword=String(req.body?.newPassword??''); if(newPassword.length<8||newPassword.length>128)return res.status(400).json({error:'invalid_password'});
    const exists=await pool.query(`SELECT u.id FROM users u JOIN user_credentials uc ON uc.user_id=u.id WHERE u.id=$1`,[id]); if(!exists.rowCount)return res.status(409).json({error:'password_auth_not_available'});
    const passwordHash=await hashPassword(newPassword); const recoveryCode=newRecoveryCode();
    await tx(async c=>{await c.query(`UPDATE user_credentials SET password_hash=$2,updated_at=now() WHERE user_id=$1`,[id,passwordHash]);await c.query(`UPDATE auth_sessions SET revoked_at=now() WHERE user_id=$1 AND revoked_at IS NULL`,[id]);await c.query(`UPDATE devices SET revoked_at=now() WHERE user_id=$1 AND revoked_at IS NULL`,[id]);await c.query(`INSERT INTO account_recovery(user_id,recovery_hash,rotated_at) VALUES($1,$2,now()) ON CONFLICT(user_id) DO UPDATE SET recovery_hash=EXCLUDED.recovery_hash,rotated_at=now()`,[id,sha256(recoveryCode)]);});
    options.disconnectUser?.(id);await audit(req,'user.reset_password',id,null,{sessionsRevoked:true,recoveryRotated:true});res.json({ok:true,recoveryCode});
  });

  app.post('/api/admin/users/:id/revoke-sessions',adminOnly,async(req,res)=>{const id=String(req.params.id);await pool.query(`UPDATE auth_sessions SET revoked_at=now() WHERE user_id=$1 AND revoked_at IS NULL`,[id]);await pool.query(`UPDATE devices SET revoked_at=now() WHERE user_id=$1 AND revoked_at IS NULL`,[id]);options.disconnectUser?.(id);await audit(req,'user.revoke_sessions',id);res.json({ok:true})});
  app.post('/api/admin/users/:id/suspend',adminOnly,async(req,res)=>{const id=String(req.params.id);const reason=String(req.body?.reason??'').trim().slice(0,500)||'Administrative review';const r=await pool.query(`UPDATE users SET account_status='suspended',suspended_at=now(),suspension_reason=$2,online=false,updated_at=now() WHERE id=$1 RETURNING id`,[id,reason]);if(!r.rowCount)return res.status(404).json({error:'user_not_found'});await pool.query(`UPDATE auth_sessions SET revoked_at=now() WHERE user_id=$1 AND revoked_at IS NULL`,[id]);await pool.query(`UPDATE devices SET revoked_at=now() WHERE user_id=$1 AND revoked_at IS NULL`,[id]);options.disconnectUser?.(id);await audit(req,'user.suspend',id,null,{reason});res.json({ok:true})});
  app.post('/api/admin/users/:id/activate',adminOnly,async(req,res)=>{const id=String(req.params.id);const r=await pool.query(`UPDATE users SET account_status='active',suspended_at=NULL,suspension_reason=NULL,updated_at=now() WHERE id=$1 RETURNING id`,[id]);if(!r.rowCount)return res.status(404).json({error:'user_not_found'});await audit(req,'user.activate',id);res.json({ok:true})});

  app.get('/api/admin/users/:id/export',adminOnly,async(req,res)=>{
    const id=String(req.params.id);const user=(await pool.query(`SELECT id,username,email,phone_e164,display_name,photo_url,about,created_at,updated_at,last_seen_at,account_status,suspended_at,suspension_reason FROM users WHERE id=$1`,[id])).rows[0];if(!user)return res.status(404).json({error:'user_not_found'});
    const [memberships,messages,calls,devices,reportsBy,reportsAgainst]=await Promise.all([
      pool.query(`SELECT chat_id,role,joined_at,last_read_at FROM chat_members WHERE user_id=$1 ORDER BY joined_at`,[id]),
      pool.query(`SELECT id,chat_id,type,body,encrypted,crypto_version,created_at,edited_at,deleted_at FROM messages WHERE sender_id=$1 ORDER BY created_at`,[id]),
      pool.query(`SELECT c.id,c.type,c.status,c.created_at,c.answered_at,c.ended_at,cp.status participant_status FROM call_participants cp JOIN calls c ON c.id=cp.call_id WHERE cp.user_id=$1 ORDER BY c.created_at`,[id]),
      pool.query(`SELECT id,platform,device_name,created_at,last_seen_at,revoked_at FROM devices WHERE user_id=$1 ORDER BY created_at`,[id]),
      pool.query(`SELECT id,reported_user_id,chat_id,reason,status,created_at,resolved_at,resolution_note FROM reports WHERE reporter_id=$1 ORDER BY created_at`,[id]),
      pool.query(`SELECT id,reporter_id,chat_id,reason,status,created_at,resolved_at,resolution_note FROM reports WHERE reported_user_id=$1 ORDER BY created_at`,[id])
    ]);await audit(req,'user.export',id);res.setHeader('Content-Disposition',`attachment; filename="zoolchat-user-${id}.json"`);res.json({exportedAt:new Date().toISOString(),user,chats:memberships.rows,messages:messages.rows,calls:calls.rows,devices:devices.rows,reportsBy:reportsBy.rows,reportsAgainst:reportsAgainst.rows});
  });

  app.delete('/api/admin/users/:id',adminOnly,async(req,res)=>{
    const id=String(req.params.id);const r=await pool.query(`SELECT id,username,firebase_uid FROM users WHERE id=$1`,[id]);if(!r.rowCount)return res.status(404).json({error:'user_not_found'});const u=r.rows[0],expected=u.username||u.id;if(String(req.body?.confirm??'')!==String(expected))return res.status(400).json({error:'confirmation_mismatch'});
    options.disconnectUser?.(id);if(u.firebase_uid&&!String(u.firebase_uid).startsWith('dev:'))await deleteFirebaseUser(u.firebase_uid).catch(e=>console.error('admin_firebase_delete_failed',e));await audit(req,'user.delete',id,null,{username:u.username});await pool.query(`DELETE FROM users WHERE id=$1`,[id]);res.json({ok:true});
  });

  app.get('/api/admin/reports',adminOnly,async(req,res)=>{const status=String(req.query.status||'open');const r=await pool.query(`SELECT r.id,r.reporter_id,r.reported_user_id,r.chat_id,r.reason,r.status,r.created_at,r.resolved_at,r.resolution_note,ru.display_name reporter_name,ru.username reporter_username,tu.display_name reported_name,tu.username reported_username FROM reports r JOIN users ru ON ru.id=r.reporter_id LEFT JOIN users tu ON tu.id=r.reported_user_id WHERE ($1='all' OR r.status=$1) ORDER BY r.created_at DESC LIMIT 300`,[status]);res.json({reports:r.rows.map(x=>({id:x.id,reporterId:x.reporter_id,reportedUserId:x.reported_user_id,chatId:x.chat_id,reason:x.reason,status:x.status,createdAt:x.created_at,resolvedAt:x.resolved_at,resolutionNote:x.resolution_note,reporterName:x.reporter_name,reporterUsername:x.reporter_username,reportedName:x.reported_name,reportedUsername:x.reported_username}))})});
  app.post('/api/admin/reports/:id/status',adminOnly,async(req,res)=>{const id=String(req.params.id),status=String(req.body?.status||'');if(!['resolved','dismissed'].includes(status))return res.status(400).json({error:'invalid_status'});const note=String(req.body?.note||'').slice(0,1000);const r=await pool.query(`UPDATE reports SET status=$2,resolved_at=now(),resolution_note=$3 WHERE id=$1 RETURNING id,reported_user_id`,[id,status,note||null]);if(!r.rowCount)return res.status(404).json({error:'report_not_found'});await audit(req,`report.${status}`,r.rows[0].reported_user_id,id,{note});res.json({ok:true})});

  app.get('/api/admin/audit',adminOnly,async(_req,res)=>{const r=await pool.query(`SELECT a.id,a.admin_name,a.action,a.target_user_id,a.target_report_id,a.metadata,a.ip,a.created_at,u.username target_username FROM admin_audit_log a LEFT JOIN users u ON u.id=a.target_user_id ORDER BY a.created_at DESC LIMIT 500`);res.json({entries:r.rows.map(x=>({id:x.id,adminName:x.admin_name,action:x.action,targetUserId:x.target_user_id,targetUsername:x.target_username,targetReportId:x.target_report_id,metadata:x.metadata,ip:x.ip,createdAt:x.created_at}))})});
}
