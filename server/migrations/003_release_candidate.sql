-- ZoolChat v0.17 release-candidate hardening and lifecycle tables.

ALTER TABLE devices ADD COLUMN IF NOT EXISTS device_name text;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS created_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE devices ADD COLUMN IF NOT EXISTS last_seen_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE devices ADD COLUMN IF NOT EXISTS revoked_at timestamptz;
CREATE INDEX IF NOT EXISTS idx_devices_user_active ON devices(user_id) WHERE revoked_at IS NULL;

CREATE TABLE IF NOT EXISTS user_privacy_settings (
  user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  read_receipts boolean NOT NULL DEFAULT true,
  show_last_seen boolean NOT NULL DEFAULT true,
  show_online boolean NOT NULL DEFAULT true,
  updated_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE calls ADD COLUMN IF NOT EXISTS answered_at timestamptz;
ALTER TABLE calls ADD COLUMN IF NOT EXISTS ended_by uuid REFERENCES users(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_calls_created ON calls(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_call_participants_user ON call_participants(user_id,call_id);

-- Helps cursor pagination and search while keeping the existing index compatible.
CREATE INDEX IF NOT EXISTS idx_messages_chat_created_id ON messages(chat_id,created_at DESC,id DESC);
