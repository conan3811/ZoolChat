from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def patch(rel: str, old: str, new: str, expected: int = 1):
    p = ROOT / rel
    text = p.read_text(encoding='utf-8')
    count = text.count(old)
    if count != expected:
        raise SystemExit(f'{rel}: expected {expected} occurrences, found {count}: {old[:120]!r}')
    p.write_text(text.replace(old, new), encoding='utf-8')

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

# Self identity presence is recoverable from either the canonical public-key row or the encrypted
# identity backup. This makes recovery robust if an older account has one side populated first.
patch(
    'server/src/index.ts',
    "  const r=await pool.query(`SELECT algorithm,public_key_b64,fingerprint_sha256,updated_at FROM user_crypto_keys WHERE user_id=$1`,[req.zoolUser!.id]);\n  if(!r.rowCount)return res.status(404).json({error:'e2ee_key_not_found'});",
    "  const r=await pool.query(`SELECT algorithm,public_key_b64,fingerprint_sha256,updated_at FROM user_crypto_keys WHERE user_id=$1 UNION ALL SELECT 'P256-ECDH'::text algorithm,public_key_b64,fingerprint_sha256,updated_at FROM e2ee_identity_backups WHERE user_id=$1 LIMIT 1`,[req.zoolUser!.id]);\n  if(!r.rowCount)return res.status(404).json({error:'e2ee_key_not_found'});",
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

print('PASS: hardened feedback round 2 patch')
