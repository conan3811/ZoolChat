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

export async function requireAuth(req:Request,res:Response,next:NextFunction){
  try{
    const h=req.header('authorization');
    if(h?.startsWith('Bearer ')) {
      const raw=h.slice(7).trim();
      const free=await resolveAccessToken(raw);
      if(free){
        req.zoolUser={id:free.id,firebaseUid:free.firebase_uid??undefined,phoneE164:free.phone_e164??undefined,displayName:free.display_name,authMode:'free'};
        return next();
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
        return next();
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
      return next();
    }

    return res.status(401).json({error:'missing_bearer_token'});
  }catch(e:any){
    console.error('auth_error',e?.message ?? e);
    if(e?.code==='23505') return res.status(409).json({error:'phone_or_identity_already_in_use'});
    res.status(401).json({error:'invalid_or_revoked_token'});
  }
}
