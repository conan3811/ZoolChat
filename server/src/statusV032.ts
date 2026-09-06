import type {Express} from 'express';
import path from 'node:path';
import fs from 'node:fs';
import {randomUUID} from 'node:crypto';
import {GetObjectCommand,S3Client} from '@aws-sdk/client-s3';
import {z} from 'zod';
import {pool,tx} from './db.js';
import {requireAuth} from './auth.js';
import {sendData} from './firebase.js';

type Emit=(id:string,event:unknown)=>void;
type StatusRow=Record<string,any>;

const mediaDir=path.resolve(process.env.MEDIA_DIR??'./data/media');
const r2Endpoint=process.env.R2_ENDPOINT?.trim();
const r2AccessKeyId=process.env.R2_ACCESS_KEY_ID?.trim();
const r2SecretAccessKey=process.env.R2_SECRET_ACCESS_KEY?.trim();
const r2Bucket=process.env.R2_BUCKET?.trim();
const r2Enabled=Boolean(r2Endpoint&&r2AccessKeyId&&r2SecretAccessKey&&r2Bucket);
const r2=r2Enabled?new S3Client({region:'auto',endpoint:r2Endpoint,credentials:{accessKeyId:r2AccessKeyId!,secretAccessKey:r2SecretAccessKey!}}):null;
fs.mkdirSync(mediaDir,{recursive:true});

async function blocked(a:string,b:string){
 if(a===b)return false;
 const r=await pool.query('SELECT 1 FROM blocks WHERE (blocker_id=$1 AND blocked_id=$2) OR (blocker_id=$2 AND blocked_id=$1) LIMIT 1',[a,b]);
 return Boolean(r.rowCount);
}

async function related(a:string,b:string){
 if(a===b)return true;
 const r=await pool.query(`SELECT 1 FROM chat_members x JOIN chat_members y ON y.chat_id=x.chat_id
  WHERE x.user_id=$1 AND y.user_id=$2 LIMIT 1`,[a,b]);
 return Boolean(r.rowCount);
}

function uuidArray(value:any):string[]{
 if(!Array.isArray(value))return [];
 return value.map(String);
}

async function mentioned(statusId:string,userId:string){
 const r=await pool.query('SELECT 1 FROM status_mentions WHERE status_id=$1 AND user_id=$2',[statusId,userId]);
 return Boolean(r.rowCount);
}

async function canViewStatus(row:StatusRow,viewer:string){
 const owner=String(row.owner_id);
 if(viewer===owner)return true;
 if(new Date(row.expires_at).getTime()<=Date.now())return false;
 if(await blocked(viewer,owner))return false;
 if(await mentioned(String(row.id),viewer))return true;
 const ids=uuidArray(row.audience_user_ids);
 switch(String(row.audience_mode)){
  case 'only': return ids.includes(viewer);
  case 'except': return (await related(viewer,owner))&&!ids.includes(viewer);
  default:
   // When Android supplies a current address-book snapshot, use it. Older clients can leave
   // this empty and fall back to an established ZoolChat relationship.
   return ids.length?ids.includes(viewer):related(viewer,owner);
 }
}

async function profilePhotoVisible(viewer:string,row:StatusRow){
 const owner=String(row.owner_id);
 if(viewer===owner)return true;
 const visibility=String(row.profile_photo_visibility??'contacts');
 if(visibility==='everyone')return true;
 if(visibility==='nobody')return false;
 return related(viewer,owner);
}

async function statusJson(row:StatusRow,viewer:string){
 const statusId=String(row.id);
 const media=await pool.query(`SELECT sm.media_id,sm.role,sm.position,ma.mime_type,ma.original_name,ma.size_bytes
  FROM status_media sm JOIN media_assets ma ON ma.id=sm.media_id WHERE sm.status_id=$1 ORDER BY sm.role,sm.position`,[statusId]);
 const mine=await pool.query(`SELECT
  EXISTS(SELECT 1 FROM status_views WHERE status_id=$1 AND viewer_id=$2) viewed,
  EXISTS(SELECT 1 FROM status_likes WHERE status_id=$1 AND user_id=$2) liked,
  (SELECT emoji FROM status_reactions WHERE status_id=$1 AND user_id=$2) reaction,
  EXISTS(SELECT 1 FROM status_mentions WHERE status_id=$1 AND user_id=$2) mentioned`,[statusId,viewer]);
 const owner=String(row.owner_id)===viewer;
 let stats:any={};
 if(owner){
  const s=await pool.query(`SELECT
   (SELECT count(*)::int FROM status_views WHERE status_id=$1) views,
   (SELECT count(*)::int FROM status_likes WHERE status_id=$1) likes,
   (SELECT count(*)::int FROM status_reactions WHERE status_id=$1) reactions`,[statusId]);
  stats={viewCount:Number(s.rows[0]?.views??0),likeCount:Number(s.rows[0]?.likes??0),reactionCount:Number(s.rows[0]?.reactions??0)};
 }
 const photoAllowed=await profilePhotoVisible(viewer,row);
 return {
  id:statusId,
  ownerId:String(row.owner_id),
  ownerDisplayName:String(row.display_name??'ZoolChat'),
  ownerUsername:row.username??null,
  ownerPhotoUrl:photoAllowed&&row.profile_photo_media_id?`/api/status/media/${row.profile_photo_media_id}`:null,
  kind:String(row.kind),
  text:String(row.text_content??''),
  linkUrl:row.link_url??null,
  layout:row.layout_json??null,
  addYoursPrompt:row.add_yours_prompt??null,
  reshareOf:row.reshare_of??null,
  createdAt:new Date(row.created_at).toISOString(),
  expiresAt:new Date(row.expires_at).toISOString(),
  viewed:Boolean(mine.rows[0]?.viewed),
  liked:Boolean(mine.rows[0]?.liked),
  myReaction:mine.rows[0]?.reaction??null,
  mentionedMe:Boolean(mine.rows[0]?.mentioned),
  canReshare:Boolean(mine.rows[0]?.mentioned),
  media:media.rows.map(m=>({id:String(m.media_id),role:String(m.role),position:Number(m.position),url:`/api/status/media/${m.media_id}`,mime:String(m.mime_type),name:String(m.original_name),size:Number(m.size_bytes)})),
  ...stats,
 };
}

async function getStatusRow(id:string){
 const r=await pool.query(`SELECT s.*,u.display_name,u.username,u.profile_photo_media_id,
  COALESCE(p.profile_photo_visibility,'contacts') profile_photo_visibility
  FROM status_posts s JOIN users u ON u.id=s.owner_id LEFT JOIN user_privacy_settings p ON p.user_id=u.id
  WHERE s.id=$1 AND u.account_status='active'`,[id]);
 return r.rows[0] as StatusRow|undefined;
}

async function ensureDirectChat(a:string,b:string){
 const [x,y]=[a,b].sort();
 const existing=await pool.query('SELECT chat_id FROM direct_chats WHERE user_a=$1 AND user_b=$2',[x,y]);
 if(existing.rowCount)return String(existing.rows[0].chat_id);
 return tx(async c=>{
  const again=await c.query('SELECT chat_id FROM direct_chats WHERE user_a=$1 AND user_b=$2',[x,y]);
  if(again.rowCount)return String(again.rows[0].chat_id);
  const chat=await c.query("INSERT INTO chats(type) VALUES('direct') RETURNING id");
  const chatId=String(chat.rows[0].id);
  await c.query('INSERT INTO direct_chats(user_a,user_b,chat_id) VALUES($1,$2,$3)',[x,y,chatId]);
  await c.query('INSERT INTO chat_members(chat_id,user_id) VALUES($1,$2),($1,$3)',[chatId,x,y]);
  return chatId;
 });
}

async function notificationTokens(userId:string){
 const r=await pool.query('SELECT fcm_token FROM devices WHERE user_id=$1 AND revoked_at IS NULL',[userId]);
 return r.rows.map(x=>String(x.fcm_token));
}

export function installStatusV032(app:Express,emit:Emit){
 app.get('/api/status/privacy',requireAuth,async(req,res)=>{
  const r=await pool.query(`INSERT INTO user_privacy_settings(user_id) VALUES($1)
   ON CONFLICT(user_id) DO UPDATE SET user_id=EXCLUDED.user_id RETURNING status_audience_mode,status_audience_user_ids`,[req.zoolUser!.id]);
  res.json({privacy:{mode:r.rows[0].status_audience_mode,userIds:uuidArray(r.rows[0].status_audience_user_ids)}});
 });

 app.put('/api/status/privacy',requireAuth,async(req,res)=>{
  const b=z.object({mode:z.enum(['contacts','except','only']),userIds:z.array(z.string().uuid()).max(500).default([])}).parse(req.body);
  const ids=[...new Set(b.userIds.filter(x=>x!==req.zoolUser!.id))];
  await pool.query(`INSERT INTO user_privacy_settings(user_id,status_audience_mode,status_audience_user_ids) VALUES($1,$2,$3::uuid[])
   ON CONFLICT(user_id) DO UPDATE SET status_audience_mode=$2,status_audience_user_ids=$3::uuid[],updated_at=now()`,[req.zoolUser!.id,b.mode,ids]);
  res.json({privacy:{mode:b.mode,userIds:ids}});
 });

 app.get('/api/status/audience-candidates',requireAuth,async(req,res)=>{
  const uid=req.zoolUser!.id;
  const r=await pool.query(`SELECT DISTINCT u.id,u.display_name,u.username,
   CASE WHEN COALESCE(p.profile_photo_visibility,'contacts')='everyone' OR
      (COALESCE(p.profile_photo_visibility,'contacts')='contacts' AND EXISTS(
       SELECT 1 FROM chat_members a JOIN chat_members b ON b.chat_id=a.chat_id WHERE a.user_id=$1 AND b.user_id=u.id
      )) THEN CASE WHEN u.profile_photo_media_id IS NOT NULL THEN '/api/status/media/'||u.profile_photo_media_id::text ELSE NULL END ELSE NULL END photo_url
   FROM users u LEFT JOIN user_privacy_settings p ON p.user_id=u.id
   WHERE u.id<>$1 AND u.account_status='active' AND
    EXISTS(SELECT 1 FROM chat_members a JOIN chat_members b ON b.chat_id=a.chat_id WHERE a.user_id=$1 AND b.user_id=u.id)
    AND NOT EXISTS(SELECT 1 FROM blocks bl WHERE (bl.blocker_id=$1 AND bl.blocked_id=u.id) OR (bl.blocker_id=u.id AND bl.blocked_id=$1))
   ORDER BY u.display_name LIMIT 1000`,[uid]);
  res.json({users:r.rows.map(x=>({userId:x.id,displayName:x.display_name,username:x.username,photoUrl:x.photo_url}))});
 });

 app.get('/api/status',requireAuth,async(req,res)=>{
  const uid=req.zoolUser!.id;
  const r=await pool.query(`SELECT s.*,u.display_name,u.username,u.profile_photo_media_id,
   COALESCE(p.profile_photo_visibility,'contacts') profile_photo_visibility
   FROM status_posts s JOIN users u ON u.id=s.owner_id LEFT JOIN user_privacy_settings p ON p.user_id=u.id
   WHERE s.expires_at>now() AND u.account_status='active' ORDER BY s.created_at ASC LIMIT 300`);
  const visible=[] as any[];
  for(const row of r.rows)if(await canViewStatus(row,uid))visible.push(await statusJson(row,uid));
  res.json({statuses:visible});
 });

 app.post('/api/status',requireAuth,async(req,res)=>{
  const b=z.object({
   kind:z.enum(['text','image','video','voice','link','collage','music']),
   text:z.string().max(1000).default(''),
   linkUrl:z.string().url().max(2048).nullable().optional(),
   mediaIds:z.array(z.string().uuid()).max(6).default([]),
   stickerMediaIds:z.array(z.string().uuid()).max(10).default([]),
   musicMediaId:z.string().uuid().nullable().optional(),
   audienceMode:z.enum(['contacts','except','only']).default('contacts'),
   audienceUserIds:z.array(z.string().uuid()).max(500).default([]),
   mentionedUserIds:z.array(z.string().uuid()).max(50).default([]),
   layout:z.any().optional(),
   addYoursPrompt:z.string().trim().max(120).nullable().optional(),
  }).parse(req.body);
  const uid=req.zoolUser!.id;
  const primary=[...new Set(b.mediaIds)];
  const stickers=[...new Set(b.stickerMediaIds.filter(x=>!primary.includes(x)))];
  const all=[...primary,...stickers,...(b.musicMediaId?[b.musicMediaId]:[])];
  if(b.kind==='text'&&!b.text.trim())return res.status(400).json({error:'status_text_required'});
  if(b.kind==='link'&&!b.linkUrl)return res.status(400).json({error:'status_link_required'});
  if(['image','video','voice','collage','music'].includes(b.kind)&&!all.length)return res.status(400).json({error:'status_media_required'});
  if(all.length){
   const owned=await pool.query('SELECT id,mime_type FROM media_assets WHERE owner_id=$1 AND id=ANY($2::uuid[])',[uid,all]);
   if(owned.rowCount!==all.length)return res.status(403).json({error:'status_media_not_owned'});
   const byId=new Map(owned.rows.map(x=>[String(x.id),String(x.mime_type)]));
   if(b.kind==='image'&&primary.some(id=>!byId.get(id)?.startsWith('image/')))return res.status(415).json({error:'status_media_type'});
   if(b.kind==='video'&&primary.some(id=>!byId.get(id)?.startsWith('video/')))return res.status(415).json({error:'status_media_type'});
   if(b.kind==='voice'&&primary.some(id=>!byId.get(id)?.startsWith('audio/')))return res.status(415).json({error:'status_media_type'});
  }
  const audience=[...new Set(b.audienceUserIds.filter(x=>x!==uid))];
  const mentions=[...new Set(b.mentionedUserIds.filter(x=>x!==uid))];
  const created=await tx(async c=>{
   const r=await c.query(`INSERT INTO status_posts(owner_id,kind,text_content,link_url,audience_mode,audience_user_ids,layout_json,add_yours_prompt)
    VALUES($1,$2,$3,$4,$5,$6::uuid[],$7::jsonb,$8) RETURNING *`,[uid,b.kind,b.text,b.linkUrl??null,b.audienceMode,audience,b.layout===undefined?null:JSON.stringify(b.layout),b.addYoursPrompt??null]);
   const id=String(r.rows[0].id);
   for(let i=0;i<primary.length;i++)await c.query("INSERT INTO status_media(status_id,media_id,role,position) VALUES($1,$2,'primary',$3)",[id,primary[i],i]);
   for(let i=0;i<stickers.length;i++)await c.query("INSERT INTO status_media(status_id,media_id,role,position) VALUES($1,$2,'sticker',$3)",[id,stickers[i],i]);
   if(b.musicMediaId)await c.query("INSERT INTO status_media(status_id,media_id,role,position) VALUES($1,$2,'music',0) ON CONFLICT DO NOTHING",[id,b.musicMediaId]);
   for(const m of mentions)await c.query('INSERT INTO status_mentions(status_id,user_id) SELECT $1,id FROM users WHERE id=$2 AND account_status=\'active\' ON CONFLICT DO NOTHING',[id,m]);
   await c.query(`INSERT INTO user_privacy_settings(user_id,status_audience_mode,status_audience_user_ids) VALUES($1,$2,$3::uuid[])
    ON CONFLICT(user_id) DO UPDATE SET status_audience_mode=$2,status_audience_user_ids=$3::uuid[],updated_at=now()`,[uid,b.audienceMode,audience]);
   return r.rows[0];
  });
  for(const m of mentions)emit(m,{type:'status.mentioned',data:{statusId:created.id,ownerId:uid,ownerName:req.zoolUser!.displayName}});
  const full=await getStatusRow(String(created.id));
  res.status(201).json({status:await statusJson(full!,uid)});
 });

 app.post('/api/status/:id/view',requireAuth,async(req,res)=>{
  const id=z.string().uuid().parse(req.params.id),uid=req.zoolUser!.id;const row=await getStatusRow(id);
  if(!row||!(await canViewStatus(row,uid)))return res.status(404).json({error:'status_unavailable'});
  if(String(row.owner_id)!==uid)await pool.query('INSERT INTO status_views(status_id,viewer_id) VALUES($1,$2) ON CONFLICT(status_id,viewer_id) DO UPDATE SET viewed_at=now()',[id,uid]);
  res.json({ok:true});
 });

 app.post('/api/status/:id/like',requireAuth,async(req,res)=>{
  const id=z.string().uuid().parse(req.params.id),uid=req.zoolUser!.id;const row=await getStatusRow(id);
  if(!row||!(await canViewStatus(row,uid))||String(row.owner_id)===uid)return res.status(404).json({error:'status_unavailable'});
  const exists=await pool.query('SELECT 1 FROM status_likes WHERE status_id=$1 AND user_id=$2',[id,uid]);
  if(exists.rowCount)await pool.query('DELETE FROM status_likes WHERE status_id=$1 AND user_id=$2',[id,uid]);
  else await pool.query('INSERT INTO status_likes(status_id,user_id) VALUES($1,$2)',[id,uid]);
  emit(String(row.owner_id),{type:'status.like',data:{statusId:id,userId:uid,liked:!exists.rowCount}});
  res.json({liked:!exists.rowCount});
 });

 app.post('/api/status/:id/reaction',requireAuth,async(req,res)=>{
  const id=z.string().uuid().parse(req.params.id),uid=req.zoolUser!.id;
  const emoji=z.object({emoji:z.string().trim().min(1).max(16)}).parse(req.body).emoji;const row=await getStatusRow(id);
  if(!row||!(await canViewStatus(row,uid))||String(row.owner_id)===uid)return res.status(404).json({error:'status_unavailable'});
  await pool.query(`INSERT INTO status_reactions(status_id,user_id,emoji) VALUES($1,$2,$3)
   ON CONFLICT(status_id,user_id) DO UPDATE SET emoji=EXCLUDED.emoji,updated_at=now()`,[id,uid,emoji]);
  emit(String(row.owner_id),{type:'status.reaction',data:{statusId:id,userId:uid,emoji}});
  res.json({ok:true,emoji});
 });

 app.post('/api/status/:id/reply',requireAuth,async(req,res)=>{
  const id=z.string().uuid().parse(req.params.id),uid=req.zoolUser!.id;
  const text=z.object({text:z.string().trim().min(1).max(2000)}).parse(req.body).text;const row=await getStatusRow(id);
  if(!row||!(await canViewStatus(row,uid))||String(row.owner_id)===uid)return res.status(404).json({error:'status_unavailable'});
  const owner=String(row.owner_id);const chatId=await ensureDirectChat(uid,owner);const clientId=`statusreply:${randomUUID()}`;
  const inserted=await pool.query(`INSERT INTO messages(chat_id,sender_id,client_id,type,body) VALUES($1,$2,$3,'text',$4) RETURNING *`,[chatId,uid,clientId,`↩️ ${text}`]);
  await pool.query('INSERT INTO message_receipts(message_id,user_id) VALUES($1,$2) ON CONFLICT DO NOTHING',[inserted.rows[0].id,owner]);
  const message={id:inserted.rows[0].id,clientId,chatId,senderId:uid,type:'text',body:`↩️ ${text}`,createdAt:new Date(inserted.rows[0].created_at).toISOString(),editedAt:null,deleted:false,status:'sent',encrypted:false,cryptoVersion:0,media:null};
  emit(owner,{type:'message.new',data:{...message,senderName:req.zoolUser!.displayName}});
  await sendData(await notificationTokens(owner),{type:'message_new',chatId,chatType:'direct',senderName:req.zoolUser!.displayName,body:'رد على حالتك'});
  res.status(201).json({ok:true,chatId,message});
 });

 app.post('/api/status/:id/reshare',requireAuth,async(req,res)=>{
  const id=z.string().uuid().parse(req.params.id),uid=req.zoolUser!.id;const source=await getStatusRow(id);
  if(!source||!(await canViewStatus(source,uid))||!(await mentioned(id,uid)))return res.status(403).json({error:'status_reshare_not_allowed'});
  const privacy=await pool.query('SELECT status_audience_mode,status_audience_user_ids FROM user_privacy_settings WHERE user_id=$1',[uid]);
  const mode=privacy.rows[0]?.status_audience_mode??'contacts',ids=uuidArray(privacy.rows[0]?.status_audience_user_ids);
  const copy=await tx(async c=>{
   const r=await c.query(`INSERT INTO status_posts(owner_id,kind,text_content,link_url,audience_mode,audience_user_ids,layout_json,add_yours_prompt,reshare_of)
    VALUES($1,$2,$3,$4,$5,$6::uuid[],$7,$8,$9) RETURNING id`,[uid,source.kind,source.text_content,source.link_url,mode,ids,source.layout_json,source.add_yours_prompt,id]);
   const newId=String(r.rows[0].id);
   await c.query(`INSERT INTO status_media(status_id,media_id,role,position)
    SELECT $1,media_id,role,position FROM status_media WHERE status_id=$2`,[newId,id]);
   return newId;
  });
  const row=await getStatusRow(copy);res.status(201).json({status:await statusJson(row!,uid)});
 });

 app.get('/api/status/:id/viewers',requireAuth,async(req,res)=>{
  const id=z.string().uuid().parse(req.params.id),uid=req.zoolUser!.id;const row=await getStatusRow(id);
  if(!row||String(row.owner_id)!==uid)return res.status(404).json({error:'status_unavailable'});
  const r=await pool.query(`SELECT v.viewer_id,u.display_name,u.username,v.viewed_at,
   EXISTS(SELECT 1 FROM status_likes l WHERE l.status_id=v.status_id AND l.user_id=v.viewer_id) liked,
   (SELECT emoji FROM status_reactions sr WHERE sr.status_id=v.status_id AND sr.user_id=v.viewer_id) reaction
   FROM status_views v JOIN users u ON u.id=v.viewer_id WHERE v.status_id=$1 ORDER BY v.viewed_at DESC`,[id]);
  res.json({viewers:r.rows.map(x=>({userId:x.viewer_id,displayName:x.display_name,username:x.username,viewedAt:new Date(x.viewed_at).toISOString(),liked:Boolean(x.liked),reaction:x.reaction??null}))});
 });

 app.delete('/api/status/:id',requireAuth,async(req,res)=>{
  const id=z.string().uuid().parse(req.params.id);const r=await pool.query('DELETE FROM status_posts WHERE id=$1 AND owner_id=$2 RETURNING id',[id,req.zoolUser!.id]);
  if(!r.rowCount)return res.status(404).json({error:'status_unavailable'});res.json({ok:true});
 });

 // Separate media endpoint keeps status audience checks independent from message/profile media rules.
 app.get('/api/status/media/:id',requireAuth,async(req,res)=>{
  const mediaId=z.string().uuid().safeParse(req.params.id);if(!mediaId.success)return res.status(404).json({error:'media_not_found'});
  const viewer=req.zoolUser!.id;
  const asset=await pool.query('SELECT * FROM media_assets WHERE id=$1',[mediaId.data]);if(!asset.rowCount)return res.status(404).json({error:'media_not_found'});
  let allowed=String(asset.rows[0].owner_id)===viewer;
  if(!allowed){
   const posts=await pool.query(`SELECT s.*,u.display_name,u.username,u.profile_photo_media_id,
    COALESCE(p.profile_photo_visibility,'contacts') profile_photo_visibility
    FROM status_media sm JOIN status_posts s ON s.id=sm.status_id JOIN users u ON u.id=s.owner_id
    LEFT JOIN user_privacy_settings p ON p.user_id=u.id WHERE sm.media_id=$1 AND s.expires_at>now()`,[mediaId.data]);
   for(const p of posts.rows){if(await canViewStatus(p,viewer)){allowed=true;break;}}
   // Profile photos returned by the Status feed use this endpoint too.
   if(!allowed){
    const owner=await pool.query(`SELECT u.id owner_id,COALESCE(p.profile_photo_visibility,'contacts') visibility
     FROM users u LEFT JOIN user_privacy_settings p ON p.user_id=u.id WHERE u.profile_photo_media_id=$1`,[mediaId.data]);
    if(owner.rowCount){const vis=String(owner.rows[0].visibility);allowed=vis==='everyone'||(vis==='contacts'&&await related(viewer,String(owner.rows[0].owner_id)));}
   }
  }
  if(!allowed)return res.status(404).json({error:'media_not_found'});
  const m=asset.rows[0];res.type(m.mime_type);res.setHeader('Cache-Control','private, max-age=3600');res.setHeader('Content-Disposition',`inline; filename*=UTF-8''${encodeURIComponent(m.original_name)}`);
  if(r2Enabled){
   if(!r2||!r2Bucket)return res.status(503).json({error:'media_storage_unavailable'});
   const object=await r2.send(new GetObjectCommand({Bucket:r2Bucket,Key:m.storage_name}));const body:any=object.Body;
   if(!body||typeof body.pipe!=='function')return res.status(404).json({error:'media_not_found'});body.pipe(res);return;
  }
  res.sendFile(path.join(mediaDir,m.storage_name));
 });
}
