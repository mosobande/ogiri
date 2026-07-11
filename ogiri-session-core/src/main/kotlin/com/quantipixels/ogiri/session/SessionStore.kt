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

/** Parameters for atomically creating and admitting a session. */
public data class CreateSessionCommand(
    public val session: StoredSession,
    public val maximumActiveSessions: Int,
    public val evictOldestWhenFull: Boolean,
)

/** Outcome of [SessionStore.create], including any session evicted to preserve the limit. */
public sealed interface CreateSessionResult {
  public data class Created(
      public val session: StoredSession,
      public val evictedSessionIds: List<SessionId>,
  ) : CreateSessionResult

  public data object LimitReached : CreateSessionResult

  public data object SelectorConflict : CreateSessionResult
}

/** Optimistic compare-and-set command for credential rotation. */
public data class RotateSessionCommand(
    public val sessionId: SessionId,
    public val expectedVersion: Long,
    public val expectedCurrentDigest: TokenDigest,
    public val replacementDigest: TokenDigest,
    public val previousValidUntil: Instant,
    public val usedAt: Instant,
)

/** Outcome of [SessionStore.compareAndRotate]. */
public sealed interface RotateSessionResult {
  public data class Rotated(public val session: StoredSession) : RotateSessionResult

  public data object Conflict : RotateSessionResult

  public data object Missing : RotateSessionResult
}

/** Parameters for idempotently revoking one session. */
public data class RevokeSessionCommand(
    public val sessionId: SessionId,
    public val expectedVersion: Long?,
    public val revokedAt: Instant,
    public val reason: RevocationReason,
)

/** Outcome of [SessionStore.revoke]. */
public sealed interface RevokeSessionResult {
  public data class Revoked(public val session: StoredSession) : RevokeSessionResult

  public data object AlreadyRevoked : RevokeSessionResult

  public data object Conflict : RevokeSessionResult

  public data object Missing : RevokeSessionResult
}

/**
 * Persistence boundary for immutable session snapshots.
 *
 * Implementations must make admission, rotation, and revocation atomic and must return detached
 * snapshots that callers cannot mutate after commit.
 */
public interface SessionStore {
  /** Creates and admits a session atomically with the maximum-session invariant. */
  public fun create(command: CreateSessionCommand): CreateSessionResult

  /** Looks up one immutable committed snapshot by non-secret selector. */
  public fun findBySelector(selector: String): StoredSession?

  /** Looks up one immutable committed snapshot by stable session ID. */
  public fun findById(sessionId: SessionId): StoredSession?

  /** Compares the expected version and digest and commits exactly one successor. */
  public fun compareAndRotate(command: RotateSessionCommand): RotateSessionResult

  /** Updates activity without changing credential versions or grace deadlines. */
  public fun recordUse(sessionId: SessionId, expectedVersion: Long, usedAt: Instant): Boolean

  /** Revokes one stable session identity. */
  public fun revoke(command: RevokeSessionCommand): RevokeSessionResult

  /** Revokes every active session for a subject and returns affected stable IDs. */
  public fun revokeAll(
      subject: SubjectRef,
      revokedAt: Instant,
      reason: RevocationReason
  ): List<SessionId>

  /** Lists sessions for [subject] that are active at [at], newest activity first. */
  public fun listActive(subject: SubjectRef, at: Instant): List<StoredSession>

  /** Deletes at most [limit] expired or revoked rows and commits that page independently. */
  public fun deleteExpiredPage(before: Instant, limit: Int): Int
}
