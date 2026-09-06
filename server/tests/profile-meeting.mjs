import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {pool} from '../dist/db.js';
import {createSession} from '../dist/freeAuth.js';
assert.equal(new URL(process.env.DATABASE_URL).pathname,'/zoolchat_test');
const users=[];let meeting,chat;
try{
 for(let i=0;i<3;i++){const username=randomUUID().replaceAll('-','').slice(0,20);const id=(await pool.query("INSERT INTO users(display_name,username,email) VALUES('Validation',$1,$2) RETURNING id",[username,`${username}@example.invalid`])).rows[0].id;users.push({id,username,...await createSession(id,'Validation')});}
 const [host,guest,outsider]=users;
 const call=async(user,path,body)=>{const r=await fetch(`http://127.0.0.1:8080${path}`,{method:body?'POST':'GET',headers:{Authorization:`Bearer ${user.accessToken}`,'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined});return {status:r.status,data:await r.json()};};
 chat=(await pool.query("INSERT INTO chats(type) VALUES('group') RETURNING id")).rows[0].id;
 for(const u of [host,guest])await pool.query('INSERT INTO chat_members(chat_id,user_id) VALUES($1,$2)',[chat,u.id]);
 assert.equal((await call(outsider,`/api/users/${guest.id}/profile`)).status,404);
 await pool.query('INSERT INTO user_privacy_settings(user_id,show_online,show_last_seen) VALUES($1,false,false)',[guest.id]);
 const p=await call(host,`/api/users/${guest.id}/profile`);assert.equal(p.status,200);assert.equal(p.data.profile.online,null);assert.equal(p.data.profile.lastSeenAt,null);assert.ok(!('email' in p.data.profile));assert.ok(!('phoneE164' in p.data.profile));
 const created=await call(host,'/api/meetings',{title:'Validation meeting',requireApproval:true});assert.equal(created.status,201);meeting=created.data.meeting.id;
 assert.equal((await call(outsider,`/api/meetings/${meeting}/invitations`,{kind:'username',value:guest.username})).status,403);
 assert.equal((await call(host,`/api/meetings/${meeting}/invitations`,{kind:'username',value:guest.username})).status,202);
 const list=await call(guest,'/api/meeting-invitations');const invite=list.data.invitations[0].id;
 assert.equal((await call(outsider,`/api/meeting-invitations/${invite}/accept`,{})).status,404);
 const accepted=await call(guest,`/api/meeting-invitations/${invite}/accept`,{});assert.equal(accepted.status,202);assert.ok(!('participantToken' in accepted.data));
 assert.equal((await call(guest,`/api/meetings/${meeting}/join-status`)).data.status,'waiting');
 assert.equal((await call(outsider,`/api/meetings/${meeting}/participants/${guest.id}/admit`,{})).status,403);
 assert.equal((await call(host,`/api/meetings/${meeting}/participants/${guest.id}/deny`,{})).status,200);
 assert.equal((await call(guest,'/api/meetings/join',{code:created.data.meeting.code})).status,403,'Denied guest cannot bypass host with join code');
 console.log('PASS: private profile, relation authorization, host-only invite/admit, recipient-only acceptance, waiting without token, denial enforcement');
}finally{if(meeting)await pool.query('DELETE FROM meetings WHERE id=$1',[meeting]);if(chat)await pool.query('DELETE FROM chats WHERE id=$1',[chat]);for(const u of users)await pool.query('DELETE FROM users WHERE id=$1',[u.id]);await pool.end();}
