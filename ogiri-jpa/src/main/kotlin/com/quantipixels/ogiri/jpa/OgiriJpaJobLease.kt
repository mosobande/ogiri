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

import com.quantipixels.ogiri.security.session.OgiriJobLease
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import java.time.Instant
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

/**
 * Transactional JPA implementation of a cluster-safe [OgiriJobLease].
 *
 * Each acquire or release uses an independent transaction and a pessimistic row lock. An expired
 * lease may be taken by another owner; an active lease may be renewed by its current owner.
 */
public open class OgiriJpaJobLease(
    private val entityManager: EntityManager,
    transactionManager: PlatformTransactionManager,
) : OgiriJobLease {
  private val rowCreation =
      TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
      }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  override fun tryAcquire(name: String, owner: String, now: Instant, until: Instant): Boolean {
    ensureLeaseRow(name)
    val lease =
        entityManager.find(
            OgiriJobLeaseEntity::class.java,
            name,
            LockModeType.PESSIMISTIC_WRITE,
        )
            ?: throw IllegalStateException("job lease row was not committed")
    if (lease.ownerId != null && lease.ownerId != owner && lease.leaseUntil?.isAfter(now) == true) {
      return false
    }
    lease.ownerId = owner
    lease.leaseUntil = until
    entityManager.flush()
    return true
  }

  private fun ensureLeaseRow(name: String) {
    var insertionFailure: RuntimeException? = null
    try {
      rowCreation.executeWithoutResult {
        if (entityManager.find(OgiriJobLeaseEntity::class.java, name) == null) {
          entityManager.persist(OgiriJobLeaseEntity(name, null, null))
          entityManager.flush()
        }
      }
    } catch (failure: RuntimeException) {
      insertionFailure = failure
    }
    if (insertionFailure == null) return

    val concurrentlyInserted =
        rowCreation.execute { entityManager.find(OgiriJobLeaseEntity::class.java, name) != null } ==
            true
    if (!concurrentlyInserted) throw insertionFailure
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  override fun release(name: String, owner: String) {
    val lease = entityManager.find(OgiriJobLeaseEntity::class.java, name) ?: return
    entityManager.lock(lease, LockModeType.PESSIMISTIC_WRITE)
    if (lease.ownerId == owner) {
      lease.ownerId = null
      lease.leaseUntil = null
    }
  }
}
