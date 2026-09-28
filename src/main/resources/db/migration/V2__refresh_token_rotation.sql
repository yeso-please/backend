SET search_path TO app, public;

ALTER TABLE refresh_tokens
    ADD COLUMN family_id UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN revoked_at TIMESTAMP WITHOUT TIME ZONE,
    ADD COLUMN replaced_by_token_id BIGINT REFERENCES refresh_tokens(id) ON DELETE SET NULL;

UPDATE refresh_tokens SET revoked_at = CURRENT_TIMESTAMP WHERE revoked = TRUE;

DROP INDEX IF EXISTS idx_refresh_tokens_expiry;
ALTER TABLE refresh_tokens DROP COLUMN revoked;

CREATE INDEX idx_refresh_tokens_family ON refresh_tokens(family_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_refresh_tokens_expiry ON refresh_tokens(expires_at) WHERE revoked_at IS NULL;
