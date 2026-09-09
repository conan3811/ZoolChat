import type { Express } from 'express';
import { createHash, randomInt } from 'node:crypto';
import { rateLimit } from 'express-rate-limit';
import { z } from 'zod';
import nodemailer from 'nodemailer';
import { pool, tx } from './db.js';
import { requireAuth } from './auth.js';
import { hashPassword, verifyPassword } from './freeAuth.js';

const emailSchema=z.string().trim().email().max(254).transform(v=>v.toLowerCase());
const limiter=rateLimit({windowMs:15*60_000,limit:Number(process.env.EMAIL_RATE_LIMIT_PER_15_MINUTES??12),standardHeaders:'draft-8',legacyHeaders:false});
const accountLimiter=rateLimit({windowMs:15*60_000,limit:Number(process.env.ACCOUNT_SECURITY_RATE_LIMIT_PER_15_MINUTES??30),standardHeaders:'draft-8',legacyHeaders:false});
export function emailDeliveryConfigured() { return Boolean(process.env.MAIL_HOST && process.env.MAIL_FROM); }
function mailTransportOptions(){
  const port=Number(process.env.MAIL_PORT??587);
  const secure=process.env.MAIL_SECURE?process.env.MAIL_SECURE==='true':port===465;
  const requireTLS=process.env.MAIL_REQUIRE_TLS?process.env.MAIL_REQUIRE_TLS==='true':!secure;
  return {
    host:process.env.MAIL_HOST,
    port,
    secure,
    requireTLS,
    auth:process.env.MAIL_USER?{user:process.env.MAIL_USER,pass:process.env.MAIL_PASSWORD}:undefined,
    connectionTimeout:10_000,
    socketTimeout:15_000,
  };
}
async function issue(userId:string,email:string,purpose:'verify'|'recover') {
  const code=String(randomInt(0,100_000_000)).padStart(8,'0');
  const codeHash=await hashPassword(code);
  const challenge=await tx(async c=>{
    await c.query('SELECT id FROM users WHERE id=$1 FOR UPDATE',[userId]);
    const recent=await c.query("SELECT 1 FROM email_challenges WHERE user_id=$1 AND created_at>now()-interval '1 minute'",[userId]);
    if(recent.rowCount) return null;
    await c.query('UPDATE email_challenges SET consumed_at=now() WHERE user_id=$1 AND purpose=$2 AND consumed_at IS NULL',[userId,purpose]);
    const r=await c.query('INSERT INTO email_challenges(user_id,email,purpose,code_hash) VALUES($1,$2,$3,$4) RETURNING id',[userId,email,purpose,codeHash]);
    return r.rows[0].id;
  });
  if(!challenge)return;
  try {
    const transport=nodemailer.createTransport(mailTransportOptions());
    await transport.sendMail({from:process.env.MAIL_FROM,to:email,subject:purpose==='verify'?'Verify your ZoolChat email':'ZoolChat password recovery',text:`Your one-time ZoolChat code is ${code}. It expires in 15 minutes. If you did not request this, ignore this email.`});
    transport.close();
  } catch {
    await pool.query('UPDATE email_challenges SET consumed_at=now() WHERE id=$1',[challenge]);
    console.warn('email_delivery_failed');
  }
}

export function installEmailRecovery(app:Express,onSessionsRevoked:(id:string)=>void) {
  app.get('/api/users/search',requireAuth,accountLimiter,async(req,res)=>{
    const q=String(req.query.q??'').trim();
    if(q.length<3||q.length>254)return res.json({users:[]});
    const uid=req.zoolUser!.id;
    const username=q.replace(/^@/,'').toLowerCase();
    const display=q.toLowerCase();
    const exactEmail=/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(q)?q.toLowerCase():null;
    const phoneCandidate=q.replace(/[\s()\-.]/g,'');
    const exactPhone=/^\+[1-9]\d{5,14}$/.test(phoneCandidate)?phoneCandidate:null;
    const r=await pool.query(`SELECT u.id,u.phone_e164,u.display_name,u.username,
      CASE WHEN COALESCE(p.profile_photo_visibility,'contacts')='everyone' OR
        (COALESCE(p.profile_photo_visibility,'contacts')='contacts' AND EXISTS(
          SELECT 1 FROM chat_members a JOIN chat_members b ON b.chat_id=a.chat_id WHERE a.user_id=$1 AND b.user_id=u.id
        )) THEN COALESCE('/api/media/'||u.profile_photo_media_id::text,u.photo_url) ELSE NULL END photo_url,
      CASE
        WHEN u.username IS NOT NULL AND lower(u.username)=lower($2) THEN 0
        WHEN lower(u.display_name)=lower($3) THEN 1
        WHEN $4::text IS NOT NULL AND lower(u.email)=lower($4) THEN 2
        WHEN $5::text IS NOT NULL AND u.phone_e164=$5 THEN 3
        WHEN u.username IS NOT NULL AND lower(u.username) LIKE lower($2)||'%' THEN 4
        ELSE 5 END rank
      FROM users u LEFT JOIN user_privacy_settings p ON p.user_id=u.id
      WHERE u.id<>$1 AND u.account_status='active' AND (
        (u.username IS NOT NULL AND lower(u.username) LIKE lower($2)||'%') OR
        lower(u.display_name) LIKE '%'||lower($3)||'%' OR
        ($4::text IS NOT NULL AND lower(u.email)=lower($4)) OR
        ($5::text IS NOT NULL AND u.phone_e164=$5)
      ) AND NOT EXISTS(SELECT 1 FROM blocks bl WHERE (bl.blocker_id=$1 AND bl.blocked_id=u.id) OR (bl.blocker_id=u.id AND bl.blocked_id=$1))
      ORDER BY rank,lower(u.display_name),lower(u.username) NULLS LAST LIMIT 30`,[uid,username,display,exactEmail,exactPhone]);
    res.json({users:r.rows.map(x=>({
      userId:x.id,
      phoneE164:exactPhone&&x.phone_e164===exactPhone?exactPhone:null,
      displayName:x.display_name,
      username:x.username,
      photoUrl:x.photo_url,
    }))});
  });

  app.post('/api/auth/password/request',limiter,async(req,res)=>{
    const b=z.object({email:emailSchema}).safeParse(req.body);
    if(!b.success)return res.status(400).json({error:'invalid_request'});
    if(!emailDeliveryConfigured())return res.status(503).json({error:'email_delivery_unavailable'});
    const u=await pool.query("SELECT id FROM users WHERE lower(email)=$1 AND account_status='active'",[b.data.email]);
    if(u.rowCount)await issue(u.rows[0].id,b.data.email,'recover');
    res.status(202).json({ok:true});
  });
  app.post('/api/auth/password/reset',limiter,async(req,res)=>{
    const b=z.object({email:emailSchema,code:z.string().regex(/^\d{8}$/),newPassword:z.string().min(8).max(128)}).safeParse(req.body);
    if(!b.success)return res.status(400).json({error:'invalid_request'});
    const passwordHash=await hashPassword(b.data.newPassword);
    const id=await tx(async c=>{
      const u=await c.query("SELECT id FROM users WHERE lower(email)=$1 AND account_status='active' FOR UPDATE",[b.data.email]);
      if(!u.rowCount)return null;
      const userId=u.rows[0].id;
      const r=await c.query("SELECT * FROM email_challenges WHERE user_id=$1 AND email=$2 AND purpose='recover' AND consumed_at IS NULL AND expires_at>now() AND attempts<5 ORDER BY created_at DESC LIMIT 1 FOR UPDATE",[userId,b.data.email]);
      if(!r.rowCount)return null;
      const code=r.rows[0];
      await c.query('UPDATE email_challenges SET attempts=attempts+1 WHERE id=$1',[code.id]);
      if(!(await verifyPassword(b.data.code,code.code_hash)))return null;
      await c.query('UPDATE user_credentials SET password_hash=$2,updated_at=now() WHERE user_id=$1',[userId,passwordHash]);
      await c.query('UPDATE users SET email_verified_at=COALESCE(email_verified_at,now()),updated_at=now() WHERE id=$1',[userId]);
      await c.query('UPDATE email_challenges SET consumed_at=now() WHERE user_id=$1 AND consumed_at IS NULL',[userId]);
      await c.query('UPDATE auth_sessions SET revoked_at=now() WHERE user_id=$1 AND revoked_at IS NULL',[userId]);
      await c.query('UPDATE devices SET revoked_at=now() WHERE user_id=$1 AND revoked_at IS NULL',[userId]);
      await c.query('UPDATE user_two_factor_settings SET enabled=false,enabled_at=NULL,updated_at=now() WHERE user_id=$1',[userId]);
      await c.query('UPDATE two_factor_challenges SET consumed_at=now() WHERE user_id=$1 AND consumed_at IS NULL',[userId]);
      await c.query('DELETE FROM account_recovery WHERE user_id=$1',[userId]);
      return userId;
    });
    if(!id)return res.status(400).json({error:'invalid_recovery_code'});
    onSessionsRevoked(id);res.json({ok:true});
  });

  app.post('/api/me/password/verify',requireAuth,accountLimiter,async(req,res)=>{
    const b=z.object({password:z.string().min(8).max(128)}).safeParse(req.body);
    if(!b.success)return res.status(400).json({error:'invalid_request'});
    const r=await pool.query('SELECT password_hash FROM user_credentials WHERE user_id=$1',[req.zoolUser!.id]);
    if(!r.rowCount||!(await verifyPassword(b.data.password,String(r.rows[0].password_hash))))return res.status(403).json({error:'invalid_password'});
    res.json({ok:true});
  });

  app.put('/api/me/password',requireAuth,accountLimiter,async(req,res)=>{
    const b=z.object({currentPassword:z.string().min(8).max(128),newPassword:z.string().min(8).max(128)}).safeParse(req.body);
    if(!b.success)return res.status(400).json({error:'invalid_request'});
    const uid=req.zoolUser!.id;
    const current=await pool.query('SELECT password_hash FROM user_credentials WHERE user_id=$1',[uid]);
    if(!current.rowCount||!(await verifyPassword(b.data.currentPassword,String(current.rows[0].password_hash))))return res.status(403).json({error:'invalid_password'});
    const next=await hashPassword(b.data.newPassword);
    await tx(async c=>{
      await c.query('UPDATE user_credentials SET password_hash=$2,updated_at=now() WHERE user_id=$1',[uid,next]);
      await c.query('UPDATE auth_sessions SET revoked_at=now() WHERE user_id=$1 AND revoked_at IS NULL',[uid]);
      await c.query('UPDATE devices SET revoked_at=now() WHERE user_id=$1 AND revoked_at IS NULL',[uid]);
    });
    onSessionsRevoked(uid);res.json({ok:true,sessionRevoked:true});
  });

  app.put('/api/me/phone',requireAuth,accountLimiter,async(req,res)=>{
    const b=z.object({password:z.string().min(8).max(128),phoneE164:z.string().regex(/^\+[1-9]\d{5,14}$/)}).safeParse(req.body);
    if(!b.success)return res.status(400).json({error:'invalid_request'});
    const uid=req.zoolUser!.id;
    const current=await pool.query('SELECT password_hash FROM user_credentials WHERE user_id=$1',[uid]);
    if(!current.rowCount||!(await verifyPassword(b.data.password,String(current.rows[0].password_hash))))return res.status(403).json({error:'invalid_password'});
    const discoveryHash=createHash('sha256').update(b.data.phoneE164,'utf8').digest('hex');
    try{
      await pool.query('UPDATE users SET phone_e164=$2,phone_discovery_hash=$3,updated_at=now() WHERE id=$1',[uid,b.data.phoneE164,discoveryHash]);
      res.json({ok:true,phoneE164:b.data.phoneE164});
    }catch(e:any){if(e?.code==='23505')return res.status(409).json({error:'phone_in_use'});throw e;}
  });

  app.post('/api/me/email/request',requireAuth,limiter,async(req,res)=>{
    const b=z.object({email:emailSchema}).safeParse(req.body);
    if(!b.success)return res.status(400).json({error:'invalid_request'});
    if(!emailDeliveryConfigured())return res.status(503).json({error:'email_delivery_unavailable'});
    const existing=await pool.query('SELECT id FROM users WHERE lower(email)=$1 AND id<>$2',[b.data.email,req.zoolUser!.id]);
    if(existing.rowCount)return res.status(409).json({error:'email_in_use'});
    await issue(req.zoolUser!.id,b.data.email,'verify');res.status(202).json({ok:true});
  });
  app.post('/api/me/email/verify',requireAuth,limiter,async(req,res)=>{
    const b=z.object({email:emailSchema,code:z.string().regex(/^\d{8}$/)}).safeParse(req.body);
    if(!b.success)return res.status(400).json({error:'invalid_request'});
    try {
      const valid=await tx(async c=>{
        const uid=req.zoolUser!.id;
        await c.query('SELECT id FROM users WHERE id=$1 FOR UPDATE',[uid]);
        const r=await c.query("SELECT * FROM email_challenges WHERE user_id=$1 AND email=$2 AND purpose='verify' AND consumed_at IS NULL AND expires_at>now() AND attempts<5 ORDER BY created_at DESC LIMIT 1 FOR UPDATE",[uid,b.data.email]);
        if(!r.rowCount)return false;
        await c.query('UPDATE email_challenges SET attempts=attempts+1 WHERE id=$1',[r.rows[0].id]);
        if(!(await verifyPassword(b.data.code,r.rows[0].code_hash)))return false;
        await c.query('UPDATE users SET email=$2,email_verified_at=now(),updated_at=now() WHERE id=$1',[uid,b.data.email]);
        await c.query('UPDATE email_challenges SET consumed_at=now() WHERE user_id=$1 AND consumed_at IS NULL',[uid]);
        return true;
      });
      res.status(valid?200:400).json(valid?{ok:true}:{error:'invalid_recovery_code'});
    } catch(e:any) { if(e.code==='23505')return res.status(409).json({error:'email_in_use'});throw e; }
  });
  app.get('/api/me/availability',requireAuth,limiter,async(req,res)=>{
    const b=z.object({email:emailSchema.optional(),username:z.string().trim().min(3).max(32).regex(/^[A-Za-z0-9_.]+$/).optional()}).safeParse(req.query);
    if(!b.success||(!b.data.email&&!b.data.username))return res.status(400).json({error:'invalid_request'});
    const r=await pool.query('SELECT email,username FROM users WHERE id<>$1 AND (lower(email)=lower($2) OR lower(username)=lower($3))',[req.zoolUser!.id,b.data.email??null,b.data.username??null]);
    res.json({emailAvailable:!r.rows.some(x=>x.email?.toLowerCase()===b.data.email),usernameAvailable:!r.rows.some(x=>x.username?.toLowerCase()===b.data.username?.toLowerCase())});
  });

  app.get('/api/e2ee/self-key',requireAuth,accountLimiter,async(req,res)=>{
    const r=await pool.query(`SELECT algorithm,public_key_b64,fingerprint_sha256,updated_at FROM user_crypto_keys WHERE user_id=$1 UNION ALL SELECT 'P256-ECDH'::text algorithm,public_key_b64,fingerprint_sha256,updated_at FROM e2ee_identity_backups WHERE user_id=$1 AND NOT EXISTS(SELECT 1 FROM user_crypto_keys WHERE user_id=$1) LIMIT 1`,[req.zoolUser!.id]);
    if(!r.rowCount)return res.status(404).json({error:'e2ee_key_missing'});
    const x=r.rows[0];res.json({algorithm:x.algorithm,publicKeyB64:x.public_key_b64,fingerprintSha256:x.fingerprint_sha256,updatedAt:new Date(x.updated_at).toISOString()});
  });
  app.get('/api/e2ee/backup',requireAuth,accountLimiter,async(req,res)=>{
    const r=await pool.query('SELECT version,public_key_b64,fingerprint_sha256,salt_b64,iv_b64,ciphertext_b64,updated_at FROM e2ee_identity_backups WHERE user_id=$1',[req.zoolUser!.id]);
    if(!r.rowCount)return res.status(404).json({error:'e2ee_backup_missing'});
    const x=r.rows[0];res.json({backup:{version:Number(x.version),publicKeyB64:x.public_key_b64,fingerprintSha256:x.fingerprint_sha256,saltB64:x.salt_b64,ivB64:x.iv_b64,ciphertextB64:x.ciphertext_b64,updatedAt:new Date(x.updated_at).toISOString()}});
  });
  app.put('/api/e2ee/backup',requireAuth,accountLimiter,async(req,res)=>{
    const b=z.object({
      version:z.literal(1),
      publicKeyB64:z.string().min(80).max(4096),
      fingerprintSha256:z.string().regex(/^[A-Fa-f0-9:]{32,200}$/),
      saltB64:z.string().min(16).max(256),
      ivB64:z.string().min(12).max(128),
      ciphertextB64:z.string().min(64).max(8192),
    }).safeParse(req.body);
    if(!b.success)return res.status(400).json({error:'invalid_request'});
    const uid=req.zoolUser!.id;
    const current=await pool.query(`SELECT public_key_b64,fingerprint_sha256 FROM user_crypto_keys WHERE user_id=$1 UNION ALL SELECT public_key_b64,fingerprint_sha256 FROM e2ee_identity_backups WHERE user_id=$1 AND NOT EXISTS(SELECT 1 FROM user_crypto_keys WHERE user_id=$1) LIMIT 1`,[uid]);
    if(current.rowCount&&(String(current.rows[0].public_key_b64)!==b.data.publicKeyB64||String(current.rows[0].fingerprint_sha256).toUpperCase()!==b.data.fingerprintSha256.toUpperCase()))return res.status(409).json({error:'e2ee_key_conflict'});
    await tx(async c=>{
      await c.query(`INSERT INTO user_crypto_keys(user_id,algorithm,public_key_b64,fingerprint_sha256)
        VALUES($1,'P256-ECDH',$2,$3)
        ON CONFLICT(user_id) DO UPDATE SET public_key_b64=EXCLUDED.public_key_b64,fingerprint_sha256=EXCLUDED.fingerprint_sha256,updated_at=now()`,[uid,b.data.publicKeyB64,b.data.fingerprintSha256]);
      await c.query(`INSERT INTO e2ee_identity_backups(user_id,version,public_key_b64,fingerprint_sha256,salt_b64,iv_b64,ciphertext_b64)
        VALUES($1,$2,$3,$4,$5,$6,$7)
        ON CONFLICT(user_id) DO UPDATE SET version=EXCLUDED.version,public_key_b64=EXCLUDED.public_key_b64,fingerprint_sha256=EXCLUDED.fingerprint_sha256,salt_b64=EXCLUDED.salt_b64,iv_b64=EXCLUDED.iv_b64,ciphertext_b64=EXCLUDED.ciphertext_b64,updated_at=now()`,[uid,b.data.version,b.data.publicKeyB64,b.data.fingerprintSha256,b.data.saltB64,b.data.ivB64,b.data.ciphertextB64]);
    });
    res.json({ok:true});
  });
}
