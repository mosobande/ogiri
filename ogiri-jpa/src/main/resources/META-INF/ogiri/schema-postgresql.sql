-- Copyright (c) 2025 Quanti Pixels
-- Licensed under the Apache License, Version 2.0.
CREATE
    TABLE
        ogiri_subject_locks(
            subject_key VARCHAR(600) PRIMARY KEY,
            record_version BIGINT NOT NULL DEFAULT 0
        );

CREATE
    TABLE
        ogiri_job_leases(
            job_name VARCHAR(128) PRIMARY KEY,
            owner_id VARCHAR(64),
            lease_until TIMESTAMP WITH TIME ZONE,
            record_version BIGINT NOT NULL DEFAULT 0
        );

CREATE
    TABLE
        ogiri_sessions(
            session_id VARCHAR(36) PRIMARY KEY,
            selector VARCHAR(64) NOT NULL,
            realm VARCHAR(63) NOT NULL,
            tenant_id VARCHAR(255),
            subject_id VARCHAR(255) NOT NULL,
            client_id VARCHAR(255) NOT NULL,
            client_label VARCHAR(255),
            user_agent VARCHAR(512),
            ip_address VARCHAR(64),
            current_key_id VARCHAR(64) NOT NULL,
            current_digest VARCHAR(128) NOT NULL,
            previous_key_id VARCHAR(64),
            previous_digest VARCHAR(128),
            previous_valid_until TIMESTAMP WITH TIME ZONE,
            record_version BIGINT NOT NULL DEFAULT 0,
            family_id VARCHAR(36) NOT NULL,
            created_at TIMESTAMP WITH TIME ZONE NOT NULL,
            last_used_at TIMESTAMP WITH TIME ZONE NOT NULL,
            expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
            revoked_at TIMESTAMP WITH TIME ZONE,
            revocation_reason VARCHAR(32),
            CONSTRAINT ux_ogiri_sessions_selector UNIQUE(selector),
            CONSTRAINT ck_ogiri_previous_pair CHECK(
                (
                    previous_digest IS NULL
                    AND previous_key_id IS NULL
                    AND previous_valid_until IS NULL
                )
                OR(
                    previous_digest IS NOT NULL
                    AND previous_key_id IS NOT NULL
                    AND previous_valid_until IS NOT NULL
                )
            )
        );

CREATE
    INDEX ix_ogiri_sessions_subject ON
    ogiri_sessions(
        realm,
        tenant_id,
        subject_id,
        revoked_at,
        expires_at
    );

CREATE
    INDEX ix_ogiri_sessions_cleanup ON
    ogiri_sessions(
        expires_at,
        revoked_at
    );
