import { applicationDefault, cert, getApps, initializeApp } from 'firebase-admin/app';
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
  if(!tokens.length)return;
  try{initFirebaseAdmin();await getMessaging().sendEachForMulticast({tokens,data,android:{priority:highPriority?'high':'normal'}});}
  catch(e){console.warn('FCM send skipped/failed',e);}
}
