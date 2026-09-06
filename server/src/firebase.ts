import { applicationDefault, cert, getApps, initializeApp } from 'firebase-admin/app';
import { pool } from './db.js';
import { getAuth } from 'firebase-admin/auth';
import { getMessaging } from 'firebase-admin/messaging';

export function initFirebaseAdmin(){
  if(getApps().length)return;
  const raw=process.env.FIREBASE_SERVICE_ACCOUNT_JSON;
  if(raw){initializeApp({credential:cert(JSON.parse(raw))});return;}
  try{initializeApp({credential:applicationDefault()});}
  catch(e){console.warn('Firebase Admin not initialized; auth/push unavailable until credentials are configured.');}
}
export async function verifyFirebaseToken(token:string){initFirebaseAdmin();return getAuth().verifyIdToken(token,true);}
export async function deleteFirebaseUser(uid:string){initFirebaseAdmin();await getAuth().deleteUser(uid);}
export async function sendData(tokens:string[],data:Record<string,string>,highPriority=true){
  const unique=[...new Set(tokens.filter(Boolean))];
  for(let offset=0;offset<unique.length;offset+=500){
    const batch=unique.slice(offset,offset+500);
    try{
      initFirebaseAdmin();
      const response=await getMessaging().sendEachForMulticast({tokens:batch,data,android:{priority:highPriority?'high':'normal',ttl:data.type==='call_invite'?60_000:24*60*60_000}});
      const stale=response.responses.flatMap((r,i)=>['messaging/registration-token-not-registered','messaging/invalid-registration-token'].includes(r.error?.code??'')?[batch[i]]:[]);
      if(stale.length)await pool.query('UPDATE devices SET revoked_at=now() WHERE fcm_token=ANY($1::text[])',[stale]);
    }catch{console.warn('fcm_delivery_failed');}
  }
}
