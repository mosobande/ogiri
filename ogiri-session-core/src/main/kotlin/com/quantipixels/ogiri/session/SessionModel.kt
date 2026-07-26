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
package com.quantipixels.ogiri.session

import java.time.Instant

/** Stable, Java-compatible identifier assigned to a persisted session. */
public class SessionId(public val value: String) {
  init {
    require(value.isNotBlank()) { "session ID must not be blank" }
  }

  public override fun equals(other: Any?): Boolean =
      this === other || (other is SessionId && value == other.value)

  public override fun hashCode(): Int = value.hashCode()

  public override fun toString(): String = value
}

/** Application-defined identifier of the account or principal that owns a session. */
@JvmInline
public value class SubjectId(public val value: String) {
  init {
    require(value.isNotBlank()) { "subject ID must not be blank" }
  }

  public override fun toString(): String = value
}

/**
 * Namespace in which a [SubjectId] is interpreted.
 *
 * Realm names are safe for storage keys and must contain 1–63 lowercase ASCII letters, digits,
 * dots, underscores, or hyphens.
 */
@JvmInline
public value class Realm(public val value: String) {
  init {
    require(value.matches(REALM_PATTERN)) {
      "realm must contain only lowercase ASCII letters, digits, dots, underscores, or hyphens"
    }
  }

  public override fun toString(): String = value

  private companion object {
    private val REALM_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,62}")
  }
}

/** Optional tenant boundary used to distinguish otherwise identical subjects. */
@JvmInline
public value class TenantId(public val value: String) {
  init {
    require(value.isNotBlank()) { "tenant ID must not be blank" }
  }

  public override fun toString(): String = value
}

/**
 * Canonical identity of a session owner.
 *
 * @property realm namespace of the subject identifier
 * @property subjectId identifier within [realm]
 * @property tenantId optional tenant boundary
 */
public data class SubjectRef
@JvmOverloads
public constructor(
    public val realm: Realm,
    public val subjectId: SubjectId,
    public val tenantId: TenantId? = null,
) {
  public fun realmName(): String = realm.value

  public fun subjectValue(): String = subjectId.value

  public fun tenantValue(): String? = tenantId?.value
}

/**
 * Metadata identifying the client on which a session was issued.
 *
 * Only [clientId] participates in client-targeted revocation. The remaining fields are descriptive
 * metadata suitable for session-management views and audit events.
 */
public data class ClientContext
@JvmOverloads
public constructor(
    public val clientId: String,
    public val label: String? = null,
    public val userAgent: String? = null,
    public val ipAddress: String? = null,
) {
  init {
    require(clientId.isNotBlank()) { "client ID must not be blank" }
  }
}

/**
 * One-way verifier digest stored with a session.
 *
 * @property keyId identifies the hashing key so credentials remain verifiable during key rotation
 * @property value encoded digest produced by [TokenHasher]
 */
public data class TokenDigest(public val keyId: String, public val value: String)

/**
 * Complete persisted state of a session.
 *
 * [currentDigest] authenticates the current credential. During rotation, [previousDigest] remains
 * valid only until [previousValidUntil], allowing a bounded grace window for concurrent requests. A
 * session is immutable; stores replace it using optimistic [version] checks.
 */
public data class StoredSession(
    public val id: SessionId,
    public val selector: String,
    public val subject: SubjectRef,
    public val client: ClientContext,
    public val currentDigest: TokenDigest,
    public val previousDigest: TokenDigest?,
    public val previousValidUntil: Instant?,
    public val version: Long,
    public val familyId: String,
    public val createdAt: Instant,
    public val lastUsedAt: Instant,
    public val expiresAt: Instant,
    public val revokedAt: Instant? = null,
    public val revocationReason: RevocationReason? = null,
) {
  init {
    require(version >= 0) { "session version must not be negative" }
    require(previousDigest != null || previousValidUntil == null) {
      "a previous deadline requires a previous digest"
    }
    require(previousDigest == null || previousValidUntil != null) {
      "a previous digest requires a fixed deadline"
    }
  }

  /** Returns whether this session is neither revoked nor expired at [at]. */
  public fun isActive(at: Instant): Boolean = revokedAt == null && at.isBefore(expiresAt)
}

/** Machine-readable reason recorded when a session is revoked. */
public enum class RevocationReason {
  SIGN_OUT,
  SIGN_OUT_ALL,
  ACCOUNT_DISABLED,
  PASSWORD_CHANGED,
  TOKEN_REUSE,
  SESSION_LIMIT,
  ADMINISTRATIVE,
  EXPIRED,
  PARENT_REVOKED,
}

/**
 * Secret credential returned to a client when a session is issued or rotated.
 *
 * The verifier is sensitive and must not be persisted or logged in plaintext.
 */
public data class SessionCredential(
    public val sessionId: SessionId,
    public val selector: String,
    public val verifier: String,
) {
  /** Encodes the public selector and secret verifier for transport using [codec]. */
  public fun encoded(codec: TokenCodec): String = codec.encode(selector, verifier)

  /** Returns a diagnostic representation without credential material. */
  public override fun toString(): String =
      "SessionCredential(sessionId=$sessionId, selector=[REDACTED], verifier=[REDACTED])"
}

/** Newly persisted session state together with the credential that authenticates it. */
public data class IssuedSession(
    public val session: StoredSession,
    public val credential: SessionCredential,
) {
  /** Returns a diagnostic representation without session credential material. */
  public override fun toString(): String =
      "IssuedSession(sessionId=${session.id}, credential=$credential)"
}

/**
 * Non-secret identity established after successful credential verification.
 *
 * [usedPreviousVersion] is true when authentication succeeded within the configured rotation grace
 * window and callers should return the latest credential rather than rotate again.
 */
public data class AuthenticatedSession(
    public val sessionId: SessionId,
    public val subject: SubjectRef,
    public val client: ClientContext,
    public val version: Long,
    public val familyId: String,
    public val usedPreviousVersion: Boolean,
)

/**
 * Expected session failure with a stable [code] suitable for protocol error responses.
 *
 * Concrete subclasses deliberately contain no credential or persistence details.
 */
public sealed class SessionError(public val code: String, message: String) :
    RuntimeException(message) {
  public class InvalidCredential :
      SessionError("invalid_credential", "The session credential is invalid")

  public class Expired : SessionError("session_expired", "The session has expired")

  public class Revoked : SessionError("session_revoked", "The session has been revoked")

  public class SubjectUnavailable :
      SessionError("subject_unavailable", "The subject cannot authenticate")

  public class Conflict :
      SessionError(
          "session_conflict", "The session changed concurrently; retry with current state")

  public class ReuseDetected :
      SessionError("token_reuse", "A superseded session credential was reused")

  public class SessionLimitReached :
      SessionError("session_limit", "The maximum active-session count was reached")
}
