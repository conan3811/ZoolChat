from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

def patch(rel: str, old: str, new: str, expected: int = 1):
    p = ROOT / rel
    text = p.read_text(encoding='utf-8')
    count = text.count(old)
    if count != expected:
        raise SystemExit(f'{rel}: expected {expected} occurrences, found {count}: {old[:120]!r}')
    p.write_text(text.replace(old, new), encoding='utf-8')

def regex_patch(rel: str, pattern: str, replacement: str, expected: int = 1):
    p = ROOT / rel
    text = p.read_text(encoding='utf-8')
    next_text, count = re.subn(pattern, replacement, text, count=expected, flags=re.S)
    if count != expected:
        raise SystemExit(f'{rel}: expected {expected} regex matches, found {count}: {pattern[:120]!r}')
    p.write_text(next_text, encoding='utf-8')

# Avoid ambiguity between message.type and chat.type in group-admin deletion authorization.
patch(
    'server/src/index.ts',
    "SELECT m.*,c.type,cm.role FROM messages m JOIN chats c ON c.id=m.chat_id",
    "SELECT m.*,c.type chat_type,cm.role FROM messages m JOIN chats c ON c.id=m.chat_id",
)
patch(
    'server/src/index.ts',
    "const adminCanDelete=m.type==='group'&&['owner','admin'].includes(String(m.role));",
    "const adminCanDelete=m.chat_type==='group'&&['owner','admin'].includes(String(m.role));",
)

# emailRecovery.ts is installed before the legacy routes and is therefore the authoritative
# account-security/E2EE backup surface. Keep the public key row and encrypted identity backup
# transactionally coherent, and allow backup-only records created by an older v0.32 build to be
# recognized during recovery.
patch(
    'server/src/emailRecovery.ts',
    "    const r=await pool.query('SELECT algorithm,public_key_b64,fingerprint_sha256,updated_at FROM user_crypto_keys WHERE user_id=$1',[req.zoolUser!.id]);",
    "    const r=await pool.query(`SELECT algorithm,public_key_b64,fingerprint_sha256,updated_at FROM user_crypto_keys WHERE user_id=$1 UNION ALL SELECT 'P256-ECDH'::text algorithm,public_key_b64,fingerprint_sha256,updated_at FROM e2ee_identity_backups WHERE user_id=$1 AND NOT EXISTS(SELECT 1 FROM user_crypto_keys WHERE user_id=$1) LIMIT 1`,[req.zoolUser!.id]);",
)
patch(
    'server/src/emailRecovery.ts',
    "    const current=await pool.query('SELECT public_key_b64,fingerprint_sha256 FROM user_crypto_keys WHERE user_id=$1',[uid]);",
    "    const current=await pool.query(`SELECT public_key_b64,fingerprint_sha256 FROM user_crypto_keys WHERE user_id=$1 UNION ALL SELECT public_key_b64,fingerprint_sha256 FROM e2ee_identity_backups WHERE user_id=$1 AND NOT EXISTS(SELECT 1 FROM user_crypto_keys WHERE user_id=$1) LIMIT 1`,[uid]);",
)
patch(
    'server/src/emailRecovery.ts',
    """    await pool.query(`INSERT INTO e2ee_identity_backups(user_id,version,public_key_b64,fingerprint_sha256,salt_b64,iv_b64,ciphertext_b64)
      VALUES($1,$2,$3,$4,$5,$6,$7)
      ON CONFLICT(user_id) DO UPDATE SET version=EXCLUDED.version,public_key_b64=EXCLUDED.public_key_b64,fingerprint_sha256=EXCLUDED.fingerprint_sha256,salt_b64=EXCLUDED.salt_b64,iv_b64=EXCLUDED.iv_b64,ciphertext_b64=EXCLUDED.ciphertext_b64,updated_at=now()`,[uid,b.data.version,b.data.publicKeyB64,b.data.fingerprintSha256,b.data.saltB64,b.data.ivB64,b.data.ciphertextB64]);
""",
    """    await tx(async c=>{
      await c.query(`INSERT INTO user_crypto_keys(user_id,algorithm,public_key_b64,fingerprint_sha256)
        VALUES($1,'P256-ECDH',$2,$3)
        ON CONFLICT(user_id) DO UPDATE SET public_key_b64=EXCLUDED.public_key_b64,fingerprint_sha256=EXCLUDED.fingerprint_sha256,updated_at=now()`,[uid,b.data.publicKeyB64,b.data.fingerprintSha256]);
      await c.query(`INSERT INTO e2ee_identity_backups(user_id,version,public_key_b64,fingerprint_sha256,salt_b64,iv_b64,ciphertext_b64)
        VALUES($1,$2,$3,$4,$5,$6,$7)
        ON CONFLICT(user_id) DO UPDATE SET version=EXCLUDED.version,public_key_b64=EXCLUDED.public_key_b64,fingerprint_sha256=EXCLUDED.fingerprint_sha256,salt_b64=EXCLUDED.salt_b64,iv_b64=EXCLUDED.iv_b64,ciphertext_b64=EXCLUDED.ciphertext_b64,updated_at=now()`,[uid,b.data.version,b.data.publicKeyB64,b.data.fingerprintSha256,b.data.saltB64,b.data.ivB64,b.data.ciphertextB64]);
    });
""",
)

# Backfill canonical public-key rows for accounts that already have an encrypted identity backup
# created by an earlier build. This preserves peer-key lookup after an app reinstall without
# exposing the private key to the server.
migration = ROOT / 'server/migrations/017_v032_e2ee_key_backfill.sql'
if not migration.exists():
    migration.write_text("""INSERT INTO user_crypto_keys(user_id,algorithm,public_key_b64,fingerprint_sha256,created_at,updated_at)
SELECT user_id,'P256-ECDH',public_key_b64,fingerprint_sha256,created_at,updated_at
FROM e2ee_identity_backups
ON CONFLICT(user_id) DO NOTHING;
""", encoding='utf-8')

# The round-2 generator originally added a second copy of the same E2EE/password handlers to
# index.ts. Remove that duplicate surface so Express has one authoritative implementation.
regex_patch(
    'server/src/index.ts',
    r"\napp\.get\('/api/e2ee/self-key', requireAuth, async \(req,res\)=>\{.*?\napp\.post\('/api/me/password/verify', requireAuth, async \(req,res\)=>\{.*?\n\}\);\n",
    "\n",
)

# An edit response/event must not carry viewer-specific reactedByMe flags to other users.
patch(
    'server/src/index.ts',
    "  const reactions=await messageReactionMap([String(r.rows[0].id)],uid);\n  const payload=messageJson({...r.rows[0],status:'sent',reactions:reactions.get(String(r.rows[0].id))??[]});",
    "  const payload=messageJson({...r.rows[0],status:'sent',reactions:[]});",
)

# Preserve a cached reaction set when an edited-message realtime payload deliberately has no
# viewer-specific reaction state.
patch(
    'android/app/src/main/java/com/zoolchat/app/sync/MessageSyncRepository.kt',
    "    suspend fun onEditedMessage(message: RemoteMessage) {\n        val decoded = decode(message)\n        db.messages().upsert(decoded.toEntity(localCrypto))\n        updatePreview(decoded.chatId, previewFor(decoded), decoded.createdAt)\n    }",
    "    suspend fun onEditedMessage(message: RemoteMessage) {\n        val decoded = decode(message)\n        val existing = db.messages().byId(decoded.id)?.toRemote(localCrypto)\n        val merged = if (decoded.reactions.isEmpty() && existing != null) decoded.copy(reactions = existing.reactions) else decoded\n        db.messages().upsert(merged.toEntity(localCrypto))\n        updatePreview(merged.chatId, previewFor(merged), merged.createdAt)\n    }",
)

# Reactions are user-relative (reactedByMe), so realtime recipients re-fetch the authoritative
# page instead of trusting the requester's snapshot.
patch(
    'android/app/src/main/java/com/zoolchat/app/state/ZoolAppViewModel.kt',
    "            \"message.reaction\" -> {\n                val d = event.getJSONObject(\"data\")\n                viewModelScope.launch { sync.applyReactionSnapshot(d.getString(\"messageId\"), d.optJSONArray(\"reactions\") ?: org.json.JSONArray()) }\n            }",
    "            \"message.reaction\" -> {\n                val d = event.getJSONObject(\"data\")\n                val chatId = d.getString(\"chatId\")\n                viewModelScope.launch {\n                    runCatching {\n                        val result = sync.refreshMessages(chatId)\n                        _hasMoreMessages.value = _hasMoreMessages.value + (chatId to result.hasMore)\n                    }.onFailure { _error.value = it.message }\n                }\n            }",
)

# Server-side reaction broadcast intentionally contains no viewer-relative snapshot.
patch(
    'server/src/index.ts',
    "for(const p of members.rows)emitTo(p.user_id,{type:'message.reaction',data:{chatId,messageId:req.params.messageId,reactions}});",
    "for(const p of members.rows)emitTo(p.user_id,{type:'message.reaction',data:{chatId,messageId:req.params.messageId}});",
    expected=2,
)

# The integration test also verifies that uploading an encrypted identity backup immediately makes
# the corresponding public key available to another participant.
patch(
    'server/tests/message-interactions.mjs',
    "  assert.equal((await get('/api/e2ee/self-key',a.token)).status,200,'self identity metadata must exist after backup upload');\n  const restored=await get('/api/e2ee/backup',a.token);",
    "  assert.equal((await get('/api/e2ee/self-key',a.token)).status,200,'self identity metadata must exist after backup upload');\n  assert.equal((await get(`/api/users/${a.id}/e2ee-key`,b.token)).status,200,'peer key lookup must work after backup upload');\n  const restored=await get('/api/e2ee/backup',a.token);",
)

print('PASS: hardened feedback round 2 patch')
