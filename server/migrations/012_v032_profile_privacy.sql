ALTER TABLE user_privacy_settings
  ADD COLUMN IF NOT EXISTS profile_photo_visibility text NOT NULL DEFAULT 'contacts'
    CHECK (profile_photo_visibility IN ('everyone','contacts','nobody')),
  ADD COLUMN IF NOT EXISTS about_visibility text NOT NULL DEFAULT 'contacts'
    CHECK (about_visibility IN ('everyone','contacts','nobody'));

ALTER TABLE users
  ADD COLUMN IF NOT EXISTS profile_photo_media_id uuid REFERENCES media_assets(id) ON DELETE SET NULL;

ALTER TABLE devices
  ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();

CREATE INDEX IF NOT EXISTS idx_users_profile_photo_media_id ON users(profile_photo_media_id);
