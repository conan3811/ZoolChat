-- ZoolChat v0.31: admin dashboard, moderation state and audit trail.
ALTER TABLE users ADD COLUMN IF NOT EXISTS account_status text NOT NULL DEFAULT 'active';
ALTER TABLE users ADD COLUMN IF NOT EXISTS suspended_at timestamptz;
ALTER TABLE users ADD COLUMN IF NOT EXISTS suspension_reason text;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='users_account_status_check') THEN
    ALTER TABLE users ADD CONSTRAINT users_account_status_check CHECK (account_status IN ('active','suspended'));
  END IF;
END $$;
CREATE INDEX IF NOT EXISTS idx_users_account_status ON users(account_status);

ALTER TABLE reports ADD COLUMN IF NOT EXISTS status text NOT NULL DEFAULT 'open';
ALTER TABLE reports ADD COLUMN IF NOT EXISTS resolved_at timestamptz;
ALTER TABLE reports ADD COLUMN IF NOT EXISTS resolution_note text;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='reports_status_check') THEN
    ALTER TABLE reports ADD CONSTRAINT reports_status_check CHECK (status IN ('open','dismissed','resolved'));
  END IF;
END $$;
CREATE INDEX IF NOT EXISTS idx_reports_status_created ON reports(status,created_at DESC);

CREATE TABLE IF NOT EXISTS admin_audit_log (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  admin_name text NOT NULL,
  action text NOT NULL,
  target_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
  target_report_id uuid REFERENCES reports(id) ON DELETE SET NULL,
  metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
  ip text,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_admin_audit_created ON admin_audit_log(created_at DESC);
