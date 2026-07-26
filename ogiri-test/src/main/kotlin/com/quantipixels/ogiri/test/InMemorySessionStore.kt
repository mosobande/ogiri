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
package com.quantipixels.ogiri.test

import com.quantipixels.ogiri.session.CreateSessionCommand
import com.quantipixels.ogiri.session.CreateSessionResult
import com.quantipixels.ogiri.session.RevocationReason
import com.quantipixels.ogiri.session.RevokeSessionCommand
import com.quantipixels.ogiri.session.RevokeSessionResult
import com.quantipixels.ogiri.session.RotateSessionCommand
import com.quantipixels.ogiri.session.RotateSessionResult
import com.quantipixels.ogiri.session.SessionId
import com.quantipixels.ogiri.session.SessionStore
import com.quantipixels.ogiri.session.StoredSession
import com.quantipixels.ogiri.session.SubjectRef
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory [SessionStore] for tests.
 *
 * It implements the same admission and optimistic-update outcomes as production stores and returns
 * copies so test code cannot mutate committed state accidentally.
 */
public class InMemorySessionStore : SessionStore {
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
            .sortedWith(compareBy(StoredSession::createdAt, { it.id.value }))
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
      byId.values
          .filter { it.subject == subject && it.isActive(at) }
          .sortedWith(compareByDescending<StoredSession> { it.lastUsedAt }.thenBy { it.id.value })
          .map { it.copy() }

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

  /** Returns detached copies of every stored session, including revoked and expired entries. */
  public fun snapshot(): List<StoredSession> = byId.values.map(StoredSession::copy)
}
