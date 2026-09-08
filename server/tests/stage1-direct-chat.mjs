// Stage-one regression: search-result Message action must remain idempotent and immediately usable.
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
  const r=await fetch('http://127.0.0.1:8080/api/auth/register',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({email,phoneE164:phone,password,deviceName:`${label} direct chat validation`})});
  assert.equal(r.status,201);
  const j=await r.json();made.push(j.user.id);return {id:j.user.id,token:j.accessToken};
}
try{
  const a=await account('A');
  const b=await account('B');
  const attempts=await Promise.all(Array.from({length:4},()=>post('/api/chats/direct',{userId:b.id},a.token)));
  assert.ok(attempts.every(r=>r.status===200),'rapid Message taps must all resolve successfully');
  const payloads=await Promise.all(attempts.map(r=>r.json()));
  const ids=new Set(payloads.map(x=>x.chat?.id));
  assert.equal(ids.size,1,'all rapid Message taps must resolve to one direct chat');
  const db=await pool.query('SELECT count(*)::int n FROM direct_chats WHERE (user_a=$1 AND user_b=$2) OR (user_a=$2 AND user_b=$1)',[a.id,b.id]);
  assert.equal(db.rows[0].n,1,'server must persist exactly one direct chat');
  console.log('PASS: search-result Message action is idempotent under rapid/concurrent taps');
}finally{
  for(const id of made.reverse())await pool.query('DELETE FROM users WHERE id=$1',[id]).catch(()=>{});
  await pool.end();
}
