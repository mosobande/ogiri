-- SPDX-License-Identifier: Apache-2.0
-- Explicit application-owned migration template, never auto-applied by the library.
CREATE TABLE ogiri_subject_locks (
    lock_key varbinary(32) PRIMARY KEY CHECK (octet_length(lock_key) = 32)
) ENGINE=InnoDB;
CREATE TABLE ogiri_sessions (
    id varchar(36) PRIMARY KEY,
    realm varchar(63) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    tenant_id varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    subject_id varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    client varchar(255) NOT NULL,
    token_hash varbinary(32) NOT NULL UNIQUE CHECK (octet_length(token_hash) = 32),
    created_at bigint NOT NULL,
    expires_at bigint NOT NULL,
    CONSTRAINT ogiri_expiry_after_creation CHECK (expires_at > created_at)
) ENGINE=InnoDB;
CREATE INDEX ogiri_sessions_owner ON ogiri_sessions (realm, tenant_id, subject_id, expires_at);
CREATE INDEX ogiri_sessions_expiry ON ogiri_sessions (expires_at, id);
