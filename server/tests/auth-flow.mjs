import assert from 'node:assert/strict';
import { randomUUID, randomInt } from 'node:crypto';
import { pool } from '../dist/db.js';

assert.equal(new URL(process.env.DATABASE_URL).pathname,'/zoolchat_test');
const email=`${randomUUID()}@example.invalid`;
const phone=`+1555${String(randomInt(10_000_000,99_999_999))}`;
const password=`Zc!${randomUUID()}`;
const post=(path,body,token)=>fetch(`http://127.0.0.1:8080${path}`,{method:'POST',headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{})},body:JSON.stringify(body)});
let userId=null;
try{
  const registered=await post('/api/auth/register',{email,phoneE164:phone,password,deviceName:'Registration validation'});
  assert.equal(registered.status,201,'registration must create an account');
  const r=await registered.json();
  userId=r.user?.id;
  assert.ok(userId&&r.accessToken&&r.refreshToken,'registration must establish a session immediately');
  assert.equal(r.user.phoneE164,phone);
  const me=await fetch('http://127.0.0.1:8080/api/me',{headers:{Authorization:`Bearer ${r.accessToken}`}});
  assert.equal(me.status,200,'registration session must open authenticated app state');

  const logged=await post('/api/auth/login',{identifier:phone,password,deviceName:'Phone login validation'});
  assert.equal(logged.status,200,'phone plus password must log in');
  const l=await logged.json();
  assert.ok(l.accessToken&&l.refreshToken,'login must return a usable session');

  const refreshed=await post('/api/auth/refresh',{refreshToken:l.refreshToken,deviceName:'Refresh validation'});
  assert.equal(refreshed.status,200,'refresh token must rotate');
  const rr=await refreshed.json();
  assert.ok(rr.accessToken&&rr.refreshToken&&rr.refreshToken!==l.refreshToken,'refresh token must rotate to a new value');

  assert.equal((await post('/api/auth/logout',{refreshToken:rr.refreshToken})).status,200,'logout must revoke the active refresh token');
  assert.equal((await post('/api/auth/refresh',{refreshToken:rr.refreshToken})).status,401,'revoked refresh token must not be reusable');
  console.log('PASS: registration immediate session, phone-only login, refresh rotation, logout revocation');
}finally{
  if(userId)await pool.query('DELETE FROM users WHERE id=$1',[userId]);
  await pool.end();
}
