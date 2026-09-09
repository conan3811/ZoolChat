import assert from 'node:assert/strict';
import { randomUUID, randomInt } from 'node:crypto';
import { pool } from '../dist/db.js';

assert.equal(new URL(process.env.DATABASE_URL).pathname, '/zoolchat_test');
const base='http://127.0.0.1:8080';
const post=(path,body,token)=>fetch(base+path,{method:'POST',headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{})},body:JSON.stringify(body??{})});
const put=(path,body,token)=>fetch(base+path,{method:'PUT',headers:{'Content-Type':'application/json',Authorization:`Bearer ${token}`},body:JSON.stringify(body??{})});
const get=(path,token)=>fetch(base+path,{headers:{Authorization:`Bearer ${token}`}});
const made=[];

async function account(label){
  const email=`${label}.${randomUUID()}@example.invalid`;
  const phone=`+1555${String(randomInt(10_000_000,99_999_999))}`;
  const password=`Zc!${randomUUID()}`;
  const r=await post('/api/auth/register',{email,phoneE164:phone,password,deviceName:`${label} stage3`});
  assert.equal(r.status,201);const j=await r.json();made.push(j.user.id);
  const fp='AB:'.repeat(31)+'AB';
  const key=Buffer.from(`stage3-${label}-${randomUUID()}`).toString('base64url').padEnd(120,'A');
  const kr=await put('/api/e2ee/key',{algorithm:'P256-ECDH',publicKeyB64:key,fingerprintSha256:fp},j.accessToken);
  assert.equal(kr.status,200);
  return {id:j.user.id,token:j.accessToken};
}

try{
  const owner=await account('owner');
  const member=await account('member');
  const outsider=await account('outsider');
  const noKeyEmail=`nokey.${randomUUID()}@example.invalid`;
  const noKeyPhone=`+1555${String(randomInt(10_000_000,99_999_999))}`;
  const noKeyReg=await post('/api/auth/register',{email:noKeyEmail,phoneE164:noKeyPhone,password:`Zc!${randomUUID()}`,deviceName:'no-key stage3'});
  assert.equal(noKeyReg.status,201);const noKey=await noKeyReg.json();made.push(noKey.user.id);

  const created=await post('/api/groups',{title:'Stage 3 encrypted group',description:'',memberUserIds:[member.id]},owner.token);
  assert.equal(created.status,201);const chatId=(await created.json()).group.id;

  const ownerKeys=await get(`/api/groups/${chatId}/e2ee-keys`,owner.token);
  assert.equal(ownerKeys.status,200);const ok=await ownerKeys.json();
  assert.deepEqual(new Set(ok.keys.map(x=>x.userId)),new Set([owner.id,member.id]));
  assert.deepEqual(ok.missingUserIds,[]);
  assert.equal((await get(`/api/groups/${chatId}/e2ee-keys`,member.token)).status,200);
  assert.equal((await get(`/api/groups/${chatId}/e2ee-keys`,outsider.token)).status,403,'non-members must not enumerate group E2EE keys');

  const envelope='zcg2:'+Buffer.from(JSON.stringify({v:2,payload:'opaque-ciphertext',wraps:{[owner.id]:'owner-wrap',[member.id]:'member-wrap'}})).toString('base64url');
  const sent=await post(`/api/chats/${chatId}/messages`,{clientId:randomUUID(),type:'text',body:envelope,encrypted:true,cryptoVersion:2},owner.token);
  assert.equal(sent.status,201);const message=(await sent.json()).message;
  assert.equal(message.body,envelope);assert.equal(message.encrypted,true);assert.equal(message.cryptoVersion,2);

  const page=await get(`/api/chats/${chatId}/messages?limit=10`,member.token);
  assert.equal(page.status,200);const received=(await page.json()).messages.find(x=>x.id===message.id);
  assert.equal(received.body,envelope,'server must round-trip group ciphertext without plaintext transformation');
  assert.equal(received.cryptoVersion,2);
  const search=await get(`/api/chats/${chatId}/search?q=opaque-ciphertext`,member.token);
  assert.equal(search.status,200);assert.equal((await search.json()).messages.length,0,'encrypted group bodies must never enter plaintext server search');

  const addMissing=await post(`/api/groups/${chatId}/members`,{userIds:[noKey.user.id]},owner.token);
  assert.equal(addMissing.status,200);
  const after=await (await get(`/api/groups/${chatId}/e2ee-keys`,owner.token)).json();
  assert.ok(after.missingUserIds.includes(noKey.user.id),'missing member keys must be explicit so clients fail closed instead of plaintext fallback');

  console.log('PASS: stage3 group E2EE membership-scoped keys, opaque v2 transport and fail-closed missing-key discovery');
} finally {
  for(const id of made.reverse()) await pool.query('DELETE FROM users WHERE id=$1',[id]).catch(()=>{});
  await pool.end();
}
