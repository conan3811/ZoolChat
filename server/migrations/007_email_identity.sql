-- ZoolChat v0.26: required email identity for new registrations and email-or-username login.
ALTER TABLE users ADD COLUMN IF NOT EXISTS email text;
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_email_lower
  ON users(lower(email)) WHERE email IS NOT NULL;
