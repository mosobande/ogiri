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

import com.quantipixels.ogiri.session.ClientContext
import com.quantipixels.ogiri.session.HmacSha256TokenHasher
import com.quantipixels.ogiri.session.OpaqueTokenCodec
import com.quantipixels.ogiri.session.Realm
import com.quantipixels.ogiri.session.SessionManager
import com.quantipixels.ogiri.session.SessionStore
import com.quantipixels.ogiri.session.SubjectId
import com.quantipixels.ogiri.session.SubjectRef
import com.quantipixels.ogiri.session.SubjectStatusChecker
import com.quantipixels.ogiri.test.InMemorySessionStore
import com.quantipixels.ogiri.test.OgiriFakeClock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler

class OgiriSessionCleanupTest {
  @Test
  fun `cleanup owns a cluster lease and commits bounded pages`() {
    val clock = OgiriFakeClock(Instant.parse("2026-01-01T00:00:00Z"))
    val store = InMemorySessionStore()
    val sessions =
        SessionManager(
            store,
            OpaqueTokenCodec(),
            HmacSha256TokenHasher("test", mapOf("test" to ByteArray(32) { 1 })),
            SubjectStatusChecker { true },
            clock,
        )
    repeat(5) {
      sessions.issue(
          SubjectRef(Realm("users"), SubjectId("subject-$it")),
          ClientContext("client"),
      )
    }
    clock.advance(Duration.ofDays(15))
    val lease = InMemoryJobLease()
    val cleanup =
        OgiriSessionCleanupScheduler(
            sessions,
            lease,
            ConcurrentTaskScheduler(),
            clock,
            OgiriSessionProperties.Cleanup(batchSize = 2),
        )

    cleanup.runOnce()

    assertEquals(5, cleanup.status().lastDeletedRows)
    assertEquals(3, lease.acquireAttempts)
    assertTrue(store.snapshot().isEmpty())
    assertFalse(lease.isHeld("session-cleanup"))
  }

  @Test
  fun `cleanup stops before another page when lease renewal fails`() {
    val clock = OgiriFakeClock(Instant.parse("2026-01-01T00:00:00Z"))
    val store = InMemorySessionStore()
    val sessions = sessionManager(store, clock)
    repeat(5) {
      sessions.issue(SubjectRef(Realm("users"), SubjectId("renewal-$it")), ClientContext("client"))
    }
    clock.advance(Duration.ofDays(15))
    val lease = InMemoryJobLease(maxSuccessfulAcquisitions = 1)
    val cleanup =
        OgiriSessionCleanupScheduler(
            sessions,
            lease,
            ConcurrentTaskScheduler(),
            clock,
            OgiriSessionProperties.Cleanup(batchSize = 2),
        )

    cleanup.runOnce()

    assertEquals(2, cleanup.status().lastDeletedRows)
    assertEquals(3, store.snapshot().size)
    assertEquals(2, lease.acquireAttempts)
  }

  @Test
  fun `cleanup respects its fixed run duration before another page`() {
    val clock = OgiriFakeClock(Instant.parse("2026-01-01T00:00:00Z"))
    val delegate = InMemorySessionStore()
    val timedStore =
        object : SessionStore by delegate {
          override fun deleteExpiredPage(before: Instant, limit: Int): Int {
            val deleted = delegate.deleteExpiredPage(before, limit)
            clock.advance(Duration.ofMinutes(5))
            return deleted
          }
        }
    val sessions = sessionManager(timedStore, clock)
    repeat(5) {
      sessions.issue(SubjectRef(Realm("users"), SubjectId("duration-$it")), ClientContext("client"))
    }
    clock.advance(Duration.ofDays(15))
    val lease = InMemoryJobLease()
    val cleanup =
        OgiriSessionCleanupScheduler(
            sessions,
            lease,
            ConcurrentTaskScheduler(),
            clock,
            OgiriSessionProperties.Cleanup(
                batchSize = 2,
                maxRunDuration = Duration.ofMinutes(5),
            ),
        )

    cleanup.runOnce()

    assertEquals(2, cleanup.status().lastDeletedRows)
    assertEquals(3, delegate.snapshot().size)
    assertEquals(1, lease.acquireAttempts)
  }

  private fun sessionManager(store: SessionStore, clock: OgiriFakeClock): SessionManager =
      SessionManager(
          store,
          OpaqueTokenCodec(),
          HmacSha256TokenHasher("test", mapOf("test" to ByteArray(32) { 1 })),
          SubjectStatusChecker { true },
          clock,
      )
}

private class InMemoryJobLease(private val maxSuccessfulAcquisitions: Int = Int.MAX_VALUE) :
    OgiriJobLease {
  private val owners = ConcurrentHashMap<String, String>()
  var acquireAttempts: Int = 0
    private set

  @Synchronized
  override fun tryAcquire(name: String, owner: String, now: Instant, until: Instant): Boolean {
    acquireAttempts += 1
    if (acquireAttempts > maxSuccessfulAcquisitions) return false
    val existing = owners[name]
    if (existing != null && existing != owner) return false
    owners[name] = owner
    return true
  }

  override fun release(name: String, owner: String) {
    owners.remove(name, owner)
  }

  fun isHeld(name: String): Boolean = owners.containsKey(name)
}
