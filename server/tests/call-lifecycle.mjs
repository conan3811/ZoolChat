import assert from 'node:assert/strict';
import { randomUUID, randomInt } from 'node:crypto';
import { pool } from '../dist/db.js';

assert.equal(new URL(process.env.DATABASE_URL).pathname,'/zoolchat_test');
const post=(path,body,token)=>fetch(`http://127.0.0.1:8080${path}`,{method:'POST',headers:{'Content-Type':'application/json',Authorization:`Bearer ${token}`},body:JSON.stringify(body??{})});
const made=[];
async function account(label){
  const email=`${randomUUID()}@example.invalid`;
  const phone=`+1555${String(randomInt(10_000_000,99_999_999))}`;
  const password=`Zc!${randomUUID()}`;
  const r=await fetch('http://127.0.0.1:8080/api/auth/register',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({email,phoneE164:phone,password,deviceName:`${label} call validation`})});
  assert.equal(r.status,201);
  const j=await r.json();
  made.push(j.user.id);
  return {id:j.user.id,token:j.accessToken};
}
try{
  const caller=await account('Caller');
  const callee=await account('Callee');
  const outsider=await account('Outsider');

  const created=await post('/api/calls',{peerUserIds:[callee.id],type:'audio'},caller.token);
  assert.equal(created.status,201,'caller must be able to create an audio call');
  const c=await created.json();
  assert.ok(c.callId&&c.participantToken&&c.serverUrl,'call creation must return LiveKit connection material');
  assert.equal(c.peerUserId,callee.id);
  assert.equal(c.e2eeMode,'client-derived-p256');

  assert.equal((await post(`/api/calls/${c.callId}/accept`,{},outsider.token)).status,404,'non-participant must not accept a call');
  const accepted=await post(`/api/calls/${c.callId}/accept`,{},callee.token);
  assert.equal(accepted.status,200,'invited callee must accept');
  const a=await accepted.json();
  assert.ok(a.participantToken&&a.serverUrl,'accepted callee must receive LiveKit connection material');
  let db=await pool.query('SELECT status,answered_at FROM calls WHERE id=$1',[c.callId]);
  assert.equal(db.rows[0].status,'active');
  assert.ok(db.rows[0].answered_at);
  assert.equal((await pool.query("SELECT status FROM call_participants WHERE call_id=$1 AND user_id=$2",[c.callId,callee.id])).rows[0].status,'joined');

  assert.equal((await post(`/api/calls/${c.callId}/end`,{},caller.token)).status,200,'participant must end active call');
  db=await pool.query('SELECT status,ended_at FROM calls WHERE id=$1',[c.callId]);
  assert.equal(db.rows[0].status,'ended');
  assert.ok(db.rows[0].ended_at);
  assert.equal((await post(`/api/calls/${c.callId}/accept`,{},callee.token)).status,409,'ended call must not be accepted again');
  const timeline=await pool.query("SELECT 1 FROM messages WHERE client_id=$1 AND type='call'",[`call:${c.callId}`]);
  assert.equal(timeline.rowCount,1,'call lifecycle must be synchronized into chat timeline');

  const second=await post('/api/calls',{peerUserIds:[callee.id],type:'video'},caller.token);
  assert.equal(second.status,201);
  const d=await second.json();
  assert.equal((await post(`/api/calls/${d.callId}/decline`,{},callee.token)).status,200,'callee must be able to decline');
  db=await pool.query('SELECT status,ended_at FROM calls WHERE id=$1',[d.callId]);
  assert.equal(db.rows[0].status,'declined');
  assert.ok(db.rows[0].ended_at);
  console.log('PASS: call create/authorization/accept/LiveKit token/end/reaccept guard/timeline/decline lifecycle');
}finally{
  for(const id of made.reverse())await pool.query('DELETE FROM users WHERE id=$1',[id]).catch(()=>{});
  await pool.end();
}
