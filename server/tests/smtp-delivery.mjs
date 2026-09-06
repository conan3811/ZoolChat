import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { pool } from '../dist/db.js';
import { hashPassword } from '../dist/freeAuth.js';

assert.equal(new URL(process.env.DATABASE_URL).pathname,'/zoolchat_test');
assert.ok(process.env.MAIL_HOST,'MAIL_HOST must be configured for SMTP integration validation');

const email=`${randomUUID()}@example.invalid`;
const oldPassword=randomUUID();
const newPassword=randomUUID();
const user=(await pool.query("INSERT INTO users(display_name,email,email_verified_at) VALUES('SMTP Validation',$1,now()) RETURNING id",[email])).rows[0].id;
const post=(path,body)=>fetch(`http://127.0.0.1:8080${path}`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});

async function latestMail(){
  for(let i=0;i<30;i++){
    try{
      const list=await (await fetch('http://127.0.0.1:8025/api/v1/messages?limit=20')).json();
      const items=Array.isArray(list.messages)?list.messages:[];
      if(items.length){
        const full=await (await fetch(`http://127.0.0.1:8025/api/v1/message/${items[0].ID}`)).json();
        if(String(full.Text??'').includes('one-time ZoolChat code'))return full;
      }
    }catch{}
    await new Promise(r=>setTimeout(r,250));
  }
  return null;
}

try{
  await pool.query('INSERT INTO user_credentials(user_id,password_hash) VALUES($1,$2)',[user,await hashPassword(oldPassword)]);
  const requested=await post('/api/auth/password/request',{email});
  assert.equal(requested.status,202,'recovery request should be accepted when SMTP is configured');
  const mail=await latestMail();
  assert.ok(mail,'SMTP message must arrive in the validation inbox');
  const match=String(mail.Text??'').match(/\b(\d{8})\b/);
  assert.ok(match,'recovery email must contain an 8-digit one-time code');
  const reset=await post('/api/auth/password/reset',{email,code:match[1],newPassword});
  assert.equal(reset.status,200,'code delivered over SMTP must reset the password');
  const login=await post('/api/auth/login',{identifier:email,password:newPassword,deviceName:'SMTP validation'});
  assert.equal(login.status,200,'new password must authenticate after SMTP recovery');
  console.log('PASS: real SMTP transport, recovery email content, one-time code reset, post-reset authentication');
}finally{
  await pool.query('DELETE FROM users WHERE id=$1',[user]);
  await pool.end();
}
