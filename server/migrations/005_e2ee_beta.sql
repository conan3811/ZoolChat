-- ZoolChat v0.19: one-device-per-account E2EE beta for direct chats/calls.
-- Server stores only public identity keys and encrypted message envelopes.

CREATE TABLE IF NOT EXISTS user_crypto_keys (
  user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  algorithm text NOT NULL DEFAULT 'P256-ECDH',
  public_key_b64 text NOT NULL,
  fingerprint_sha256 text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE messages ADD COLUMN IF NOT EXISTS encrypted boolean NOT NULL DEFAULT false;
ALTER TABLE messages ADD COLUMN IF NOT EXISTS crypto_version integer NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_messages_encrypted ON messages(chat_id, encrypted, created_at DESC);
