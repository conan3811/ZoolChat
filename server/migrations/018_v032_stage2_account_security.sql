-- ZoolChat v0.32 stage 2: account security and session/device binding.
CREATE TABLE IF NOT EXISTS user_two_factor_settings (
  user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  enabled boolean NOT NULL DEFAULT false,
  enabled_at timestamptz,
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS two_factor_challenges (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  purpose text NOT NULL CHECK (purpose IN ('login','enable','disable')),
  challenge_hash text NOT NULL UNIQUE,
  code_hash text NOT NULL,
  device_name text,
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL DEFAULT now() + interval '10 minutes',
  attempts integer NOT NULL DEFAULT 0,
  consumed_at timestamptz
);
CREATE INDEX IF NOT EXISTS two_factor_challenges_lookup
  ON two_factor_challenges(user_id,purpose,created_at DESC);

ALTER TABLE devices ADD COLUMN IF NOT EXISTS auth_session_id uuid REFERENCES auth_sessions(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS devices_auth_session_idx ON devices(auth_session_id) WHERE auth_session_id IS NOT NULL;
