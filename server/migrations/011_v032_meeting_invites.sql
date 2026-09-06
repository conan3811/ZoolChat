CREATE TABLE IF NOT EXISTS meeting_invitations (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 meeting_id uuid NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
 recipient_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 status text NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','accepted')),
 created_at timestamptz NOT NULL DEFAULT now(),
 expires_at timestamptz NOT NULL DEFAULT now()+interval '24 hours',
 UNIQUE(meeting_id,recipient_id)
);
