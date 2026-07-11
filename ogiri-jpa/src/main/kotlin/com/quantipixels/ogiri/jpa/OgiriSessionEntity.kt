/*
 * Copyright (c) 2025 Quanti Pixels
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.quantipixels.ogiri.jpa

import com.quantipixels.ogiri.session.RevocationReason
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant

@Entity
@Table(
    name = "ogiri_sessions",
    indexes =
        [
            Index(name = "ux_ogiri_sessions_selector", columnList = "selector", unique = true),
            Index(
                name = "ix_ogiri_sessions_subject",
                columnList = "realm, tenant_id, subject_id, revoked_at, expires_at"),
            Index(name = "ix_ogiri_sessions_cleanup", columnList = "expires_at, revoked_at"),
        ],
)
public class OgiriSessionEntity(
    @Id @Column(name = "session_id", length = 36, nullable = false) public var sessionId: String,
    @Column(name = "selector", length = 64, nullable = false, unique = true)
    public var selector: String,
    @Column(name = "realm", length = 63, nullable = false) public var realm: String,
    @Column(name = "tenant_id", length = 255) public var tenantId: String?,
    @Column(name = "subject_id", length = 255, nullable = false) public var subjectId: String,
    @Column(name = "client_id", length = 255, nullable = false) public var clientId: String,
    @Column(name = "client_label", length = 255) public var clientLabel: String?,
    @Column(name = "user_agent", length = 512) public var userAgent: String?,
    @Column(name = "ip_address", length = 64) public var ipAddress: String?,
    @Column(name = "current_key_id", length = 64, nullable = false) public var currentKeyId: String,
    @Column(name = "current_digest", length = 128, nullable = false)
    public var currentDigest: String,
    @Column(name = "previous_key_id", length = 64) public var previousKeyId: String?,
    @Column(name = "previous_digest", length = 128) public var previousDigest: String?,
    @Column(name = "previous_valid_until") public var previousValidUntil: Instant?,
    @Version @Column(name = "record_version", nullable = false) public var recordVersion: Long,
    @Column(name = "family_id", length = 36, nullable = false) public var familyId: String,
    @Column(name = "created_at", nullable = false, updatable = false) public var createdAt: Instant,
    @Column(name = "last_used_at", nullable = false) public var lastUsedAt: Instant,
    @Column(name = "expires_at", nullable = false) public var expiresAt: Instant,
    @Column(name = "revoked_at") public var revokedAt: Instant?,
    @Enumerated(EnumType.STRING)
    @Column(name = "revocation_reason", length = 32)
    public var revocationReason: RevocationReason?,
) {
  protected constructor() :
      this(
          "",
          "",
          "",
          null,
          "",
          "",
          null,
          null,
          null,
          "",
          "",
          null,
          null,
          null,
          0,
          "",
          Instant.EPOCH,
          Instant.EPOCH,
          Instant.EPOCH,
          null,
          null,
      )
}

@Entity
@Table(name = "ogiri_subject_locks")
public class OgiriSubjectLockEntity(
    @Id @Column(name = "subject_key", length = 600, nullable = false) public var subjectKey: String,
    @Version @Column(name = "record_version", nullable = false) public var recordVersion: Long = 0,
) {
  protected constructor() : this("")
}

@Entity
@Table(name = "ogiri_job_leases")
public class OgiriJobLeaseEntity(
    @Id @Column(name = "job_name", length = 128, nullable = false) public var jobName: String,
    @Column(name = "owner_id", length = 64) public var ownerId: String?,
    @Column(name = "lease_until") public var leaseUntil: Instant?,
    @Version @Column(name = "record_version", nullable = false) public var recordVersion: Long = 0,
) {
  protected constructor() : this("", null, null)
}
