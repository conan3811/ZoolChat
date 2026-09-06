import type {Express} from 'express';
import {z} from 'zod';
import {rateLimit} from 'express-rate-limit';
import {pool,tx} from './db.js';
import {requireAuth} from './auth.js';
import {sendData} from './firebase.js';
import {installProfileV032} from './profileV032.js';
import {installStatusV032} from './statusV032.js';
export function installMeetingInvitations(app:Express,emit:(id:string,event:unknown)=>void) {
 installProfileV032(app);
 installStatusV032(app,emit);
 const limit=rateLimit({windowMs:60_000,limit:20,standardHeaders:'draft-8',legacyHeaders:false});

 async function emitWaitingCount(meetingId:string,hostId:string){
  const count=await pool.query("SELECT count(*)::int n FROM meeting_participants WHERE meeting_id=$1 AND status='waiting'",[meetingId]);
  emit(hostId,{type:'meeting.waiting_changed',data:{meetingId,waiting:Number(count.rows[0]?.n??0)}});
 }

 // v0.32 authoritative admit/deny handlers are registered before the legacy routes. Besides the
 // decision itself they tell the host client whether anyone is still waiting, which controls the
 // repeated waiting-room alert without disturbing LiveKit call audio.
 app.post('/api/meetings/:id/participants/:userId/admit',requireAuth,limit,async(req,res)=>{
  const id=z.string().uuid().parse(req.params.id),target=z.string().uuid().parse(req.params.userId),uid=req.zoolUser!.id;
  const m=await pool.query("SELECT host_id FROM meetings WHERE id=$1 AND status='active'",[id]);
  if(!m.rowCount)return res.status(404).json({error:'meeting_not_found'});
  if(String(m.rows[0].host_id)!==uid)return res.status(403).json({error:'host_required'});
  const changed=await pool.query("UPDATE meeting_participants SET status='admitted',admitted_at=now(),joined_at=COALESCE(joined_at,now()),left_at=NULL WHERE meeting_id=$1 AND user_id=$2 AND status='waiting' RETURNING user_id",[id,target]);
  if(changed.rowCount)emit(target,{type:'meeting.admitted',data:{meetingId:id}});
  await emitWaitingCount(id,uid);
  res.json({ok:true});
 });
 app.post('/api/meetings/:id/participants/:userId/deny',requireAuth,limit,async(req,res)=>{
  const id=z.string().uuid().parse(req.params.id),target=z.string().uuid().parse(req.params.userId),uid=req.zoolUser!.id;
  const m=await pool.query("SELECT host_id FROM meetings WHERE id=$1 AND status='active'",[id]);
  if(!m.rowCount)return res.status(404).json({error:'meeting_not_found'});
  if(String(m.rows[0].host_id)!==uid)return res.status(403).json({error:'host_required'});
  const changed=await pool.query("UPDATE meeting_participants SET status='denied',left_at=now() WHERE meeting_id=$1 AND user_id=$2 AND status='waiting' RETURNING user_id",[id,target]);
  if(changed.rowCount)emit(target,{type:'meeting.denied',data:{meetingId:id}});
  await emitWaitingCount(id,uid);
  res.json({ok:true});
 });

 app.post('/api/meetings/:id/invitations',requireAuth,limit,async(req,res)=>{
  const id=z.string().uuid().parse(req.params.id),uid=req.zoolUser!.id;
  const b=z.object({kind:z.enum(['username','phone','email']),value:z.string().trim().min(3).max(254)}).parse(req.body);
  const m=await pool.query("SELECT * FROM meetings WHERE id=$1 AND host_id=$2 AND status='active'",[id,uid]);
  if(!m.rowCount)return res.status(403).json({error:'host_required'});
  const r=await pool.query("SELECT id FROM users WHERE account_status='active' AND id<>$1 AND (($2='username' AND lower(username)=lower($3)) OR ($2='phone' AND phone_e164=$3) OR ($2='email' AND lower(email)=lower($3) AND email_verified_at IS NOT NULL))",[uid,b.kind,b.value.replace(/^@/,'')]);
  if(r.rowCount){
   const recipient=r.rows[0].id;
   const blocked=await pool.query('SELECT 1 FROM blocks WHERE (blocker_id=$1 AND blocked_id=$2) OR (blocker_id=$2 AND blocked_id=$1)',[uid,recipient]);
   if(!blocked.rowCount){
    const invite=await pool.query('INSERT INTO meeting_invitations(meeting_id,recipient_id) VALUES($1,$2) ON CONFLICT(meeting_id,recipient_id) DO NOTHING RETURNING id',[id,recipient]);
    if(invite.rowCount){
     const data={type:'meeting_invite',inviteId:invite.rows[0].id,title:m.rows[0].title};
     emit(recipient,{type:'meeting.invite',data});
     const tokens=await pool.query('SELECT fcm_token FROM devices WHERE user_id=$1 AND revoked_at IS NULL',[recipient]);
     await sendData(tokens.rows.map(x=>x.fcm_token),data);
    }
   }
  }
  // Same reply for nonexistent, hidden, blocked, and existing recipients.
  res.status(202).json({ok:true});
 });
 app.get('/api/meeting-invitations',requireAuth,async(req,res)=>{
  const r=await pool.query(`SELECT i.id,m.id meeting_id,m.title,m.code,
   CASE WHEN i.expires_at<=now() OR m.status<>'active' THEN 'expired'
        WHEN i.status='pending' THEN 'pending' ELSE COALESCE(p.status,'waiting') END status
   FROM meeting_invitations i JOIN meetings m ON m.id=i.meeting_id
   LEFT JOIN meeting_participants p ON p.meeting_id=m.id AND p.user_id=i.recipient_id
   WHERE i.recipient_id=$1 ORDER BY i.created_at DESC LIMIT 50`,[req.zoolUser!.id]);
  res.json({invitations:r.rows});
 });
 app.post('/api/meeting-invitations/:id/accept',requireAuth,limit,async(req,res)=>{
  const id=z.string().uuid().parse(req.params.id),uid=req.zoolUser!.id;
  const result=await tx(async c=>{
   const r=await c.query("SELECT i.*,m.code,m.title,m.host_id FROM meeting_invitations i JOIN meetings m ON m.id=i.meeting_id WHERE i.id=$1 AND i.recipient_id=$2 AND i.expires_at>now() AND m.status='active' FOR UPDATE OF i,m",[id,uid]);
   if(!r.rowCount)return null;const i=r.rows[0];
   if(i.status==='pending'){
    await c.query("INSERT INTO meeting_participants(meeting_id,user_id,role,status) VALUES($1,$2,'member','waiting') ON CONFLICT(meeting_id,user_id) DO NOTHING",[i.meeting_id,uid]);
    await c.query("UPDATE meeting_invitations SET status='accepted' WHERE id=$1",[id]);
   }
   return {meetingId:i.meeting_id,code:i.code,title:i.title,hostId:i.host_id};
  });
  if(!result)return res.status(404).json({error:'invitation_unavailable'});
  emit(result.hostId,{type:'meeting.join_request',data:{meetingId:result.meetingId,userId:uid,displayName:req.zoolUser!.displayName}});
  // No participant token is issued here; only admission can authorize joining.
  res.status(202).json({meetingId:result.meetingId,code:result.code,title:result.title});
 });
}
