import type { Express } from 'express';
import { randomInt } from 'node:crypto';
import { rateLimit } from 'express-rate-limit';
import { z } from 'zod';
import nodemailer from 'nodemailer';
import { pool, tx } from './db.js';
import { requireAuth } from './auth.js';
import { hashPassword, verifyPassword } from './freeAuth.js';

const emailSchema=z.string().trim().email().max(254).transform(v=>v.toLowerCase());
const limiter=rateLimit({windowMs:15*60_000,limit:12,standardHeaders:'draft-8',legacyHeaders:false});
export function emailDeliveryConfigured() { return Boolean(process.env.MAIL_HOST && process.env.MAIL_FROM); }
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
    const transport=nodemailer.createTransport({host:process.env.MAIL_HOST,port:Number(process.env.MAIL_PORT??587),secure:process.env.MAIL_PORT==='465',requireTLS:process.env.MAIL_PORT!=='465',auth:process.env.MAIL_USER?{user:process.env.MAIL_USER,pass:process.env.MAIL_PASSWORD}:undefined,connectionTimeout:10_000,socketTimeout:15_000});
    await transport.sendMail({from:process.env.MAIL_FROM,to:email,subject:purpose==='verify'?'Verify your ZoolChat email':'ZoolChat password recovery',text:`Your one-time ZoolChat code is ${code}. It expires in 15 minutes. If you did not request this, ignore this email.`});
    transport.close();
  } catch {
    await pool.query('UPDATE email_challenges SET consumed_at=now() WHERE id=$1',[challenge]);
    // Never log recipients, codes, SMTP secrets, or provider response payloads.
    console.warn('email_delivery_failed');
  }
}

export function installEmailRecovery(app:Express,onSessionsRevoked:(id:string)=>void) {
  app.post('/api/auth/password/request',limiter,async(req,res)=>{
    const b=z.object({email:emailSchema}).safeParse(req.body);
    if(!b.success)return res.status(400).json({error:'invalid_request'});
    if(!emailDeliveryConfigured())return res.status(503).json({error:'email_delivery_unavailable'});
    const u=await pool.query("SELECT id FROM users WHERE lower(email)=$1 AND email_verified_at IS NOT NULL AND account_status='active'",[b.data.email]);
    if(u.rowCount)await issue(u.rows[0].id,b.data.email,'recover');
    res.status(202).json({ok:true});
  });
  app.post('/api/auth/password/reset',limiter,async(req,res)=>{
    const b=z.object({email:emailSchema,code:z.string().regex(/^\d{8}$/),newPassword:z.string().min(8).max(128)}).safeParse(req.body);
    if(!b.success)return res.status(400).json({error:'invalid_request'});
    const passwordHash=await hashPassword(b.data.newPassword);
    const id=await tx(async c=>{
      const u=await c.query("SELECT id FROM users WHERE lower(email)=$1 AND email_verified_at IS NOT NULL AND account_status='active' FOR UPDATE",[b.data.email]);
      if(!u.rowCount)return null;
      const userId=u.rows[0].id;
      const r=await c.query("SELECT * FROM email_challenges WHERE user_id=$1 AND email=$2 AND purpose='recover' AND consumed_at IS NULL AND expires_at>now() AND attempts<5 ORDER BY created_at DESC LIMIT 1 FOR UPDATE",[userId,b.data.email]);
      if(!r.rowCount)return null;
      const code=r.rows[0];
      await c.query('UPDATE email_challenges SET attempts=attempts+1 WHERE id=$1',[code.id]);
      if(!(await verifyPassword(b.data.code,code.code_hash)))return null;
      await c.query('UPDATE user_credentials SET password_hash=$2,updated_at=now() WHERE user_id=$1',[userId,passwordHash]);
      await c.query('UPDATE email_challenges SET consumed_at=now() WHERE user_id=$1 AND consumed_at IS NULL',[userId]);
      await c.query('UPDATE auth_sessions SET revoked_at=now() WHERE user_id=$1 AND revoked_at IS NULL',[userId]);
      await c.query('UPDATE devices SET revoked_at=now() WHERE user_id=$1 AND revoked_at IS NULL',[userId]);
      // Old permanent recovery codes must not override a successful secure reset.
      await c.query('DELETE FROM account_recovery WHERE user_id=$1',[userId]);
      return userId;
    });
    if(!id)return res.status(400).json({error:'invalid_recovery_code'});
    onSessionsRevoked(id);res.json({ok:true});
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
}
