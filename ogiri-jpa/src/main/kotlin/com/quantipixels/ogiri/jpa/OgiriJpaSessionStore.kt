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

import com.quantipixels.ogiri.session.ClientContext
import com.quantipixels.ogiri.session.CreateSessionCommand
import com.quantipixels.ogiri.session.CreateSessionResult
import com.quantipixels.ogiri.session.Realm
import com.quantipixels.ogiri.session.RevocationReason
import com.quantipixels.ogiri.session.RevokeSessionCommand
import com.quantipixels.ogiri.session.RevokeSessionResult
import com.quantipixels.ogiri.session.RotateSessionCommand
import com.quantipixels.ogiri.session.RotateSessionResult
import com.quantipixels.ogiri.session.SessionId
import com.quantipixels.ogiri.session.SessionStore
import com.quantipixels.ogiri.session.StoredSession
import com.quantipixels.ogiri.session.SubjectId
import com.quantipixels.ogiri.session.SubjectRef
import com.quantipixels.ogiri.session.TenantId
import com.quantipixels.ogiri.session.TokenDigest
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import jakarta.persistence.PersistenceContext
import java.time.Instant
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

/**
 * JPA implementation of [SessionStore].
 *
 * Subject-scoped pessimistic locking serializes admission-limit decisions, while credential
 * rotation and revocation use atomic version-checked updates.
 */
@Transactional(propagation = Propagation.REQUIRES_NEW)
public open class OgiriJpaSessionStore(
    @PersistenceContext private val entityManager: EntityManager,
    transactionManager: PlatformTransactionManager,
) : SessionStore {
  private val transactions =
      TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
      }
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  override fun create(command: CreateSessionCommand): CreateSessionResult =
      withSubjectLock(command.session.subject) {
        if (findEntityBySelector(command.session.selector) != null) {
          return@withSubjectLock CreateSessionResult.SelectorConflict
        }
        val active =
            activeEntities(command.session.subject, command.session.createdAt, locked = true)
        val evicted = mutableListOf<SessionId>()
        if (active.size >= command.maximumActiveSessions) {
          if (!command.evictOldestWhenFull) return@withSubjectLock CreateSessionResult.LimitReached
          active.take(active.size - command.maximumActiveSessions + 1).forEach {
            it.revokedAt = command.session.createdAt
            it.revocationReason = RevocationReason.SESSION_LIMIT
            evicted += SessionId(it.sessionId)
          }
        }
        val entity = command.session.toEntity()
        entityManager.persist(entity)
        entityManager.flush()
        CreateSessionResult.Created(entity.toDomain(), evicted)
      }

  @Transactional(readOnly = true)
  override fun findBySelector(selector: String): StoredSession? =
      findEntityBySelector(selector)?.toDomain()

  @Transactional(readOnly = true)
  override fun findById(sessionId: SessionId): StoredSession? =
      entityManager.find(OgiriSessionEntity::class.java, sessionId.value)?.toDomain()

  override fun compareAndRotate(command: RotateSessionCommand): RotateSessionResult {
    val updated =
        entityManager
            .createQuery(
                """
                update OgiriSessionEntity s
                   set s.previousKeyId = s.currentKeyId,
                       s.previousDigest = s.currentDigest,
                       s.previousValidUntil = :previousValidUntil,
                       s.currentKeyId = :replacementKeyId,
                       s.currentDigest = :replacementDigest,
                       s.lastUsedAt = case when s.lastUsedAt < :usedAt then :usedAt else s.lastUsedAt end,
                       s.recordVersion = s.recordVersion + 1
                 where s.sessionId = :sessionId
                   and s.recordVersion = :expectedVersion
                   and s.currentKeyId = :expectedKeyId
                   and s.currentDigest = :expectedDigest
                   and s.revokedAt is null
                   and s.expiresAt > :usedAt
                """
                    .trimIndent())
            .setParameter("previousValidUntil", command.previousValidUntil)
            .setParameter("replacementKeyId", command.replacementDigest.keyId)
            .setParameter("replacementDigest", command.replacementDigest.value)
            .setParameter("usedAt", command.usedAt)
            .setParameter("sessionId", command.sessionId.value)
            .setParameter("expectedVersion", command.expectedVersion)
            .setParameter("expectedKeyId", command.expectedCurrentDigest.keyId)
            .setParameter("expectedDigest", command.expectedCurrentDigest.value)
            .executeUpdate()
    if (updated == 0) {
      return if (entityManager.find(OgiriSessionEntity::class.java, command.sessionId.value) ==
          null) {
        RotateSessionResult.Missing
      } else {
        RotateSessionResult.Conflict
      }
    }
    return RotateSessionResult.Rotated(refreshSession(command.sessionId))
  }

  override fun recordUse(sessionId: SessionId, expectedVersion: Long, usedAt: Instant): Boolean =
      entityManager
          .createQuery(
              """
              update OgiriSessionEntity s
                 set s.lastUsedAt =
                     case when s.lastUsedAt < :usedAt then :usedAt else s.lastUsedAt end
               where s.sessionId = :sessionId
                 and s.recordVersion = :expectedVersion
                 and s.revokedAt is null
                 and s.expiresAt > :usedAt
              """
                  .trimIndent())
          .setParameter("usedAt", usedAt)
          .setParameter("sessionId", sessionId.value)
          .setParameter("expectedVersion", expectedVersion)
          .executeUpdate() == 1

  override fun revoke(command: RevokeSessionCommand): RevokeSessionResult {
    val existing =
        entityManager.find(OgiriSessionEntity::class.java, command.sessionId.value)
            ?: return RevokeSessionResult.Missing
    if (existing.revokedAt != null) return RevokeSessionResult.AlreadyRevoked
    if (command.expectedVersion != null && existing.recordVersion != command.expectedVersion) {
      return RevokeSessionResult.Conflict
    }
    val updated =
        entityManager
            .createQuery(
                """
                update OgiriSessionEntity s
                   set s.revokedAt = :revokedAt,
                       s.revocationReason = :reason,
                       s.recordVersion = s.recordVersion + 1
                 where s.sessionId = :sessionId
                   and s.revokedAt is null
                   and (:expectedVersion is null or s.recordVersion = :expectedVersion)
                """
                    .trimIndent())
            .setParameter("revokedAt", command.revokedAt)
            .setParameter("reason", command.reason)
            .setParameter("sessionId", command.sessionId.value)
            .setParameter("expectedVersion", command.expectedVersion)
            .executeUpdate()
    if (updated == 0) return RevokeSessionResult.Conflict
    return RevokeSessionResult.Revoked(refreshSession(command.sessionId))
  }

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  override fun revokeAll(
      subject: SubjectRef,
      revokedAt: Instant,
      reason: RevocationReason,
  ): List<SessionId> =
      withSubjectLock(subject) {
        val active = activeEntities(subject, revokedAt, locked = true)
        active.forEach {
          it.revokedAt = revokedAt
          it.revocationReason = reason
        }
        entityManager.flush()
        active.map { SessionId(it.sessionId) }
      }

  @Transactional(readOnly = true)
  override fun listActive(subject: SubjectRef, at: Instant): List<StoredSession> =
      activeEntities(subject, at, locked = false).map(OgiriSessionEntity::toDomain)

  override fun deleteExpiredPage(before: Instant, limit: Int): Int {
    require(limit in 1..10_000) { "cleanup page size must be between 1 and 10000" }
    val ids =
        entityManager
            .createQuery(
                "select s.sessionId from OgiriSessionEntity s where s.expiresAt <= :before or (s.revokedAt is not null and s.revokedAt <= :before) order by s.sessionId",
                String::class.java)
            .setParameter("before", before)
            .setMaxResults(limit)
            .resultList
    if (ids.isEmpty()) return 0
    return entityManager
        .createQuery(
            "delete from OgiriSessionEntity s where s.sessionId in :ids and (s.expiresAt <= :before or (s.revokedAt is not null and s.revokedAt <= :before))")
        .setParameter("ids", ids)
        .setParameter("before", before)
        .executeUpdate()
  }

  private fun <T : Any> withSubjectLock(subject: SubjectRef, operation: () -> T): T {
    val key = subject.key()
    ensureSubjectLock(key)
    return requireNotNull(
        transactions.execute {
          val lock =
              entityManager.find(OgiriSubjectLockEntity::class.java, key)
                  ?: throw IllegalStateException("subject lock was not committed")
          entityManager.lock(lock, LockModeType.PESSIMISTIC_WRITE)
          operation()
        })
  }

  private fun ensureSubjectLock(key: String) {
    var insertionFailure: RuntimeException? = null
    try {
      transactions.executeWithoutResult {
        if (entityManager.find(OgiriSubjectLockEntity::class.java, key) == null) {
          entityManager.persist(OgiriSubjectLockEntity(key))
          entityManager.flush()
        }
      }
    } catch (failure: RuntimeException) {
      insertionFailure = failure
    }
    if (insertionFailure == null) return

    val concurrentlyInserted =
        transactions.execute {
          entityManager.find(OgiriSubjectLockEntity::class.java, key) != null
        } == true
    if (!concurrentlyInserted) throw insertionFailure
  }

  private fun findEntityBySelector(selector: String): OgiriSessionEntity? =
      entityManager
          .createQuery(
              "select s from OgiriSessionEntity s where s.selector = :selector",
              OgiriSessionEntity::class.java)
          .setParameter("selector", selector)
          .resultList
          .firstOrNull()

  private fun refreshSession(sessionId: SessionId): StoredSession {
    val entity =
        entityManager.find(OgiriSessionEntity::class.java, sessionId.value)
            ?: throw IllegalStateException("updated session is missing")
    entityManager.refresh(entity)
    return entity.toDomain()
  }

  private fun activeEntities(
      subject: SubjectRef,
      at: Instant,
      locked: Boolean,
  ): List<OgiriSessionEntity> {
    val query =
        entityManager
            .createQuery(
                """
                select s from OgiriSessionEntity s
                 where s.realm = :realm
                   and ((:tenant is null and s.tenantId is null) or s.tenantId = :tenant)
                   and s.subjectId = :subject
                   and s.revokedAt is null
                   and s.expiresAt > :at
                 order by ${if (locked) "s.createdAt, s.sessionId" else "s.lastUsedAt desc, s.sessionId"}
                """
                    .trimIndent(),
                OgiriSessionEntity::class.java)
            .setParameter("realm", subject.realm.value)
            .setParameter("tenant", subject.tenantId?.value)
            .setParameter("subject", subject.subjectId.value)
            .setParameter("at", at)
    if (locked) query.lockMode = LockModeType.PESSIMISTIC_WRITE
    return query.resultList
  }

  private fun SubjectRef.key(): String =
      listOf(realm.value, tenantId?.value.orEmpty(), subjectId.value).joinToString("") {
        "${it.length}:$it"
      }
}

private fun StoredSession.toEntity(): OgiriSessionEntity =
    OgiriSessionEntity(
        id.value,
        selector,
        subject.realm.value,
        subject.tenantId?.value,
        subject.subjectId.value,
        client.clientId,
        client.label,
        client.userAgent,
        client.ipAddress,
        currentDigest.keyId,
        currentDigest.value,
        previousDigest?.keyId,
        previousDigest?.value,
        previousValidUntil,
        version,
        familyId,
        createdAt,
        lastUsedAt,
        expiresAt,
        revokedAt,
        revocationReason,
    )

private fun OgiriSessionEntity.toDomain(): StoredSession =
    StoredSession(
        SessionId(sessionId),
        selector,
        SubjectRef(Realm(realm), SubjectId(subjectId), tenantId?.let(::TenantId)),
        ClientContext(clientId, clientLabel, userAgent, ipAddress),
        TokenDigest(currentKeyId, currentDigest),
        previousDigest?.let { TokenDigest(requireNotNull(previousKeyId), it) },
        previousValidUntil,
        recordVersion,
        familyId,
        createdAt,
        lastUsedAt,
        expiresAt,
        revokedAt,
        revocationReason,
    )
