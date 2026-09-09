import assert from 'node:assert/strict';
import { randomUUID,randomInt } from 'node:crypto';
import { pool } from '../dist/db.js';
import { createSession,hashPassword } from '../dist/freeAuth.js';
assert.equal(new URL(process.env.DATABASE_URL).pathname,'/zoolchat_test');
const email=`${randomUUID()}@example.invalid`, password=randomUUID();
const user=(await pool.query("INSERT INTO users(display_name,email) VALUES('Validation',$1) RETURNING id",[email])).rows[0].id;
const post=(path,body)=>fetch(`http://127.0.0.1:8080${path}`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
const code=String(randomInt(10_000_000,99_999_999));
try {
  await pool.query('INSERT INTO user_credentials(user_id,password_hash) VALUES($1,$2)',[user,await hashPassword(password)]);
  const session=await createSession(user,'Validation');
  await pool.query("INSERT INTO email_challenges(user_id,email,purpose,code_hash) VALUES($1,$2,'recover',$3)",[user,email,await hashPassword(code)]);
  const request={email,code,newPassword:randomUUID()};
  await pool.query("UPDATE email_challenges SET expires_at=now()-interval '1 second' WHERE user_id=$1",[user]);
  assert.equal((await post('/api/auth/password/reset',request)).status,400,'Expired code cannot recover');
  await pool.query("UPDATE email_challenges SET expires_at=now()+interval '10 minutes' WHERE user_id=$1",[user]);
  const wrong=code==='00000000'?'11111111':'00000000';
  for(let i=0;i<5;i++)assert.equal((await post('/api/auth/password/reset',{...request,code:wrong})).status,400);
  assert.equal((await post('/api/auth/password/reset',request)).status,400,'Attempt limit prevents guessing');
  await pool.query('UPDATE email_challenges SET attempts=0 WHERE user_id=$1',[user]);
  assert.equal((await post('/api/auth/password/reset',request)).status,200,'Mailbox code proves ownership even when the email was not previously verified');
  assert.ok((await pool.query('SELECT email_verified_at FROM users WHERE id=$1',[user])).rows[0].email_verified_at,'Successful recovery verifies the mailbox');
  assert.equal((await fetch('http://127.0.0.1:8080/api/me',{headers:{Authorization:`Bearer ${session.accessToken}`}})).status,401,'Old session revoked');
  assert.equal((await post('/api/auth/password/reset',request)).status,400,'Code cannot be reused');
  console.log('PASS: mailbox ownership, expiry, attempt limit, reset, verification, session revocation, one-time use');
}finally{await pool.query('DELETE FROM users WHERE id=$1',[user]);await pool.end();}
