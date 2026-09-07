INSERT INTO user_crypto_keys(user_id,algorithm,public_key_b64,fingerprint_sha256,created_at,updated_at)
SELECT user_id,'P256-ECDH',public_key_b64,fingerprint_sha256,created_at,updated_at
FROM e2ee_identity_backups
ON CONFLICT(user_id) DO NOTHING;
