import type {Express} from 'express';
import path from 'node:path';
import fs from 'node:fs';
import {S3Client,GetObjectCommand} from '@aws-sdk/client-s3';
import {z} from 'zod';
import {pool} from './db.js';
import {requireAuth} from './auth.js';

type Visibility='everyone'|'contacts'|'nobody';

const mediaDir=path.resolve(process.env.MEDIA_DIR??'./data/media');
const r2Endpoint=process.env.R2_ENDPOINT?.trim();
const r2AccessKeyId=process.env.R2_ACCESS_KEY_ID?.trim();
const r2SecretAccessKey=process.env.R2_SECRET_ACCESS_KEY?.trim();
const r2Bucket=process.env.R2_BUCKET?.trim();
const r2Enabled=Boolean(r2Endpoint&&r2AccessKeyId&&r2SecretAccessKey&&r2Bucket);
const r2=r2Enabled?new S3Client({region:'auto',endpoint:r2Endpoint,credentials:{accessKeyId:r2AccessKeyId!,secretAccessKey:r2SecretAccessKey!}}):null;
fs.mkdirSync(mediaDir,{recursive:true});

async function directRelation(a:string,b:string){
 if(a===b)return true;
 const [x,y]=[a,b].sort();
 const r=await pool.query('SELECT 1 FROM direct_chats WHERE user_a=$1 AND user_b=$2 LIMIT 1',[x,y]);
 return Boolean(r.rowCount);
}
async function sharedRelation(a:string,b:string){
 if(a===b)return true;
 const r=await pool.query('SELECT 1 FROM chat_members x JOIN chat_members y ON y.chat_id=x.chat_id WHERE x.user_id=$1 AND y.user_id=$2 LIMIT 1',[a,b]);
 return Boolean(r.rowCount);
}
async function canSee(viewer:string,target:string,visibility:Visibility){
 if(viewer===target||visibility==='everyone')return true;
 if(visibility==='nobody')return false;
 return directRelation(viewer,target);
}
function selfJson(r:any){
 return {id:r.id,displayName:r.display_name,username:r.username??null,email:r.email??null,emailVerified:Boolean(r.email_verified_at),phoneE164:r.phone_e164??null,photoUrl:r.profile_photo_media_id?`/api/media/${r.profile_photo_media_id}`:null,about:r.about??''};
}
function privacyJson(x:any){
 return {readReceipts:Boolean(x.read_receipts),showLastSeen:Boolean(x.show_last_seen),showOnline:Boolean(x.show_online),profilePhotoVisibility:x.profile_photo_visibility,aboutVisibility:x.about_visibility,messagePreviews:Boolean(x.message_previews)};
}
function sharedMessageJson(r:any){
 return {id:r.id,clientId:r.client_id,chatId:r.chat_id,senderId:r.sender_id,type:r.type,body:r.body,createdAt:new Date(r.created_at).toISOString(),editedAt:r.edited_at?new Date(r.edited_at).toISOString():null,deleted:Boolean(r.deleted_at),status:'sent',encrypted:Boolean(r.encrypted),cryptoVersion:Number(r.crypto_version??0),media:r.media_id?{id:r.media_id,url:`/api/media/${r.media_id}`,mime:r.media_mime,name:r.media_name,size:Number(r.media_size??0)}:null};
}

export function installProfileV032(app:Express){
 // Registered before the legacy routes so these v0.32 handlers are authoritative.
 app.get('/api/me',requireAuth,async(req,res)=>{
  const r=await pool.query('SELECT * FROM users WHERE id=$1',[req.zoolUser!.id]);
  if(!r.rowCount)return res.status(404).json({error:'user_not_found'});
  res.json({user:selfJson(r.rows[0])});
 });

 app.put('/api/me',requireAuth,async(req,res)=>{
  const b=z.object({
   displayName:z.string().trim().min(1).max(80).optional(),
   username:z.string().trim().min(3).max(32).regex(/^[A-Za-z0-9_.]+$/).nullable().optional(),
   about:z.string().trim().max(160).optional(),
   photoMediaId:z.string().uuid().nullable().optional(),
  }).parse(req.body);
  const uid=req.zoolUser!.id;
  if(b.photoMediaId){
   const owned=await pool.query('SELECT 1 FROM media_assets WHERE id=$1 AND owner_id=$2',[b.photoMediaId,uid]);
   if(!owned.rowCount)return res.status(403).json({error:'profile_photo_not_owned'});
  }
  try{
   if(b.displayName!==undefined)await pool.query('UPDATE users SET display_name=$2,updated_at=now() WHERE id=$1',[uid,b.displayName]);
   if(b.username!==undefined)await pool.query('UPDATE users SET username=$2,updated_at=now() WHERE id=$1',[uid,b.username?.toLowerCase()??null]);
   if(b.about!==undefined)await pool.query('UPDATE users SET about=$2,updated_at=now() WHERE id=$1',[uid,b.about]);
   if(b.photoMediaId!==undefined)await pool.query('UPDATE users SET profile_photo_media_id=$2,photo_url=NULL,updated_at=now() WHERE id=$1',[uid,b.photoMediaId]);
  }catch(e:any){if(e?.code==='23505')return res.status(409).json({error:'username_taken'});throw e;}
  const r=await pool.query('SELECT * FROM users WHERE id=$1',[uid]);
  res.json({user:selfJson(r.rows[0])});
 });

 app.post('/api/devices/fcm/revoke',requireAuth,async(req,res)=>{
  const token=z.object({token:z.string().min(10).max(4096)}).parse(req.body).token;
  await pool.query('UPDATE devices SET revoked_at=now(),updated_at=now() WHERE user_id=$1 AND fcm_token=$2 AND revoked_at IS NULL',[req.zoolUser!.id,token]);
  res.json({ok:true});
 });

 app.get('/api/privacy',requireAuth,async(req,res)=>{
  const r=await pool.query(`INSERT INTO user_privacy_settings(user_id) VALUES($1)
   ON CONFLICT(user_id) DO UPDATE SET user_id=EXCLUDED.user_id RETURNING *`,[req.zoolUser!.id]);
  res.json({privacy:privacyJson(r.rows[0])});
 });

 app.put('/api/privacy',requireAuth,async(req,res)=>{
  const b=z.object({readReceipts:z.boolean().optional(),showLastSeen:z.boolean().optional(),showOnline:z.boolean().optional(),profilePhotoVisibility:z.enum(['everyone','contacts','nobody']).optional(),aboutVisibility:z.enum(['everyone','contacts','nobody']).optional(),messagePreviews:z.boolean().optional()}).parse(req.body);
  const r=await pool.query(`INSERT INTO user_privacy_settings(user_id,read_receipts,show_last_seen,show_online,profile_photo_visibility,about_visibility,message_previews)
   VALUES($1,COALESCE($2,true),COALESCE($3,true),COALESCE($4,true),COALESCE($5,'contacts'),COALESCE($6,'contacts'),COALESCE($7,true))
   ON CONFLICT(user_id) DO UPDATE SET
    read_receipts=COALESCE($2,user_privacy_settings.read_receipts),
    show_last_seen=COALESCE($3,user_privacy_settings.show_last_seen),
    show_online=COALESCE($4,user_privacy_settings.show_online),
    profile_photo_visibility=COALESCE($5,user_privacy_settings.profile_photo_visibility),
    about_visibility=COALESCE($6,user_privacy_settings.about_visibility),
    message_previews=COALESCE($7,user_privacy_settings.message_previews),updated_at=now()
   RETURNING *`,[req.zoolUser!.id,b.readReceipts??null,b.showLastSeen??null,b.showOnline??null,b.profilePhotoVisibility??null,b.aboutVisibility??null,b.messagePreviews??null]);
  res.json({privacy:privacyJson(r.rows[0])});
 });

 app.get('/api/users/:id/profile',requireAuth,async(req,res)=>{
  const viewer=req.zoolUser!.id,target=z.string().uuid().parse(req.params.id);
  const shared=await sharedRelation(viewer,target);
  const blocked=await pool.query('SELECT 1 FROM blocks WHERE (blocker_id=$1 AND blocked_id=$2) OR (blocker_id=$2 AND blocked_id=$1)',[viewer,target]);
  if((viewer!==target&&!shared)||blocked.rowCount)return res.status(404).json({error:'profile_unavailable'});
  const r=await pool.query(`SELECT u.id,u.display_name,u.username,u.about,u.profile_photo_media_id,u.online,u.last_seen_at,
   COALESCE(p.show_online,true) show_online,COALESCE(p.show_last_seen,true) show_last_seen,
   COALESCE(p.profile_photo_visibility,'contacts') profile_photo_visibility,COALESCE(p.about_visibility,'contacts') about_visibility
   FROM users u LEFT JOIN user_privacy_settings p ON p.user_id=u.id WHERE u.id=$1 AND u.account_status='active'`,[target]);
  if(!r.rowCount)return res.status(404).json({error:'profile_unavailable'});
  const u=r.rows[0];
  const showPhoto=await canSee(viewer,target,u.profile_photo_visibility);
  const showAbout=await canSee(viewer,target,u.about_visibility);
  const groups=await pool.query(`SELECT c.id,c.title FROM chats c JOIN chat_members a ON a.chat_id=c.id AND a.user_id=$1 JOIN chat_members b ON b.chat_id=c.id AND b.user_id=$2 WHERE c.type='group'`,[viewer,target]);
  res.json({profile:{id:u.id,displayName:u.display_name,username:u.username,about:showAbout?(u.about??''):null,photoUrl:showPhoto&&u.profile_photo_media_id?`/api/media/${u.profile_photo_media_id}`:null,online:u.show_online?u.online:null,lastSeenAt:u.show_last_seen?u.last_seen_at:null,groupsInCommon:groups.rows}});
 });

 // Extends media authorization so a profile photo can be fetched only when its owner's privacy allows it.
 app.get('/api/media/:id',requireAuth,async(req,res,next)=>{
  const id=z.string().uuid().safeParse(req.params.id);if(!id.success)return next();
  const viewer=req.zoolUser!.id;
  const r=await pool.query(`SELECT ma.*,u.id profile_owner,COALESCE(p.profile_photo_visibility,'contacts') photo_visibility
   FROM media_assets ma LEFT JOIN users u ON u.profile_photo_media_id=ma.id LEFT JOIN user_privacy_settings p ON p.user_id=u.id WHERE ma.id=$1`,[id.data]);
  if(!r.rowCount)return res.status(404).json({error:'media_not_found'});
  const m=r.rows[0];
  let allowed=m.owner_id===viewer;
  if(!allowed){const share=await pool.query('SELECT 1 FROM messages msg JOIN chat_members cm ON cm.chat_id=msg.chat_id WHERE msg.media_id=$1 AND cm.user_id=$2 LIMIT 1',[id.data,viewer]);allowed=Boolean(share.rowCount);}
  if(!allowed&&m.profile_owner){
   const blocked=await pool.query('SELECT 1 FROM blocks WHERE (blocker_id=$1 AND blocked_id=$2) OR (blocker_id=$2 AND blocked_id=$1)',[viewer,m.profile_owner]);
   if(!blocked.rowCount)allowed=await canSee(viewer,m.profile_owner,m.photo_visibility);
  }
  if(!allowed)return res.status(404).json({error:'media_not_found'});
  res.type(m.mime_type);res.setHeader('Cache-Control','private, max-age=86400');res.setHeader('Content-Disposition',`inline; filename*=UTF-8''${encodeURIComponent(m.original_name)}`);
  if(r2Enabled){
   if(!r2||!r2Bucket)return res.status(503).json({error:'media_storage_unavailable'});
   const object=await r2.send(new GetObjectCommand({Bucket:r2Bucket,Key:m.storage_name}));const body:any=object.Body;
   if(!body||typeof body.pipe!=='function')return res.status(404).json({error:'media_not_found'});
   body.on('error',(err:unknown)=>{console.error('r2_profile_media_stream_failed',err);if(!res.headersSent)res.status(502).end();else res.destroy();});body.pipe(res);return;
  }
  res.sendFile(path.join(mediaDir,m.storage_name));
 });

 app.get('/api/chats/:id/shared',requireAuth,async(req,res)=>{
  const chatId=z.string().uuid().parse(req.params.id),uid=req.zoolUser!.id;
  const member=await pool.query('SELECT 1 FROM chat_members WHERE chat_id=$1 AND user_id=$2',[chatId,uid]);if(!member.rowCount)return res.status(403).json({error:'not_member'});
  const kind=z.enum(['media','documents','links']).catch('media').parse(String(req.query.kind??'media'));
  const limit=Math.max(1,Math.min(Number(req.query.limit??100),100));
  let r;
  if(kind==='links'){
   r=await pool.query(`SELECT m.*,NULL::uuid media_id,NULL::text media_mime,NULL::text media_name,NULL::bigint media_size FROM messages m
    WHERE m.chat_id=$1 AND m.deleted_at IS NULL AND m.type='text' AND m.encrypted=false AND (m.body ILIKE '%http://%' OR m.body ILIKE '%https://%')
    ORDER BY m.created_at DESC LIMIT $2`,[chatId,limit]);
  }else{
   const types=kind==='documents'?['document']:['image','video'];
   r=await pool.query(`SELECT m.*,ma.id media_id,ma.mime_type media_mime,ma.original_name media_name,ma.size_bytes media_size FROM messages m JOIN media_assets ma ON ma.id=m.media_id
    WHERE m.chat_id=$1 AND m.deleted_at IS NULL AND m.type=ANY($2::text[]) ORDER BY m.created_at DESC LIMIT $3`,[chatId,types,limit]);
  }
  res.json({kind,items:r.rows.map(sharedMessageJson),encryptedLinksNote:kind==='links'});
 });
}
