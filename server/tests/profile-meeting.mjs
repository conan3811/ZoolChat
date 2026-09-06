import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {pool} from '../dist/db.js';
import {createSession} from '../dist/freeAuth.js';
assert.equal(new URL(process.env.DATABASE_URL).pathname,'/zoolchat_test');
const users=[];let meeting,chat,messageId;
try{
 for(let i=0;i<3;i++){const username=randomUUID().replaceAll('-','').slice(0,20);const id=(await pool.query("INSERT INTO users(display_name,username,email,about) VALUES('Validation',$1,$2,'Private validation about') RETURNING id",[username,`${username}@example.invalid`])).rows[0].id;users.push({id,username,...await createSession(id,'Validation')});}
 const [host,guest,outsider]=users;
 const call=async(user,path,body,method)=>(async()=>{const m=method??(body?'POST':'GET');const r=await fetch(`http://127.0.0.1:8080${path}`,{method:m,headers:{Authorization:`Bearer ${user.accessToken}`,'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined});return {status:r.status,data:await r.json()};})();
 chat=(await pool.query("INSERT INTO chats(type) VALUES('group') RETURNING id")).rows[0].id;
 for(const u of [host,guest])await pool.query('INSERT INTO chat_members(chat_id,user_id) VALUES($1,$2)',[chat,u.id]);
 assert.equal((await call(outsider,`/api/users/${guest.id}/profile`)).status,404);
 const privacy=await call(guest,'/api/privacy',{showOnline:false,showLastSeen:false,profilePhotoVisibility:'nobody',aboutVisibility:'nobody',messagePreviews:false},'PUT');
 assert.equal(privacy.status,200);assert.equal(privacy.data.privacy.profilePhotoVisibility,'nobody');assert.equal(privacy.data.privacy.aboutVisibility,'nobody');assert.equal(privacy.data.privacy.messagePreviews,false);
 const p=await call(host,`/api/users/${guest.id}/profile`);assert.equal(p.status,200);assert.equal(p.data.profile.online,null);assert.equal(p.data.profile.lastSeenAt,null);assert.equal(p.data.profile.about,null);assert.equal(p.data.profile.photoUrl,null);assert.ok(!('email' in p.data.profile));assert.ok(!('phoneE164' in p.data.profile));
 const me=await call(guest,'/api/me');assert.equal(me.status,200);assert.equal(me.data.user.about,'Private validation about');assert.ok('email' in me.data.user);
 const msg=(await pool.query("INSERT INTO messages(chat_id,sender_id,client_id,type,body,encrypted) VALUES($1,$2,$3,'text','See https://example.invalid/resource',false) RETURNING id",[chat,host.id,randomUUID()])).rows[0];messageId=msg.id;
 const shared=await call(guest,`/api/chats/${chat}/shared?kind=links`);assert.equal(shared.status,200);assert.equal(shared.data.items.length,1);assert.match(shared.data.items[0].body,/example\.invalid/);
 assert.equal((await call(outsider,`/api/chats/${chat}/shared?kind=links`)).status,403);
 const token=`validation-${randomUUID()}-token`;
 await pool.query("INSERT INTO devices(user_id,fcm_token,platform) VALUES($1,$2,'android')",[guest.id,token]);
 assert.equal((await call(guest,'/api/devices/fcm/revoke',{token})).status,200);
 assert.ok((await pool.query('SELECT revoked_at FROM devices WHERE fcm_token=$1',[token])).rows[0].revoked_at);
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
 console.log('PASS: profile privacy, shared-content membership, FCM revoke, meeting invite and waiting-room authorization');
}finally{if(meeting)await pool.query('DELETE FROM meetings WHERE id=$1',[meeting]);if(chat)await pool.query('DELETE FROM chats WHERE id=$1',[chat]);for(const u of users)await pool.query('DELETE FROM users WHERE id=$1',[u.id]);await pool.end();}
