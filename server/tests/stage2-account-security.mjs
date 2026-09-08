import assert from 'node:assert/strict';
import { randomUUID, randomInt } from 'node:crypto';
import { pool } from '../dist/db.js';

assert.equal(new URL(process.env.DATABASE_URL).pathname, '/zoolchat_test');
assert.ok(process.env.MAIL_HOST, 'SMTP must be configured for stage-two validation');
const base='http://127.0.0.1:8080';
const made=[];
const post=(path,body,token)=>fetch(base+path,{method:'POST',headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{})},body:JSON.stringify(body??{})});
const get=(path,token)=>fetch(base+path,{headers:{Authorization:`Bearer ${token}`}});

async function latestCode(needle){
  for(let i=0;i<40;i++){
    try{
      const list=await (await fetch('http://127.0.0.1:8025/api/v1/messages?limit=30')).json();
      const items=Array.isArray(list.messages)?list.messages:[];
      for(const item of items){
        const full=await (await fetch(`http://127.0.0.1:8025/api/v1/message/${item.ID}`)).json();
        const text=String(full.Text??'');
        if(text.includes(needle)){
          const m=text.match(/\b(\d{8})\b/);if(m)return m[1];
        }
      }
    }catch{}
    await new Promise(r=>setTimeout(r,250));
  }
  throw new Error(`mail code not found: ${needle}`);
}

async function account(label){
  const email=`${randomUUID()}@example.invalid`;
  const phone=`+1555${String(randomInt(10_000_000,99_999_999))}`;
  const password=`Zc!${randomUUID()}`;
  const username=`s2${randomUUID().replaceAll('-','').slice(0,18)}`;
  const r=await post('/api/auth/register',{email,phoneE164:phone,password,username,deviceName:`${label} phone`});
  assert.equal(r.status,201);
  const j=await r.json();made.push(j.user.id);
  await pool.query('UPDATE users SET email_verified_at=now() WHERE id=$1',[j.user.id]);
  return {id:j.user.id,email,phone,password,username,token:j.accessToken};
}

async function finishLogin(a,deviceName){
  const first=await post('/api/auth/login',{identifier:a.phone,password:a.password,deviceName});
  assert.equal(first.status,202,'2FA-enabled password login must pause before session creation');
  const challenge=await first.json();
  assert.equal(challenge.twoFactorRequired,true);
  assert.ok(challenge.challengeToken&&!challenge.accessToken,'password step must not return a session when 2FA is enabled');
  const code=await latestCode('complete your sign in');
  const done=await post('/api/auth/login/2fa',{challengeToken:challenge.challengeToken,code,deviceName});
  assert.equal(done.status,200,'valid second factor must complete login');
  return done.json();
}

try{
  const a=await account('Stage2 A');
  const enable=await post('/api/me/2fa/enable/request',{password:a.password},a.token);
  assert.equal(enable.status,202,'verified account must be able to request 2FA enablement');
  const e=await enable.json();
  const enableCode=await latestCode('enable two-step verification');
  const confirmed=await post('/api/me/2fa/enable/confirm',{challengeToken:e.challengeToken,code:enableCode},a.token);
  assert.equal(confirmed.status,200,'emailed code must enable two-step verification');

  const loginStart=await post('/api/auth/login',{identifier:a.phone,password:a.password,deviceName:'Stage2 second phone'});
  assert.equal(loginStart.status,202);
  const challenge=await loginStart.json();
  const wrong=await post('/api/auth/login/2fa',{challengeToken:challenge.challengeToken,code:'00000000',deviceName:'Stage2 second phone'});
  assert.equal(wrong.status,401,'wrong second factor must never create a session');
  const loginCode=await latestCode('complete your sign in');
  const completed=await post('/api/auth/login/2fa',{challengeToken:challenge.challengeToken,code:loginCode,deviceName:'Stage2 second phone'});
  assert.equal(completed.status,200);
  const session2=await completed.json();
  assert.ok(session2.accessToken&&session2.refreshToken);

  const devices=await (await get('/api/devices',session2.accessToken)).json();
  assert.ok(Array.isArray(devices.devices)&&devices.devices.some(d=>d.current===true),'device list must identify the current authenticated session');
  assert.ok(devices.devices.some(d=>d.current===false),'device list must include the older signed-in device');
  const logoutOthers=await post('/api/devices/logout-others',{},session2.accessToken);
  assert.equal(logoutOthers.status,200,'current device must be able to revoke other sessions');
  const lo=await logoutOthers.json();assert.ok(Number(lo.revoked)>=1);
  assert.equal((await get('/api/me',a.token)).status,401,'revoked old session must stop authorizing requests');
  assert.equal((await get('/api/me',session2.accessToken)).status,200,'current session must remain valid after logout-others');

  const viewer=await account('Stage2 viewer');
  const resolved=await get(`/api/users/by-username/${encodeURIComponent(a.username)}`,viewer.token);
  assert.equal(resolved.status,200,'shareable username profile link must resolve for another authenticated user');
  const ru=(await resolved.json()).user;assert.equal(ru.userId,a.id);assert.equal(ru.username,a.username);
  const publicProfile=await get(`/api/users/${a.id}/profile`,viewer.token);
  assert.equal(publicProfile.status,200,'profile link must open safe profile data even before a direct chat exists');
  const p=(await publicProfile.json()).profile;
  assert.equal(p.id,a.id);assert.equal(p.username,a.username);
  assert.equal(Object.hasOwn(p,'email'),false,'shared profile must never expose registration email');
  assert.equal(Object.hasOwn(p,'phoneE164'),false,'shared profile must never expose phone number');

  const status=await (await get('/api/me/2fa',session2.accessToken)).json();
  assert.equal(status.enabled,true);assert.equal(status.emailVerified,true);
  console.log('PASS: stage2 profile link, current-device sessions, logout-others and real email 2FA');
}finally{
  for(const id of made.reverse())await pool.query('DELETE FROM users WHERE id=$1',[id]).catch(()=>{});
  await pool.end();
}
