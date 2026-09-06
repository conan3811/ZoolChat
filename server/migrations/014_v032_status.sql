CREATE TABLE IF NOT EXISTS status_posts (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind text NOT NULL CHECK (kind IN ('text','image','video','voice','link','collage','music')),
  text_content text NOT NULL DEFAULT '',
  link_url text,
  audience_mode text NOT NULL DEFAULT 'contacts' CHECK (audience_mode IN ('contacts','except','only')),
  audience_user_ids uuid[] NOT NULL DEFAULT '{}'::uuid[],
  layout_json jsonb,
  add_yours_prompt text,
  reshare_of uuid REFERENCES status_posts(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL DEFAULT (now() + interval '24 hours'),
  CHECK (expires_at > created_at)
);
CREATE INDEX IF NOT EXISTS idx_status_posts_owner_created ON status_posts(owner_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_status_posts_active ON status_posts(expires_at DESC) WHERE expires_at > created_at;

CREATE TABLE IF NOT EXISTS status_media (
  status_id uuid NOT NULL REFERENCES status_posts(id) ON DELETE CASCADE,
  media_id uuid NOT NULL REFERENCES media_assets(id) ON DELETE CASCADE,
  role text NOT NULL DEFAULT 'primary' CHECK (role IN ('primary','sticker','music')),
  position integer NOT NULL DEFAULT 0,
  PRIMARY KEY(status_id,media_id,role)
);
CREATE INDEX IF NOT EXISTS idx_status_media_media ON status_media(media_id);

CREATE TABLE IF NOT EXISTS status_mentions (
  status_id uuid NOT NULL REFERENCES status_posts(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(status_id,user_id)
);
CREATE INDEX IF NOT EXISTS idx_status_mentions_user ON status_mentions(user_id,created_at DESC);

CREATE TABLE IF NOT EXISTS status_views (
  status_id uuid NOT NULL REFERENCES status_posts(id) ON DELETE CASCADE,
  viewer_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  viewed_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(status_id,viewer_id)
);

CREATE TABLE IF NOT EXISTS status_likes (
  status_id uuid NOT NULL REFERENCES status_posts(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(status_id,user_id)
);

CREATE TABLE IF NOT EXISTS status_reactions (
  status_id uuid NOT NULL REFERENCES status_posts(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  emoji text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(status_id,user_id)
);

ALTER TABLE user_privacy_settings
  ADD COLUMN IF NOT EXISTS status_audience_mode text NOT NULL DEFAULT 'contacts';
ALTER TABLE user_privacy_settings
  ADD COLUMN IF NOT EXISTS status_audience_user_ids uuid[] NOT NULL DEFAULT '{}'::uuid[];
