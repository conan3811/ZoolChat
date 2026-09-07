CREATE TABLE IF NOT EXISTS message_reactions (
  message_id uuid NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  emoji varchar(16) NOT NULL CHECK (length(trim(emoji)) BETWEEN 1 AND 16),
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(message_id,user_id)
);
CREATE INDEX IF NOT EXISTS message_reactions_message_idx ON message_reactions(message_id);
