-- SPDX-License-Identifier: Apache-2.0
-- Explicit application-owned migration template, never auto-applied by the library.
CREATE TABLE ogiri_subject_locks (
    lock_key bytea PRIMARY KEY CHECK (octet_length(lock_key) = 32)
);
CREATE TABLE ogiri_sessions (
    id varchar(36) PRIMARY KEY,
    realm varchar(63) COLLATE "C" NOT NULL,
    tenant_id varchar(255) COLLATE "C" NOT NULL,
    subject_id varchar(255) COLLATE "C" NOT NULL,
    client varchar(255) NOT NULL,
    token_hash bytea NOT NULL UNIQUE CHECK (octet_length(token_hash) = 32),
    created_at bigint NOT NULL,
    expires_at bigint NOT NULL CHECK (expires_at > created_at)
);
CREATE INDEX ogiri_sessions_owner ON ogiri_sessions (realm, tenant_id, subject_id, expires_at);
CREATE INDEX ogiri_sessions_expiry ON ogiri_sessions (expires_at, id);
