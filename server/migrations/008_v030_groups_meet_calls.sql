-- ZoolChat v0.30: real groups, ZoolMeet, and durable call timeline events.
ALTER TABLE chat_members ADD COLUMN IF NOT EXISTS role text NOT NULL DEFAULT 'member';
ALTER TABLE chat_members DROP CONSTRAINT IF EXISTS chat_members_role_check;
ALTER TABLE chat_members ADD CONSTRAINT chat_members_role_check CHECK (role IN ('owner','admin','member'));

CREATE TABLE IF NOT EXISTS group_settings (
  chat_id uuid PRIMARY KEY REFERENCES chats(id) ON DELETE CASCADE,
  owner_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  description text NOT NULL DEFAULT '',
  photo_url text,
  invite_code text NOT NULL UNIQUE,
  only_admins_send boolean NOT NULL DEFAULT false,
  only_admins_edit boolean NOT NULL DEFAULT false,
  only_admins_add boolean NOT NULL DEFAULT false,
  approve_new_members boolean NOT NULL DEFAULT false,
  disappearing_seconds integer NOT NULL DEFAULT 0 CHECK (disappearing_seconds IN (0,86400,604800,7776000)),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS group_join_requests (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  chat_id uuid NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','approved','denied')),
  created_at timestamptz NOT NULL DEFAULT now(),
  decided_at timestamptz,
  decided_by uuid REFERENCES users(id) ON DELETE SET NULL,
  UNIQUE(chat_id,user_id)
);

CREATE TABLE IF NOT EXISTS meetings (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  room_name text NOT NULL UNIQUE,
  code text NOT NULL UNIQUE,
  host_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  title text NOT NULL DEFAULT 'Zool Meet',
  require_approval boolean NOT NULL DEFAULT true,
  status text NOT NULL DEFAULT 'active' CHECK (status IN ('active','ended')),
  scheduled_for timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  ended_at timestamptz
);

CREATE TABLE IF NOT EXISTS meeting_participants (
  meeting_id uuid NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  role text NOT NULL DEFAULT 'member' CHECK (role IN ('host','member')),
  status text NOT NULL DEFAULT 'waiting' CHECK (status IN ('waiting','admitted','denied','left')),
  requested_at timestamptz NOT NULL DEFAULT now(),
  admitted_at timestamptz,
  joined_at timestamptz,
  left_at timestamptz,
  PRIMARY KEY(meeting_id,user_id)
);
CREATE INDEX IF NOT EXISTS idx_meeting_participants_user ON meeting_participants(user_id,meeting_id);
CREATE INDEX IF NOT EXISTS idx_meetings_code ON meetings(code);
