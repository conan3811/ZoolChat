import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { pool } from '../dist/db.js';
import { createSession } from '../dist/freeAuth.js';

const base = 'http://127.0.0.1:8080';
assert.equal(new URL(process.env.DATABASE_URL).pathname, '/zoolchat_test', 'Use isolated test DB only');
const users=[];
try {
  for (let i=0;i<3;i++) {
    const r=await pool.query("INSERT INTO users(display_name) VALUES('Validation user') RETURNING id");
    const id=r.rows[0].id;users.push({id,...await createSession(id,'Validation')});
  }
  const [sender,reader,outsider]=users;
  const c=await pool.query("INSERT INTO chats(type) VALUES('direct') RETURNING id");const chat=c.rows[0].id;
  for(const u of [sender,reader]) await pool.query('INSERT INTO chat_members(chat_id,user_id) VALUES($1,$2)',[chat,u.id]);
  const ids=[];
  for (const at of ['2026-01-01T00:00:00Z','2026-01-02T00:00:00Z']) {
    const m=await pool.query("INSERT INTO messages(chat_id,sender_id,client_id,type,body,created_at) VALUES($1,$2,$3,'text','Validation',$4) RETURNING id",[chat,sender.id,randomUUID(),at]);
    ids.push(m.rows[0].id);await pool.query('INSERT INTO message_receipts(message_id,user_id) VALUES($1,$2)',[m.rows[0].id,reader.id]);
  }
  await pool.query('INSERT INTO user_privacy_settings(user_id,read_receipts) VALUES($1,false)',[reader.id]);
  const read=(user,id)=>fetch(`${base}/api/chats/${chat}/read`,{method:'POST',headers:{Authorization:`Bearer ${user.accessToken}`,'Content-Type':'application/json'},body:JSON.stringify({messageId:id})});
  assert.equal((await read(outsider,ids[1])).status,403);
  assert.equal((await read(reader,ids[1])).status,200);
  assert.equal((await pool.query('SELECT count(*)::int n FROM message_receipts WHERE user_id=$1 AND read_at IS NOT NULL',[reader.id])).rows[0].n,0,'Private read receipts must not leak through history');
  assert.equal((await read(reader,ids[0])).status,200);
  const cursor=(await pool.query('SELECT last_read_at FROM chat_members WHERE chat_id=$1 AND user_id=$2',[chat,reader.id])).rows[0].last_read_at;
  assert.equal(cursor.toISOString(),'2026-01-02T00:00:00.000Z','Late older read cannot roll cursor back');
  const response=await fetch(`${base}/api/chats`,{headers:{Authorization:`Bearer ${reader.accessToken}`}});const body=await response.json();
  assert.equal(body.chats.find(c=>c.id===chat).unread,0,'Private read must still clear unread');
  await pool.query('UPDATE user_privacy_settings SET read_receipts=true WHERE user_id=$1',[reader.id]);
  assert.equal((await read(reader,ids[1])).status,200);
  assert.equal((await pool.query('SELECT count(*)::int n FROM message_receipts WHERE user_id=$1 AND read_at IS NOT NULL',[reader.id])).rows[0].n,2);
  await pool.query('DELETE FROM chats WHERE id=$1',[chat]);
  console.log('PASS: authorization, private read, unread clearing, monotonic cursor, public receipts');
} finally {
  for(const u of users) await pool.query('DELETE FROM users WHERE id=$1',[u.id]);
  await pool.end();
}
