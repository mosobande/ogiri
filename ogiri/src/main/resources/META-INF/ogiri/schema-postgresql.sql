-- SPDX-License-Identifier: Apache-2.0
-- Explicit template: copy into an application-owned migration. Never auto-applied by Ogiri.
CREATE TABLE ogiri_sessions (
    id uuid PRIMARY KEY,
    realm varchar(63) COLLATE "C" NOT NULL,
    tenant_id varchar(255) COLLATE "C" NOT NULL,
    subject_id varchar(255) COLLATE "C" NOT NULL,
    client varchar(255) NOT NULL,
    token_hash bytea NOT NULL UNIQUE CHECK (octet_length(token_hash) = 32),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL CHECK (expires_at > created_at)
);
CREATE INDEX ogiri_sessions_owner ON ogiri_sessions (realm, tenant_id, subject_id, expires_at);
CREATE INDEX ogiri_sessions_expiry ON ogiri_sessions (expires_at, id);
