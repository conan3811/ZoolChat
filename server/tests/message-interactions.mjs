import assert from 'node:assert/strict';
import { randomUUID, randomInt } from 'node:crypto';
import { pool } from '../dist/db.js';

assert.equal(new URL(process.env.DATABASE_URL).pathname,'/zoolchat_test');
const base='http://127.0.0.1:8080';
const post=(path,body,token)=>fetch(base+path,{method:'POST',headers:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{})},body:JSON.stringify(body)});
const put=(path,body,token)=>fetch(base+path,{method:'PUT',headers:{'Content-Type':'application/json',Authorization:`Bearer ${token}`},body:JSON.stringify(body)});
const del=(path,token)=>fetch(base+path,{method:'DELETE',headers:{Authorization:`Bearer ${token}`}});
const get=(path,token)=>fetch(base+path,{headers:{Authorization:`Bearer ${token}`}});
const mk=async(label)=>{
  const email=`${label}.${randomUUID()}@example.invalid`;
  const phone=`+1555${String(randomInt(10_000_000,99_999_999))}`;
  const password=`Zc!${randomUUID()}`;
  const r=await post('/api/auth/register',{email,phoneE164:phone,password,deviceName:`${label} test`});
  assert.equal(r.status,201);const j=await r.json();return {id:j.user.id,token:j.accessToken,password};
};
const users=[];
try{
  const a=await mk('interaction-a'),b=await mk('interaction-b');users.push(a.id,b.id);

  const verify=await post('/api/me/password/verify',{password:a.password},a.token);assert.equal(verify.status,200,'current password verification endpoint must work');
  const backup={version:1,publicKeyB64:'A'.repeat(120),fingerprintSha256:'ab'.repeat(32),saltB64:'c'.repeat(24),ivB64:'d'.repeat(16),ciphertextB64:'e'.repeat(96)};
  assert.equal((await put('/api/e2ee/backup',backup,a.token)).status,200,'encrypted identity backup must upload');
  assert.equal((await get('/api/e2ee/self-key',a.token)).status,200,'self identity metadata must exist after backup upload');
  assert.equal((await get(`/api/users/${a.id}/e2ee-key`,b.token)).status,200,'peer key lookup must work after backup upload');
  const restored=await get('/api/e2ee/backup',a.token);assert.equal(restored.status,200);const rb=(await restored.json()).backup;assert.equal(rb.publicKeyB64,backup.publicKeyB64);assert.equal(rb.ciphertextB64,backup.ciphertextB64);

  const direct=await post('/api/chats/direct',{userId:b.id},a.token);assert.equal(direct.status,200);const chatId=(await direct.json()).chat.id;
  const sent=await post(`/api/chats/${chatId}/messages`,{clientId:randomUUID(),type:'text',body:'hello interaction'},a.token);assert.equal(sent.status,201);const messageId=(await sent.json()).message.id;

  const reaction=await put(`/api/chats/${chatId}/messages/${messageId}/reaction`,{emoji:'👍'},b.token);assert.equal(reaction.status,200);assert.equal((await reaction.json()).reactions[0].count,1);
  const page=await get(`/api/chats/${chatId}/messages?limit=10`,b.token);assert.equal(page.status,200);const pm=(await page.json()).messages.find(x=>x.id===messageId);assert.equal(pm.reactions[0].emoji,'👍');assert.equal(pm.reactions[0].reactedByMe,true);

  const edit=await put(`/api/chats/${chatId}/messages/${messageId}`,{body:'edited interaction',encrypted:false,cryptoVersion:0},a.token);assert.equal(edit.status,200);assert.ok((await edit.json()).message.editedAt,'edited message must carry editedAt');

  const deleted=await del(`/api/chats/${chatId}/messages/${messageId}`,a.token);assert.equal(deleted.status,200);assert.equal((await deleted.json()).message.deleted,true);
  console.log('PASS: automatic E2EE backup endpoints, message reactions, edit and delete-for-everyone lifecycle');
}finally{
  if(users.length)await pool.query('DELETE FROM users WHERE id=ANY($1::uuid[])',[users]);
  await pool.end();
}
