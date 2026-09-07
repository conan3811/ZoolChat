CREATE TABLE IF NOT EXISTS e2ee_identity_backups (
  user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  version integer NOT NULL DEFAULT 1 CHECK (version = 1),
  public_key_b64 text NOT NULL CHECK (length(public_key_b64) BETWEEN 80 AND 4096),
  fingerprint_sha256 text NOT NULL CHECK (length(fingerprint_sha256) BETWEEN 32 AND 200),
  salt_b64 text NOT NULL CHECK (length(salt_b64) BETWEEN 16 AND 256),
  iv_b64 text NOT NULL CHECK (length(iv_b64) BETWEEN 12 AND 128),
  ciphertext_b64 text NOT NULL CHECK (length(ciphertext_b64) BETWEEN 64 AND 8192),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
