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

import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Determines whether a subject is currently permitted to authenticate. */
public fun interface SubjectStatusChecker {
  /** Returns `false` when issuance and authentication must reject [subject]. */
  public fun isAllowed(subject: SubjectRef): Boolean
}

/** Security and admission policy applied by [SessionManager]. */
public data class SessionPolicy
@JvmOverloads
public constructor(
    public val lifetime: Duration = Duration.ofDays(14),
    public val previousVersionGrace: Duration = Duration.ofSeconds(5),
    public val maximumActiveSessions: Int = 10,
    public val evictOldestWhenFull: Boolean = true,
) {
  init {
    require(!lifetime.isNegative && !lifetime.isZero) { "session lifetime must be positive" }
    require(!previousVersionGrace.isNegative) { "previous-version grace must not be negative" }
    require(maximumActiveSessions > 0) { "maximum active sessions must be positive" }
  }
}

/** Lifecycle action emitted after a successful session-store operation. */
public enum class SessionEventAction {
  ISSUED,
  AUTHENTICATED,
  ROTATED,
  REVOKED,
  REVOKED_ALL,
  REUSE_DETECTED,
  EVICTED,
}

/** Immutable audit event describing a committed session lifecycle change. */
public data class SessionEvent(
    public val eventId: String,
    public val occurredAt: Instant,
    public val action: SessionEventAction,
    public val subject: SubjectRef,
    public val sessionId: SessionId?,
    public val familyId: String?,
    public val reason: RevocationReason? = null,
    public val correlationId: String? = null,
)

/** Receives session lifecycle events after the corresponding store command succeeds. */
public fun interface SessionEventPublisher {
  /** Called only after the store command returns successfully. */
  public fun publish(event: SessionEvent)
}

/** Event publisher used when session lifecycle events are not observed. */
public object NoOpSessionEventPublisher : SessionEventPublisher {
  override fun publish(event: SessionEvent): Unit = Unit
}

/**
 * Coordinates session issuance, authentication, rotation, and revocation.
 *
 * Credentials are split into a non-secret selector and a hashed verifier. Mutating operations use
 * optimistic versions supplied by [SessionStore], and lifecycle events are published only after a
 * store operation commits.
 */
public class SessionManager
@JvmOverloads
public constructor(
    private val store: SessionStore,
    private val codec: TokenCodec,
    private val hasher: TokenHasher,
    private val statusChecker: SubjectStatusChecker,
    private val clock: Clock,
    private val policy: SessionPolicy = SessionPolicy(),
    private val events: SessionEventPublisher = NoOpSessionEventPublisher,
    private val identifiers: IdentifierGenerator = SecureRandomIdentifierGenerator(SecureRandom()),
) {
  /**
   * Issues a new session for [subject] and [client].
   *
   * The store atomically enforces [SessionPolicy.maximumActiveSessions]. Selector collisions are
   * retried with freshly generated credential material.
   *
   * @throws SessionError.SubjectUnavailable when the subject is not allowed to authenticate
   * @throws SessionError.SessionLimitReached when admission is full and eviction is disabled
   */
  public fun issue(subject: SubjectRef, client: ClientContext): IssuedSession =
      issue(subject, client, 0)

  private fun issue(subject: SubjectRef, client: ClientContext, attempt: Int): IssuedSession {
    check(attempt < 3) { "Session store repeatedly rejected generated selectors" }
    if (!statusChecker.isAllowed(subject)) throw SessionError.SubjectUnavailable()
    val now = clock.instant()
    val generated = codec.generate()
    val sessionId = SessionId(identifiers.next())
    val familyId = identifiers.next()
    val session =
        StoredSession(
            id = sessionId,
            selector = generated.selector,
            subject = subject,
            client = client,
            currentDigest = hasher.digest(generated.verifier),
            previousDigest = null,
            previousValidUntil = null,
            version = 0,
            familyId = familyId,
            createdAt = now,
            lastUsedAt = now,
            expiresAt = now.plus(policy.lifetime),
        )
    return when (val result =
        store.create(
            CreateSessionCommand(
                session,
                policy.maximumActiveSessions,
                policy.evictOldestWhenFull,
            ))) {
      is CreateSessionResult.Created -> {
        result.evictedSessionIds.forEach {
          publish(
              now, SessionEventAction.EVICTED, subject, it, null, RevocationReason.SESSION_LIMIT)
        }
        publish(now, SessionEventAction.ISSUED, subject, sessionId, familyId)
        IssuedSession(
            result.session,
            SessionCredential(sessionId, generated.selector, generated.verifier),
        )
      }
      CreateSessionResult.LimitReached -> throw SessionError.SessionLimitReached()
      CreateSessionResult.SelectorConflict -> issue(subject, client, attempt + 1)
    }
  }

  /**
   * Verifies an encoded credential and records successful use.
   *
   * Reusing a previous credential after its grace deadline revokes the session as token reuse.
   *
   * @throws SessionError for malformed, invalid, expired, revoked, or disallowed credentials
   */
  public fun authenticate(encodedCredential: String): AuthenticatedSession {
    val decoded = decode(encodedCredential)
    val session = store.findBySelector(decoded.selector) ?: throw SessionError.InvalidCredential()
    val now = clock.instant()
    if (session.revokedAt != null) throw SessionError.Revoked()
    if (!now.isBefore(session.expiresAt)) throw SessionError.Expired()

    val current = hasher.matches(decoded.verifier, session.currentDigest)
    val previous =
        !current && (session.previousDigest?.let { hasher.matches(decoded.verifier, it) } ?: false)
    if (!current && previous) {
      if (session.previousValidUntil?.let(now::isBefore) != true) {
        store.revoke(RevokeSessionCommand(session.id, null, now, RevocationReason.TOKEN_REUSE))
        publish(
            now,
            SessionEventAction.REUSE_DETECTED,
            session.subject,
            session.id,
            session.familyId,
            RevocationReason.TOKEN_REUSE,
        )
        throw SessionError.ReuseDetected()
      }
    } else if (!current) {
      throw SessionError.InvalidCredential()
    }

    if (!statusChecker.isAllowed(session.subject)) {
      store.revoke(
          RevokeSessionCommand(
              session.id,
              null,
              now,
              RevocationReason.ACCOUNT_DISABLED,
          ))
      throw SessionError.SubjectUnavailable()
    }

    if (!store.recordUse(session.id, session.version, now)) throw SessionError.Conflict()
    publish(now, SessionEventAction.AUTHENTICATED, session.subject, session.id, session.familyId)
    return session.authenticated(usedPreviousVersion = previous)
  }

  /**
   * Replaces the verifier while retaining the same session identity and selector.
   *
   * @throws SessionError.Conflict when another request already rotated this version
   */
  public fun rotate(encodedCredential: String): IssuedSession {
    val decoded = decode(encodedCredential)
    val session = store.findBySelector(decoded.selector) ?: throw SessionError.InvalidCredential()
    val now = clock.instant()
    if (!session.isActive(now)) {
      if (session.revokedAt != null) throw SessionError.Revoked() else throw SessionError.Expired()
    }
    if (!hasher.matches(decoded.verifier, session.currentDigest)) {
      if (session.previousDigest?.let { hasher.matches(decoded.verifier, it) } == true) {
        if (session.previousValidUntil?.let(now::isBefore) != true) {
          store.revoke(RevokeSessionCommand(session.id, null, now, RevocationReason.TOKEN_REUSE))
          publish(
              now,
              SessionEventAction.REUSE_DETECTED,
              session.subject,
              session.id,
              session.familyId,
              RevocationReason.TOKEN_REUSE)
          throw SessionError.ReuseDetected()
        }
        throw SessionError.Conflict()
      }
      throw SessionError.InvalidCredential()
    }

    if (!statusChecker.isAllowed(session.subject)) {
      store.revoke(
          RevokeSessionCommand(
              session.id,
              null,
              now,
              RevocationReason.ACCOUNT_DISABLED,
          ))
      throw SessionError.SubjectUnavailable()
    }

    val successor = codec.generate()
    val result =
        store.compareAndRotate(
            RotateSessionCommand(
                sessionId = session.id,
                expectedVersion = session.version,
                expectedCurrentDigest = session.currentDigest,
                replacementDigest = hasher.digest(successor.verifier),
                previousValidUntil = now.plus(policy.previousVersionGrace),
                usedAt = now,
            ))
    val rotated =
        when (result) {
          is RotateSessionResult.Rotated -> result.session
          RotateSessionResult.Conflict -> throw SessionError.Conflict()
          RotateSessionResult.Missing -> throw SessionError.InvalidCredential()
        }
    publish(now, SessionEventAction.ROTATED, rotated.subject, rotated.id, rotated.familyId)
    return IssuedSession(
        rotated,
        SessionCredential(rotated.id, decoded.selector, successor.verifier),
    )
  }

  /** Revokes the authenticated session, returning whether this call changed stored state. */
  public fun revoke(authenticated: AuthenticatedSession): Boolean {
    val now = clock.instant()
    return when (store.revoke(
        RevokeSessionCommand(
            authenticated.sessionId,
            authenticated.version,
            now,
            RevocationReason.SIGN_OUT,
        ))) {
      is RevokeSessionResult.Revoked -> {
        publish(
            now,
            SessionEventAction.REVOKED,
            authenticated.subject,
            authenticated.sessionId,
            authenticated.familyId,
            RevocationReason.SIGN_OUT,
        )
        true
      }
      RevokeSessionResult.AlreadyRevoked,
      RevokeSessionResult.Missing -> false
      RevokeSessionResult.Conflict ->
          when (store.revoke(
              RevokeSessionCommand(
                  authenticated.sessionId,
                  null,
                  now,
                  RevocationReason.SIGN_OUT,
              ))) {
            is RevokeSessionResult.Revoked -> true
            else -> false
          }
    }
  }

  /** Revokes [sessionId] only when it belongs to [subject]. */
  public fun revoke(
      subject: SubjectRef,
      sessionId: SessionId,
      reason: RevocationReason = RevocationReason.ADMINISTRATIVE,
  ): Boolean {
    val session = store.findById(sessionId) ?: return false
    if (session.subject != subject) return false
    val now = clock.instant()
    return when (store.revoke(RevokeSessionCommand(sessionId, null, now, reason))) {
      is RevokeSessionResult.Revoked -> {
        publish(
            now,
            SessionEventAction.REVOKED,
            subject,
            sessionId,
            session.familyId,
            reason,
        )
        true
      }
      else -> false
    }
  }

  /** Revokes every active session for the subject except [authenticated]. */
  public fun revokeOthers(
      authenticated: AuthenticatedSession,
      reason: RevocationReason = RevocationReason.ADMINISTRATIVE,
  ): List<SessionId> =
      list(authenticated.subject)
          .asSequence()
          .filter { it.id != authenticated.sessionId }
          .filter { revoke(authenticated.subject, it.id, reason) }
          .map { it.id }
          .toList()

  /** Revokes all active sessions owned by [subject] and returns their IDs. */
  public fun revokeAll(subject: SubjectRef, reason: RevocationReason): List<SessionId> {
    val now = clock.instant()
    val revoked = store.revokeAll(subject, now, reason)
    if (revoked.isNotEmpty()) {
      publish(now, SessionEventAction.REVOKED_ALL, subject, null, null, reason)
    }
    return revoked
  }

  /** Lists active sessions for [subject] at the manager clock's current instant. */
  public fun list(subject: SubjectRef): List<StoredSession> =
      store.listActive(subject, clock.instant())

  /** Deletes one bounded page of expired or revoked sessions. */
  public fun cleanupPage(limit: Int): Int {
    require(limit in 1..10_000) { "cleanup page size must be between 1 and 10000" }
    return store.deleteExpiredPage(clock.instant(), limit)
  }

  private fun decode(encodedCredential: String): DecodedCredential =
      try {
        codec.decode(encodedCredential)
      } catch (_: IllegalArgumentException) {
        throw SessionError.InvalidCredential()
      }

  private fun StoredSession.authenticated(usedPreviousVersion: Boolean): AuthenticatedSession =
      AuthenticatedSession(id, subject, client, version, familyId, usedPreviousVersion)

  private fun publish(
      now: Instant,
      action: SessionEventAction,
      subject: SubjectRef,
      sessionId: SessionId?,
      familyId: String?,
      reason: RevocationReason? = null,
  ) {
    if (events === NoOpSessionEventPublisher) return
    events.publish(
        SessionEvent(
            identifiers.next(),
            now,
            action,
            subject,
            sessionId,
            familyId,
            reason,
        ))
  }
}
