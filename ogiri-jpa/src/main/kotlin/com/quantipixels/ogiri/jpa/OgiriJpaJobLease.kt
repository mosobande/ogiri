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
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Transactional JPA implementation of a cluster-safe [OgiriJobLease].
 *
 * Each acquire or release uses an independent transaction and a pessimistic row lock. An expired
 * lease may be taken by another owner; an active lease may be renewed by its current owner.
 */
public open class OgiriJpaJobLease(private val entityManager: EntityManager) : OgiriJobLease {
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  override fun tryAcquire(name: String, owner: String, now: Instant, until: Instant): Boolean {
    var lease = entityManager.find(OgiriJobLeaseEntity::class.java, name)
    if (lease == null) {
      entityManager.persist(OgiriJobLeaseEntity(name, null, null))
      entityManager.flush()
      lease = entityManager.find(OgiriJobLeaseEntity::class.java, name)
    }
    entityManager.lock(lease, LockModeType.PESSIMISTIC_WRITE)
    if (lease.ownerId != null && lease.ownerId != owner && lease.leaseUntil?.isAfter(now) == true) {
      return false
    }
    lease.ownerId = owner
    lease.leaseUntil = until
    entityManager.flush()
    return true
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
