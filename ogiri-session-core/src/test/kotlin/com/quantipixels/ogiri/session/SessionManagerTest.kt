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

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SessionManagerTest {
  private val clock = MutableClock(Instant.parse("2026-07-10T10:00:00Z"))
  private val store = InMemorySessionStore()
  private val allowed = AtomicBoolean(true)
  private val manager =
      SessionManager(
          store,
          OpaqueTokenCodec(),
          HmacSha256TokenHasher("2026-01", mapOf("2026-01" to ByteArray(32) { 7 })),
          SubjectStatusChecker { allowed.get() },
          clock,
          SessionPolicy(previousVersionGrace = Duration.ofSeconds(10)),
      )
  private val subject = SubjectRef(Realm("users"), SubjectId("user-42"))
  private val client = ClientContext("browser-1")

  @Test
  fun `previous credential has one immutable deadline despite repeated use`() {
    val initial = manager.issue(subject, client)
    val rotated = manager.rotate(initial.credential.encoded(OpaqueTokenCodec()))
    val fixedDeadline = rotated.session.previousValidUntil

    repeat(4) {
      clock.advance(Duration.ofSeconds(2))
      val accepted = manager.authenticate(initial.credential.encoded(OpaqueTokenCodec()))
      assertTrue(accepted.usedPreviousVersion)
      assertEquals(fixedDeadline, store.findById(initial.session.id)?.previousValidUntil)
    }

    clock.set(fixedDeadline!!)
    assertFailsWith<SessionError.ReuseDetected> {
      manager.authenticate(initial.credential.encoded(OpaqueTokenCodec()))
    }
    assertEquals(RevocationReason.TOKEN_REUSE, store.findById(initial.session.id)?.revocationReason)
  }

  @Test
  fun `three rotations never retain an unbounded historical credential`() {
    val t0 = manager.issue(subject, client)
    val t1 = manager.rotate(t0.credential.encoded(OpaqueTokenCodec()))
    val t2 = manager.rotate(t1.credential.encoded(OpaqueTokenCodec()))
    manager.rotate(t2.credential.encoded(OpaqueTokenCodec()))

    assertFailsWith<SessionError.InvalidCredential> {
      manager.authenticate(t0.credential.encoded(OpaqueTokenCodec()))
    }
    val stored = store.findById(t0.session.id)!!
    assertEquals(3, stored.version)
    assertTrue(stored.previousDigest != null)
    assertTrue(stored.previousValidUntil != null)
  }

  @Test
  fun `parallel rotation commits exactly one successor`() {
    val issued = manager.issue(subject, client)
    val credential = issued.credential.encoded(OpaqueTokenCodec())
    val executor = Executors.newFixedThreadPool(12)
    val outcomes =
        executor.invokeAll(
            List(50) {
              Callable {
                runCatching { manager.rotate(credential) }
                    .fold({ "rotated" }, { it::class.simpleName!! })
              }
            })
    executor.shutdown()

    val values = outcomes.map { it.get() }
    assertEquals(1, values.count { it == "rotated" })
    assertEquals(49, values.count { it == "Conflict" })
    assertEquals(1, store.findById(issued.session.id)?.version)
  }

  @Test
  fun `disabled subject is denied and active session is revoked`() {
    val issued = manager.issue(subject, client)
    allowed.set(false)

    assertFailsWith<SessionError.SubjectUnavailable> {
      manager.authenticate(issued.credential.encoded(OpaqueTokenCodec()))
    }
    assertEquals(
        RevocationReason.ACCOUNT_DISABLED, store.findById(issued.session.id)?.revocationReason)
  }

  @Test
  fun `revoke uses stable session identity and is idempotent`() {
    val issued = manager.issue(subject, client)
    val authenticated = manager.authenticate(issued.credential.encoded(OpaqueTokenCodec()))

    assertTrue(manager.revoke(authenticated))
    assertFalse(manager.revoke(authenticated))
    assertFailsWith<SessionError.Revoked> {
      manager.authenticate(issued.credential.encoded(OpaqueTokenCodec()))
    }
  }

  @Test
  fun `maximum session admission is atomic and counts sessions only`() {
    val limited =
        SessionManager(
            store,
            OpaqueTokenCodec(),
            HmacSha256TokenHasher("k", mapOf("k" to ByteArray(32) { 1 })),
            SubjectStatusChecker { true },
            clock,
            SessionPolicy(maximumActiveSessions = 2, evictOldestWhenFull = false),
        )
    val executor = Executors.newFixedThreadPool(10)
    val results =
        executor.invokeAll(
            List(20) {
              Callable {
                runCatching { limited.issue(subject, ClientContext("client-$it")) }.isSuccess
              }
            })
    executor.shutdown()

    assertEquals(2, results.count { it.get() })
    assertEquals(2, limited.list(subject).size)
  }

  @Test
  fun `issued plaintext is separate from immutable stored session`() {
    val issued = manager.issue(subject, client)
    val stored = store.findById(issued.session.id)!!

    assertNotEquals(issued.credential.verifier, stored.currentDigest.value)
    assertFalse(stored.toString().contains(issued.credential.verifier))
  }
}

private class MutableClock(private var current: Instant) : Clock() {
  override fun getZone(): ZoneId = ZoneOffset.UTC

  override fun withZone(zone: ZoneId): Clock = this

  override fun instant(): Instant = synchronized(this) { current }

  fun advance(duration: Duration) = synchronized(this) { current = current.plus(duration) }

  fun set(instant: Instant) = synchronized(this) { current = instant }
}

private class InMemorySessionStore : SessionStore {
  private val byId = ConcurrentHashMap<SessionId, StoredSession>()
  private val selectorToId = ConcurrentHashMap<String, SessionId>()

  @Synchronized
  override fun create(command: CreateSessionCommand): CreateSessionResult {
    if (selectorToId.containsKey(command.session.selector))
        return CreateSessionResult.SelectorConflict
    val active =
        byId.values
            .filter {
              it.subject == command.session.subject && it.isActive(command.session.createdAt)
            }
            .sortedBy { it.createdAt }
    val evicted = mutableListOf<SessionId>()
    if (active.size >= command.maximumActiveSessions) {
      if (!command.evictOldestWhenFull) return CreateSessionResult.LimitReached
      active.take(active.size - command.maximumActiveSessions + 1).forEach {
        byId[it.id] =
            it.copy(
                version = it.version + 1,
                revokedAt = command.session.createdAt,
                revocationReason = RevocationReason.SESSION_LIMIT,
            )
        selectorToId.remove(it.selector)
        evicted += it.id
      }
    }
    byId[command.session.id] = command.session.copy()
    selectorToId[command.session.selector] = command.session.id
    return CreateSessionResult.Created(command.session.copy(), evicted)
  }

  override fun findBySelector(selector: String): StoredSession? =
      selectorToId[selector]?.let(byId::get)?.copy()

  override fun findById(sessionId: SessionId): StoredSession? = byId[sessionId]?.copy()

  @Synchronized
  override fun compareAndRotate(command: RotateSessionCommand): RotateSessionResult {
    val current = byId[command.sessionId] ?: return RotateSessionResult.Missing
    if (current.version != command.expectedVersion ||
        current.currentDigest != command.expectedCurrentDigest ||
        current.revokedAt != null) {
      return RotateSessionResult.Conflict
    }
    val rotated =
        current.copy(
            currentDigest = command.replacementDigest,
            previousDigest = current.currentDigest,
            previousValidUntil = command.previousValidUntil,
            version = current.version + 1,
            lastUsedAt = command.usedAt,
        )
    byId[current.id] = rotated
    return RotateSessionResult.Rotated(rotated.copy())
  }

  @Synchronized
  override fun recordUse(sessionId: SessionId, expectedVersion: Long, usedAt: Instant): Boolean {
    val current = byId[sessionId] ?: return false
    if (current.version != expectedVersion || current.revokedAt != null) return false
    byId[sessionId] = current.copy(lastUsedAt = maxOf(current.lastUsedAt, usedAt))
    return true
  }

  @Synchronized
  override fun revoke(command: RevokeSessionCommand): RevokeSessionResult {
    val current = byId[command.sessionId] ?: return RevokeSessionResult.Missing
    if (current.revokedAt != null) return RevokeSessionResult.AlreadyRevoked
    if (command.expectedVersion != null && current.version != command.expectedVersion) {
      return RevokeSessionResult.Conflict
    }
    val revoked =
        current.copy(
            version = current.version + 1,
            revokedAt = command.revokedAt,
            revocationReason = command.reason,
        )
    byId[current.id] = revoked
    return RevokeSessionResult.Revoked(revoked.copy())
  }

  @Synchronized
  override fun revokeAll(
      subject: SubjectRef,
      revokedAt: Instant,
      reason: RevocationReason,
  ): List<SessionId> =
      byId.values
          .filter { it.subject == subject && it.revokedAt == null }
          .map {
            byId[it.id] =
                it.copy(version = it.version + 1, revokedAt = revokedAt, revocationReason = reason)
            it.id
          }

  override fun listActive(subject: SubjectRef, at: Instant): List<StoredSession> =
      byId.values.filter { it.subject == subject && it.isActive(at) }.map { it.copy() }

  @Synchronized
  override fun deleteExpiredPage(before: Instant, limit: Int): Int {
    val ids =
        byId.values
            .filter { !it.expiresAt.isAfter(before) || it.revokedAt?.isAfter(before) == false }
            .sortedBy { it.id.value }
            .take(limit)
            .map { it.id }
    ids.forEach { id -> byId.remove(id)?.let { selectorToId.remove(it.selector) } }
    return ids.size
  }
}
