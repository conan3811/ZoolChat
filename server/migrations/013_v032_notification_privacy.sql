ALTER TABLE user_privacy_settings
  ADD COLUMN IF NOT EXISTS message_previews boolean NOT NULL DEFAULT true;
