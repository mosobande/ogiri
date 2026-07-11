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
package com.quantipixels.ogiri.security.session

import com.quantipixels.ogiri.session.SessionManager
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.springframework.context.SmartLifecycle
import org.springframework.scheduling.TaskScheduler

/**
 * Cluster-wide lease used to ensure only one node performs a named maintenance job.
 *
 * Implementations must acquire atomically and must not let one owner release another owner's lease.
 */
public interface OgiriJobLease {
  /** Attempts to own [name] as [owner] until [until]. */
  public fun tryAcquire(name: String, owner: String, now: Instant, until: Instant): Boolean

  /** Releases [name] only if it is still held by [owner]. */
  public fun release(name: String, owner: String): Unit
}

/** Latest observable outcome of the session cleanup scheduler. */
public data class OgiriCleanupStatus(
    val lastStartedAt: Instant?,
    val lastCompletedAt: Instant?,
    val lastDeletedRows: Int,
    val lastFailure: String?,
)

/**
 * Lifecycle-managed cleanup loop that deletes bounded pages under a cluster-safe lease.
 *
 * A run continues until a short page is returned. Failures are recorded in [status] and rethrown so
 * the configured scheduler can apply its normal error handling.
 */
public class OgiriSessionCleanupScheduler(
    private val sessions: SessionManager,
    private val lease: OgiriJobLease,
    private val scheduler: TaskScheduler,
    private val clock: Clock,
    private val properties: OgiriSessionProperties.Cleanup,
) : SmartLifecycle {
  private val owner = UUID.randomUUID().toString()
  private val running = AtomicBoolean(false)
  private val status = AtomicReference(OgiriCleanupStatus(null, null, 0, null))
  private var future: ScheduledFuture<*>? = null

  override fun start() {
    if (running.compareAndSet(false, true)) {
      future = scheduler.scheduleWithFixedDelay(::runOnce, properties.interval)
    }
  }

  override fun stop() {
    future?.cancel(false)
    running.set(false)
  }

  override fun isRunning(): Boolean = running.get()

  /** Returns the latest immutable cleanup status snapshot. */
  public fun status(): OgiriCleanupStatus = status.get()

  /**
   * Attempts one leased cleanup run immediately; does nothing when another owner holds the lease.
   */
  public fun runOnce() {
    val started = clock.instant()
    if (!lease.tryAcquire(JOB_NAME, owner, started, started.plus(properties.lease))) return
    status.set(OgiriCleanupStatus(started, null, 0, null))
    var deleted = 0
    try {
      do {
        val page = sessions.cleanupPage(properties.batchSize)
        deleted += page
      } while (page == properties.batchSize)
      status.set(OgiriCleanupStatus(started, clock.instant(), deleted, null))
    } catch (error: RuntimeException) {
      status.set(
          OgiriCleanupStatus(
              started,
              clock.instant(),
              deleted,
              error::class.qualifiedName ?: "cleanup_failure",
          ))
      throw error
    } finally {
      lease.release(JOB_NAME, owner)
    }
  }

  private companion object {
    private const val JOB_NAME = "session-cleanup"
  }
}
