from pathlib import Path
import sys


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"expected exactly one match in {path}, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def patch_server() -> None:
    path = Path("server/src/index.ts")

    old_direct = '''app.post('/api/chats/direct', requireAuth, async (req, res) => {
  const peer = z.object({ userId: z.string().uuid() }).parse(req.body).userId, uid = req.zoolUser!.id;
  if (peer === uid) return res.status(400).json({ error: 'cannot_chat_self' });
  const blocked = await pool.query(`SELECT 1 FROM blocks WHERE (blocker_id=$1 AND blocked_id=$2) OR (blocker_id=$2 AND blocked_id=$1)`, [uid, peer]); if (blocked.rowCount) return res.status(403).json({ error: 'blocked' });
  const [a, b] = [uid, peer].sort(); const existing = await pool.query(`SELECT chat_id FROM direct_chats WHERE user_a=$1 AND user_b=$2`, [a, b]); let chatId = existing.rows[0]?.chat_id;
  if (!chatId) chatId = await tx(async c => { const cr = await c.query(`INSERT INTO chats(type) VALUES('direct') RETURNING id`); const id = cr.rows[0].id; await c.query(`INSERT INTO direct_chats(user_a,user_b,chat_id) VALUES($1,$2,$3)`, [a, b, id]); await c.query(`INSERT INTO chat_members(chat_id,user_id) VALUES($1,$2),($1,$3)`, [id, a, b]); return id; });
  res.json({ chat: { id: chatId, type: 'direct', peerUserId: peer } });
});'''
    new_direct = '''app.post('/api/chats/direct', requireAuth, async (req, res) => {
  const peer = z.object({ userId: z.string().uuid() }).parse(req.body).userId, uid = req.zoolUser!.id;
  if (peer === uid) return res.status(400).json({ error: 'cannot_chat_self' });
  const peerExists = await pool.query(`SELECT 1 FROM users WHERE id=$1 AND account_status='active'`, [peer]);
  if (!peerExists.rowCount) return res.status(404).json({ error: 'peer_not_found' });
  const blocked = await pool.query(`SELECT 1 FROM blocks WHERE (blocker_id=$1 AND blocked_id=$2) OR (blocker_id=$2 AND blocked_id=$1)`, [uid, peer]);
  if (blocked.rowCount) return res.status(403).json({ error: 'blocked' });
  // ensureDirectChat rechecks inside a transaction, so rapid/double taps cannot race into
  // duplicate direct chats or a unique-constraint 500 response.
  const chatId = await ensureDirectChat(uid, peer);
  res.json({ chat: { id: chatId, type: 'direct', peerUserId: peer } });
});'''
    replace_once(path, old_direct, new_direct)

    old_create_call = '''app.post('/api/calls', callRateLimit, requireAuth, async (req, res) => {
  try {
    const b = z.object({ peerUserIds: z.array(z.string().uuid()).min(1).max(1), type: z.enum(['audio','video']) }).parse(req.body);
    const peers = [...new Set(b.peerUserIds)].filter(id => id !== req.zoolUser!.id);
    if (!peers.length) return res.status(400).json({ error: 'invalid_call_peers' });
    const existingPeers = await pool.query(`SELECT id FROM users WHERE id=ANY($1::uuid[])`, [peers]);
    if (existingPeers.rowCount !== peers.length) return res.status(404).json({ error: 'peer_not_found' });
    const blocked = await pool.query(`SELECT 1 FROM blocks WHERE (blocker_id=$1 AND blocked_id=ANY($2::uuid[])) OR (blocked_id=$1 AND blocker_id=ANY($2::uuid[])) LIMIT 1`, [req.zoolUser!.id, peers]);
    if (blocked.rowCount) return res.status(403).json({ error: 'blocked' });
    const roomName = `call_${randomUUID().replaceAll('-', '')}`;
    const cr = await pool.query(`INSERT INTO calls(room_name,initiator_id,type) VALUES($1,$2,$3) RETURNING *`, [roomName, req.zoolUser!.id, b.type]);
    const callId = cr.rows[0].id;
    await pool.query(`INSERT INTO call_participants(call_id,user_id,status,joined_at) VALUES($1,$2,'joined',now())`, [callId, req.zoolUser!.id]);
    for (const peer of peers) await pool.query(`INSERT INTO call_participants(call_id,user_id,status) VALUES($1,$2,'invited') ON CONFLICT DO NOTHING`, [callId, peer]);
    for (const peer of peers) emitTo(peer,{type:'call.invite',data:{callId,callerUserId:req.zoolUser!.id,callerName:req.zoolUser!.displayName,mode:b.type}}); const tokens = await fcmTokens(peers); await sendData(tokens, { type: 'call_invite', callId, callerName: req.zoolUser!.displayName, mode: b.type }, true);
    await syncCallTimeline(callId); const conn = await livekitToken(req.zoolUser!.id, req.zoolUser!.displayName, roomName); res.status(201).json({ callId, mode: b.type, peerUserId: peers[0], e2eeMode:'client-derived-p256', ...conn });
  } catch (e: any) { if (e.message === 'livekit_not_configured') return res.status(503).json({ error: 'livekit_not_configured' }); throw e; }
});'''
    new_create_call = '''app.post('/api/calls', callRateLimit, requireAuth, async (req, res) => {
  try {
    const b = z.object({ peerUserIds: z.array(z.string().uuid()).min(1).max(1), type: z.enum(['audio','video']) }).parse(req.body);
    const peers = [...new Set(b.peerUserIds)].filter(id => id !== req.zoolUser!.id);
    if (!peers.length) return res.status(400).json({ error: 'invalid_call_peers' });
    const existingPeers = await pool.query(`SELECT id FROM users WHERE id=ANY($1::uuid[]) AND account_status='active'`, [peers]);
    if (existingPeers.rowCount !== peers.length) return res.status(404).json({ error: 'peer_not_found' });
    const blocked = await pool.query(`SELECT 1 FROM blocks WHERE (blocker_id=$1 AND blocked_id=ANY($2::uuid[])) OR (blocked_id=$1 AND blocker_id=ANY($2::uuid[])) LIMIT 1`, [req.zoolUser!.id, peers]);
    if (blocked.rowCount) return res.status(403).json({ error: 'blocked' });
    const roomName = `call_${randomUUID().replaceAll('-', '')}`;

    // Validate/generate LiveKit connection material before persisting a ringing call. If the
    // media service is unavailable, the caller receives a clean failure and no stale call row is
    // left behind for the callee.
    const conn = await livekitToken(req.zoolUser!.id, req.zoolUser!.displayName, roomName);
    const callId = await tx(async c => {
      const cr = await c.query(`INSERT INTO calls(room_name,initiator_id,type) VALUES($1,$2,$3) RETURNING id`, [roomName, req.zoolUser!.id, b.type]);
      const id = String(cr.rows[0].id);
      await c.query(`INSERT INTO call_participants(call_id,user_id,status,joined_at) VALUES($1,$2,'joined',now())`, [id, req.zoolUser!.id]);
      for (const peer of peers) await c.query(`INSERT INTO call_participants(call_id,user_id,status) VALUES($1,$2,'invited') ON CONFLICT DO NOTHING`, [id, peer]);
      return id;
    });

    // A chat-timeline refresh or push outage must never turn an already-created call into a 500
    // on the caller. Realtime delivery is immediate; timeline and FCM are best-effort side effects.
    for (const peer of peers) emitTo(peer,{type:'call.invite',data:{callId,callerUserId:req.zoolUser!.id,callerName:req.zoolUser!.displayName,mode:b.type}});
    void syncCallTimeline(callId).catch(() => console.warn('call_timeline_sync_failed'));
    void (async () => {
      const tokens = await fcmTokens(peers);
      await sendData(tokens, { type: 'call_invite', callId, callerName: req.zoolUser!.displayName, mode: b.type }, true);
    })().catch(() => console.warn('call_invite_delivery_failed'));
    res.status(201).json({ callId, mode: b.type, peerUserId: peers[0], e2eeMode:'client-derived-p256', ...conn });
  } catch (e: any) { if (e.message === 'livekit_not_configured') return res.status(503).json({ error: 'livekit_not_configured' }); throw e; }
});'''
    replace_once(path, old_create_call, new_create_call)

    old_accept = '''app.post('/api/calls/:id/accept', requireAuth, async (req, res) => {
  try {
    const r = await pool.query(`SELECT c.* FROM calls c JOIN call_participants cp ON cp.call_id=c.id WHERE c.id=$1 AND cp.user_id=$2`, [req.params.id, req.zoolUser!.id]); if (!r.rowCount) return res.status(404).json({ error: 'call_not_found' });
    if (!['ringing','active'].includes(r.rows[0].status)) return res.status(409).json({ error: 'call_no_longer_available' });
    await pool.query(`UPDATE call_participants SET status='joined',joined_at=now() WHERE call_id=$1 AND user_id=$2`, [req.params.id, req.zoolUser!.id]); await pool.query(`UPDATE calls SET status='active',answered_at=COALESCE(answered_at,now()) WHERE id=$1`,[req.params.id]); emitTo(r.rows[0].initiator_id,{type:'call.accepted',data:{callId:req.params.id}});
    await syncCallTimeline(String(req.params.id)); const conn = await livekitToken(req.zoolUser!.id, req.zoolUser!.displayName, r.rows[0].room_name); res.json({ callId: req.params.id, mode: r.rows[0].type, peerUserId: r.rows[0].initiator_id, e2eeMode:'client-derived-p256', ...conn });
  } catch (e: any) { if (e.message === 'livekit_not_configured') return res.status(503).json({ error: 'livekit_not_configured' }); throw e; }
});'''
    new_accept = '''app.post('/api/calls/:id/accept', requireAuth, async (req, res) => {
  try {
    const r = await pool.query(`SELECT c.* FROM calls c JOIN call_participants cp ON cp.call_id=c.id WHERE c.id=$1 AND cp.user_id=$2`, [req.params.id, req.zoolUser!.id]); if (!r.rowCount) return res.status(404).json({ error: 'call_not_found' });
    if (!['ringing','active'].includes(r.rows[0].status)) return res.status(409).json({ error: 'call_no_longer_available' });
    // Obtain media credentials before announcing acceptance, otherwise a LiveKit configuration
    // failure could leave the call marked active while the callee received an error.
    const conn = await livekitToken(req.zoolUser!.id, req.zoolUser!.displayName, r.rows[0].room_name);
    await tx(async c => {
      await c.query(`UPDATE call_participants SET status='joined',joined_at=COALESCE(joined_at,now()) WHERE call_id=$1 AND user_id=$2`, [req.params.id, req.zoolUser!.id]);
      await c.query(`UPDATE calls SET status='active',answered_at=COALESCE(answered_at,now()) WHERE id=$1`,[req.params.id]);
    });
    emitTo(r.rows[0].initiator_id,{type:'call.accepted',data:{callId:req.params.id}});
    void syncCallTimeline(String(req.params.id)).catch(() => console.warn('call_timeline_sync_failed'));
    res.json({ callId: req.params.id, mode: r.rows[0].type, peerUserId: r.rows[0].initiator_id, e2eeMode:'client-derived-p256', ...conn });
  } catch (e: any) { if (e.message === 'livekit_not_configured') return res.status(503).json({ error: 'livekit_not_configured' }); throw e; }
});'''
    replace_once(path, old_accept, new_accept)

    old_decline = '''  await syncCallTimeline(String(req.params.id)); emitTo(r.rows[0].initiator_id,{type:'call.declined',data:{callId:req.params.id}});res.json({ok:true});'''
    new_decline = '''  void syncCallTimeline(String(req.params.id)).catch(() => console.warn('call_timeline_sync_failed')); emitTo(r.rows[0].initiator_id,{type:'call.declined',data:{callId:req.params.id}});res.json({ok:true});'''
    replace_once(path, old_decline, new_decline)

    old_end = '''  const callId=String(req.params.id);await syncCallTimeline(callId);const peers=await pool.query(`SELECT user_id FROM call_participants WHERE call_id=$1 AND user_id<>$2`,[callId,uid]);for(const p of peers.rows)emitTo(p.user_id,{type:'call.ended',data:{callId}});const tokens=await fcmTokens(peers.rows.map(x=>x.user_id));await sendData(tokens,{type:'call_cancelled',callId},true);res.json({ok:true});'''
    new_end = '''  const callId=String(req.params.id);void syncCallTimeline(callId).catch(() => console.warn('call_timeline_sync_failed'));const peers=await pool.query(`SELECT user_id FROM call_participants WHERE call_id=$1 AND user_id<>$2`,[callId,uid]);for(const p of peers.rows)emitTo(p.user_id,{type:'call.ended',data:{callId}});void (async()=>{const tokens=await fcmTokens(peers.rows.map(x=>x.user_id));await sendData(tokens,{type:'call_cancelled',callId},true);})().catch(()=>console.warn('call_cancel_delivery_failed'));res.json({ok:true});'''
    replace_once(path, old_end, new_end)


def patch_android() -> None:
    vm = Path("android/app/src/main/java/com/zoolchat/app/state/ZoolAppViewModel.kt")
    old_open = '''    fun openDirect(userId: String, onReady: (String) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val c = api.createDirectChat(userId)
                sync.refreshChats()
                onReady(c.getString("id"))
            }.onFailure { _error.value = it.message }
        }
    }'''
    new_open = '''    fun openDirect(userId: String, onReady: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { api.createDirectChat(userId).getString("id") }
                .onSuccess { chatId ->
                    // Navigation must depend only on successful direct-chat creation. A later
                    // chat-list refresh is useful for title/peer metadata, but must not make the
                    // Message button appear broken when the chat already exists on the server.
                    onReady(chatId)
                    viewModelScope.launch {
                        runCatching { sync.refreshChats() }.onFailure { _error.value = it.message }
                    }
                }
                .onFailure { _error.value = it.message }
        }
    }'''
    replace_once(vm, old_open, new_open)

    call = Path("android/app/src/main/java/com/zoolchat/app/ui/screens/ProductionCallScreen.kt")
    old_retry = '''            val firstConnect = engine.connect(cfg)
            if (firstConnect.isFailure) {
                engine.disconnect()
                delay(500)
                engine.connect(cfg).getOrThrow()
            }'''
    new_retry = '''            var connectedSuccessfully = false
            var lastConnectFailure: Throwable? = null
            for (attempt in 0..2) {
                val result = engine.connect(cfg)
                if (result.isSuccess) {
                    connectedSuccessfully = true
                    break
                }
                lastConnectFailure = result.exceptionOrNull()
                engine.disconnect()
                if (attempt < 2) delay(if (attempt == 0) 500 else 1_200)
            }
            if (!connectedSuccessfully) throw lastConnectFailure ?: IllegalStateException("call_media_connect_failed")'''
    replace_once(call, old_retry, new_retry)

    old_error_ui = '''        if (error != null) Text(error!!, color = Color(0xFFFF6B6B), modifier = Modifier.padding(12.dp))'''
    new_error_ui = '''        if (error != null) {
            Text(error!!, color = Color(0xFFFF6B6B), modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            if (!incoming || !accepted) {
                TextButton(onClick = { requestAndConnect() }) { Text("إعادة المحاولة") }
            }
        }'''
    replace_once(call, old_error_ui, new_error_ui)

    old_friendly = '''private fun friendlyCallError(t: Throwable): String {
    val raw = t.message.orEmpty()
    if (raw.contains("livekit_not_configured", true)) return "خدمة المكالمات غير متاحة حالياً"
    if (raw.contains("call_no_longer_available", true)) return "هذه المكالمة لم تعد متاحة"
    if (raw.contains("microphone_permission_required", true)) return "يلزم السماح بالميكروفون لإجراء المكالمة"
    if (raw.contains("microphone_publish_failed", true)) return "تعذر تشغيل الميكروفون. أغلق أي تطبيق يستخدمه وحاول مرة أخرى"
    if (raw.contains("camera_publish_failed", true)) return "تعذر تشغيل الكاميرا. أغلق أي تطبيق يستخدمها وحاول مرة أخرى"
    if (t is ApiException && t.status >= 500) return "تعذر الاتصال بخدمة المكالمات. حاول مرة أخرى"
    return "تعذر إنشاء الاتصال الصوتي. تحقق من الإنترنت وحاول مرة أخرى."
}'''
    new_friendly = '''private fun friendlyCallError(t: Throwable): String {
    val raw = t.message.orEmpty()
    if (raw.contains("livekit_not_configured", true)) return "خدمة المكالمات غير متاحة حالياً"
    if (raw.contains("call_no_longer_available", true)) return "هذه المكالمة لم تعد متاحة"
    if (raw.contains("peer_not_found", true)) return "هذا المستخدم غير متاح للمكالمة حالياً"
    if (raw.contains("blocked", true)) return "لا يمكن إجراء مكالمة مع هذا المستخدم"
    if (raw.contains("microphone_permission_required", true)) return "يلزم السماح بالميكروفون لإجراء المكالمة"
    if (raw.contains("microphone_publish_failed", true)) return "تعذر تشغيل الميكروفون. أغلق أي تطبيق يستخدمه وحاول مرة أخرى"
    if (raw.contains("camera_publish_failed", true)) return "تعذر تشغيل الكاميرا. أغلق أي تطبيق يستخدمها وحاول مرة أخرى"
    if (t is ApiException && t.status == 401) return "انتهت جلسة الدخول. افتح التطبيق وسجّل الدخول مرة أخرى"
    if (t is ApiException && t.status == 429) return "تم إجراء محاولات كثيرة. انتظر قليلاً ثم حاول مرة أخرى"
    if (t is ApiException && t.status >= 500) return "تعذر الاتصال بخدمة المكالمات. حاول مرة أخرى"
    if (t is java.io.IOException) return "تعذر الوصول إلى الشبكة. تحقق من اتصال الإنترنت وحاول مرة أخرى"
    return "تعذر بدء المكالمة. حاول مرة أخرى."
}'''
    replace_once(call, old_friendly, new_friendly)


def install_test_and_validation() -> None:
    test = Path("server/tests/stage1-direct-chat.mjs")
    test.write_text('''import assert from 'node:assert/strict';
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
''', encoding="utf-8")

    workflow = Path(".github/workflows/validate-v032.yml")
    old = '''          node tests/read-state.mjs
          node tests/auth-flow.mjs
          node tests/call-lifecycle.mjs'''
    new = '''          node tests/read-state.mjs
          node tests/auth-flow.mjs
          node tests/stage1-direct-chat.mjs
          node tests/call-lifecycle.mjs'''
    replace_once(workflow, old, new)


def main() -> None:
    server_only = len(sys.argv) > 1 and sys.argv[1] == "server-only"
    patch_server()
    if not server_only:
        patch_android()
        install_test_and_validation()


if __name__ == "__main__":
    main()
