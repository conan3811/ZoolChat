import type {Request,Response,NextFunction} from 'express';
import {pool} from './db.js';
import {verifyFirebaseToken} from './firebase.js';
import {resolveAccessToken} from './freeAuth.js';

declare global {
  namespace Express {
    interface Request {
      zoolUser?: { id:string; firebaseUid?:string; phoneE164?:string; displayName:string; authMode?:'free'|'firebase'|'dev' }
    }
  }
}

function validDevId(value:string){ return /^[A-Za-z0-9._-]{3,80}$/.test(value); }
function validPhone(value?:string){ return !value || /^\+[1-9]\d{5,14}$/.test(value); }
const delay=(ms:number)=>new Promise<void>(resolve=>setTimeout(resolve,ms));

/**
 * v0.32 compatibility handling that runs after authentication but before the older route body.
 * Keeping it here allows already-authenticated clients to receive the corrected directory search
 * and gives an incoming call a short window to publish its E2EE identity during cold start.
 */
async function handleAuthenticatedV032(req:Request,res:Response,next:NextFunction){
  const uid=req.zoolUser!.id;

  if(req.method==='GET' && req.path==='/api/users/search'){
    const q=String(req.query.q??'').trim();
    if(q.length<3 || q.length>254){res.json({users:[]});return;}

    const usernameQuery=q.replace(/^@/,'').toLowerCase();
    const emailQuery=q.includes('@')&&!q.startsWith('@') ? q.toLowerCase() : null;
    const rawDigits=q.replace(/\D/g,'');
    const normalizedDigits=rawDigits.startsWith('00') ? rawDigits.slice(2) : rawDigits.replace(/^0/,'');
    const phoneDigits=normalizedDigits.length>=8 ? normalizedDigits : null;

    const r=await pool.query(`SELECT u.id,u.phone_e164,u.display_name,u.username,
      CASE WHEN COALESCE(p.profile_photo_visibility,'contacts')='everyone' OR (COALESCE(p.profile_photo_visibility,'contacts')='contacts' AND EXISTS(SELECT 1 FROM chat_members a JOIN chat_members b ON b.chat_id=a.chat_id WHERE a.user_id=$1 AND b.user_id=u.id)) THEN COALESCE('/api/media/'||u.profile_photo_media_id::text,u.photo_url) ELSE NULL END photo_url
      FROM users u LEFT JOIN user_privacy_settings p ON p.user_id=u.id
      WHERE u.id<>$1 AND u.account_status='active' AND (
        (u.username IS NOT NULL AND lower(u.username) LIKE $2 || '%')
        OR lower(u.display_name) LIKE '%' || $2 || '%'
        OR ($3::text IS NOT NULL AND lower(u.email)=lower($3))
        OR ($4::text IS NOT NULL AND regexp_replace(COALESCE(u.phone_e164,''),'[^0-9]','','g') LIKE '%' || $4)
      )
      AND NOT EXISTS(SELECT 1 FROM blocks bl WHERE (bl.blocker_id=$1 AND bl.blocked_id=u.id) OR (bl.blocker_id=u.id AND bl.blocked_id=$1))
      ORDER BY CASE
        WHEN u.username IS NOT NULL AND lower(u.username)=$2 THEN 0
        WHEN $3::text IS NOT NULL AND lower(u.email)=lower($3) THEN 1
        WHEN $4::text IS NOT NULL AND regexp_replace(COALESCE(u.phone_e164,''),'[^0-9]','','g') LIKE '%' || $4 THEN 2
        WHEN u.username IS NOT NULL AND lower(u.username) LIKE $2 || '%' THEN 3
        ELSE 4 END,
        lower(u.display_name),lower(u.username) ASC NULLS LAST LIMIT 20`,[uid,usernameQuery,emailQuery,phoneDigits]);

    res.json({users:r.rows.map(x=>{
      const storedDigits=String(x.phone_e164??'').replace(/\D/g,'');
      const phoneMatched=Boolean(phoneDigits&&storedDigits.endsWith(phoneDigits));
      return {userId:x.id,phoneE164:phoneMatched?x.phone_e164:null,displayName:x.display_name,username:x.username,photoUrl:x.photo_url};
    })});
    return;
  }

  const e2eeMatch=req.method==='GET' ? req.path.match(/^\/api\/users\/([0-9a-fA-F-]{36})\/e2ee-key$/) : null;
  if(e2eeMatch){
    const id=e2eeMatch[1];
    if(!/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$/.test(id)){
      res.status(400).json({error:'invalid_request'});return;
    }
    if(id===uid){res.status(400).json({error:'self_key_not_required'});return;}

    // A callee opened from FCM may still be bootstrapping and publishing its identity while the
    // caller is already deriving the call key. Wait briefly instead of failing the whole call.
    for(let attempt=0;attempt<12;attempt++){
      const r=await pool.query(`SELECT u.id,u.display_name,k.algorithm,k.public_key_b64,k.fingerprint_sha256,k.updated_at
        FROM users u LEFT JOIN user_crypto_keys k ON k.user_id=u.id WHERE u.id=$1 AND u.account_status='active'`,[id]);
      if(!r.rowCount){res.status(404).json({error:'user_not_found'});return;}
      if(r.rows[0].public_key_b64){
        const x=r.rows[0];
        res.json({userId:x.id,displayName:x.display_name,algorithm:x.algorithm,publicKeyB64:x.public_key_b64,fingerprintSha256:x.fingerprint_sha256,updatedAt:new Date(x.updated_at).toISOString()});
        return;
      }
      if(attempt<11)await delay(350);
    }
    res.status(409).json({error:'peer_e2ee_key_missing'});return;
  }

  next();
}

export async function requireAuth(req:Request,res:Response,next:NextFunction){
  try{
    const h=req.header('authorization');
    if(h?.startsWith('Bearer ')) {
      const raw=h.slice(7).trim();
      const free=await resolveAccessToken(raw);
      if(free){
        req.zoolUser={id:free.id,firebaseUid:free.firebase_uid??undefined,phoneE164:free.phone_e164??undefined,displayName:free.display_name,authMode:'free'};
        return handleAuthenticatedV032(req,res,next);
      }
      if(process.env.FIREBASE_AUTH_ENABLED!=='false'){
        const decoded=await verifyFirebaseToken(raw);
        const phone=(decoded.phone_number as string|undefined);
        const r=await pool.query(`INSERT INTO users(firebase_uid,phone_e164,display_name)
          VALUES($1,$2,$3)
          ON CONFLICT(firebase_uid) DO UPDATE SET phone_e164=COALESCE(EXCLUDED.phone_e164,users.phone_e164),updated_at=now()
          RETURNING id,firebase_uid,phone_e164,display_name,account_status`,[decoded.uid,phone??null,phone??'ZoolChat User']);
        if(r.rows[0].account_status!=='active') return res.status(403).json({error:'account_suspended'});
        req.zoolUser={id:r.rows[0].id,firebaseUid:r.rows[0].firebase_uid,phoneE164:r.rows[0].phone_e164,displayName:r.rows[0].display_name,authMode:'firebase'};
        return handleAuthenticatedV032(req,res,next);
      }
      return res.status(401).json({error:'invalid_or_expired_access_token'});
    }

    if(process.env.DEV_AUTH_ENABLED==='true' && req.header('x-zoolchat-dev-user')){
      const dev=req.header('x-zoolchat-dev-user')!.trim();
      const phone=req.header('x-zoolchat-dev-phone')?.trim() || undefined;
      const name=(req.header('x-zoolchat-dev-name')?.trim() || `Dev ${dev.slice(0,8)}`).slice(0,80);
      if(!validDevId(dev) || !validPhone(phone)) return res.status(400).json({error:'invalid_dev_identity'});
      const r=await pool.query(`INSERT INTO users(firebase_uid,phone_e164,display_name)
        VALUES($1,$2,$3)
        ON CONFLICT(firebase_uid) DO UPDATE SET phone_e164=COALESCE(EXCLUDED.phone_e164,users.phone_e164),display_name=COALESCE(NULLIF(EXCLUDED.display_name,''),users.display_name),updated_at=now()
        RETURNING id,firebase_uid,phone_e164,display_name,account_status`,[`dev:${dev}`,phone??null,name]);
      if(r.rows[0].account_status!=='active') return res.status(403).json({error:'account_suspended'});
      req.zoolUser={id:r.rows[0].id,firebaseUid:r.rows[0].firebase_uid,phoneE164:r.rows[0].phone_e164,displayName:r.rows[0].display_name,authMode:'dev'};
      return handleAuthenticatedV032(req,res,next);
    }

    return res.status(401).json({error:'missing_bearer_token'});
  }catch(e:any){
    console.error('auth_error',e?.message ?? e);
    if(e?.code==='23505') return res.status(409).json({error:'phone_or_identity_already_in_use'});
    res.status(401).json({error:'invalid_or_revoked_token'});
  }
}
