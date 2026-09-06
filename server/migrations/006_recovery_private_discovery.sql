-- ZoolChat v0.22: private-ish contact discovery hashes + free recovery codes.

ALTER TABLE users ADD COLUMN IF NOT EXISTS phone_discovery_hash text;
UPDATE users SET phone_discovery_hash=encode(digest(phone_e164,'sha256'),'hex')
  WHERE phone_e164 IS NOT NULL AND phone_discovery_hash IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_phone_discovery_hash
  ON users(phone_discovery_hash) WHERE phone_discovery_hash IS NOT NULL;

CREATE TABLE IF NOT EXISTS account_recovery (
  user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  recovery_hash text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  rotated_at timestamptz NOT NULL DEFAULT now()
);
