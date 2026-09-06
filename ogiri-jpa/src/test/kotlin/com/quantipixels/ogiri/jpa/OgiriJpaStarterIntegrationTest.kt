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
import com.quantipixels.ogiri.session.ClientContext
import com.quantipixels.ogiri.session.Realm
import com.quantipixels.ogiri.session.RevocationReason
import com.quantipixels.ogiri.session.RevokeSessionCommand
import com.quantipixels.ogiri.session.SessionManager
import com.quantipixels.ogiri.session.SessionStore
import com.quantipixels.ogiri.session.SubjectId
import com.quantipixels.ogiri.session.SubjectRef
import com.quantipixels.ogiri.session.TenantId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityManager
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.hibernate.resource.jdbc.spi.StatementInspector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@SpringBootTest(
    classes = [OgiriJpaStarterIntegrationTest.TestApplication::class],
    properties =
        [
            "spring.datasource.url=jdbc:h2:mem:ogiri-v4;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
            "ogiri.session.enabled=true",
            "ogiri.session.token-hash.current-key-id=test",
            "ogiri.session.token-hash.keys.test=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        ],
)
class OgiriJpaStarterIntegrationTest {
  @Autowired private lateinit var sessions: SessionManager
  @Autowired private lateinit var store: SessionStore
  @Autowired private lateinit var jobLease: OgiriJobLease
  @Autowired private lateinit var entityManager: EntityManager
  @Autowired private lateinit var transactionManager: PlatformTransactionManager
  @Autowired private lateinit var leaseRaceInspector: LeaseRaceStatementInspector

  @Test
  fun `blank consumer can issue authenticate and revoke through default JPA store`() {
    val subject = SubjectRef(Realm("users"), SubjectId("user-42"))
    val issued = sessions.issue(subject, ClientContext("browser"))
    val encoded = issued.credential.encoded(com.quantipixels.ogiri.session.OpaqueTokenCodec())

    assertEquals("user-42", sessions.authenticate(encoded).subject.subjectId.value)
    sessions.revokeAll(subject, com.quantipixels.ogiri.session.RevocationReason.SIGN_OUT_ALL)
    assertThrows(com.quantipixels.ogiri.session.SessionError.Revoked::class.java) {
      sessions.authenticate(encoded)
    }
  }

  @Test
  fun `record use accepts authoritative versions without moving activity backward`() {
    val issued =
        sessions.issue(
            SubjectRef(Realm("users"), SubjectId("user-42"), TenantId("record-use")),
            ClientContext("record-use-browser"),
        )
    val initial = store.findById(issued.session.id)!!.lastUsedAt

    assertTrue(store.recordUse(issued.session.id, issued.session.version, initial))
    assertTrue(
        store.recordUse(
            issued.session.id,
            issued.session.version,
            initial.minus(Duration.ofSeconds(1)),
        ))
    assertEquals(initial, store.findById(issued.session.id)?.lastUsedAt)
    assertFalse(store.recordUse(issued.session.id, issued.session.version + 1, initial))

    store.revoke(
        RevokeSessionCommand(
            issued.session.id,
            issued.session.version,
            initial.plusSeconds(1),
            RevocationReason.ADMINISTRATIVE,
        ))
    assertFalse(store.recordUse(issued.session.id, issued.session.version, initial.plusSeconds(2)))
  }

  @Test
  fun `concurrent first lease acquisition creates one row and one owner`() {
    val name = "first-lease-race-${System.nanoTime()}"
    val now = Instant.parse("2026-01-01T00:00:00Z")
    val ready = CountDownLatch(8)
    val start = CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(8)
    val outcomes =
        try {
          val futures =
              List(8) { index ->
                executor.submit(
                    Callable {
                      ready.countDown()
                      start.await()
                      runCatching {
                        jobLease.tryAcquire(name, "owner-$index", now, now.plusSeconds(60))
                      }
                    })
              }
          assertTrue(ready.await(5, TimeUnit.SECONDS))
          start.countDown()
          futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
          start.countDown()
          executor.shutdownNow()
          executor.awaitTermination(10, TimeUnit.SECONDS)
        }

    assertTrue(outcomes.all { it.isSuccess })
    assertEquals(1, outcomes.count { it.getOrThrow() })
    val winner = outcomes.indexOfFirst { it.getOrThrow() }.let { "owner-$it" }
    assertTrue(jobLease.tryAcquire(name, winner, now.plusSeconds(1), now.plusSeconds(120)))
    assertFalse(jobLease.tryAcquire(name, "successor", now.plusSeconds(2), now.plusSeconds(120)))
    assertTrue(jobLease.tryAcquire(name, "successor", now.plusSeconds(121), now.plusSeconds(180)))
    jobLease.release(name, winner)
    assertFalse(jobLease.tryAcquire(name, "third", now.plusSeconds(122), now.plusSeconds(180)))
  }

  @Test
  fun `expired lease owner cannot release a successor lease`() {
    val name = "lease-release-race-${System.nanoTime()}"
    val now = Instant.parse("2026-01-01T00:00:00Z")
    assertTrue(jobLease.tryAcquire(name, "expired-owner", now, now.plusSeconds(1)))

    val executor = Executors.newSingleThreadExecutor()
    val control = leaseRaceInspector.start("lease-release-thread")
    val release =
        CompletableFuture.runAsync(
            {
              Thread.currentThread().name = "lease-release-thread"
              jobLease.release(name, "expired-owner")
            },
            executor,
        )
    try {
      CompletableFuture.anyOf(control.unsafeSecondSelect, release).get(5, TimeUnit.SECONDS)
      assertTrue(jobLease.tryAcquire(name, "successor", now.plusSeconds(2), now.plusSeconds(60)))
      control.successorCommitted.complete(Unit)
      release.get(5, TimeUnit.SECONDS)

      assertFalse(jobLease.tryAcquire(name, "third", now.plusSeconds(3), now.plusSeconds(60)))
    } finally {
      control.successorCommitted.complete(Unit)
      leaseRaceInspector.stop()
      executor.shutdownNow()
      executor.awaitTermination(5, TimeUnit.SECONDS)
    }
  }

  @Test
  fun `concurrent first sign-ins share one committed subject lock`() {
    val subject =
        SubjectRef(
            Realm("users"),
            SubjectId("user-42"),
            TenantId("first-sign-in-race"),
        )
    val ready = CountDownLatch(8)
    val start = CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(8)
    val outcomes =
        try {
          val futures =
              List(8) { index ->
                executor.submit(
                    Callable {
                      ready.countDown()
                      start.await()
                      runCatching { sessions.issue(subject, ClientContext("first-sign-in-$index")) }
                    })
              }
          assertTrue(ready.await(5, TimeUnit.SECONDS))
          start.countDown()
          futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
          start.countDown()
          executor.shutdownNow()
          executor.awaitTermination(10, TimeUnit.SECONDS)
        }

    assertTrue(outcomes.all { it.isSuccess })
    assertEquals(8, sessions.list(subject).size)
  }

  @Test
  fun `concurrent JPA rotation commits exactly one successor`() {
    val issued =
        sessions.issue(
            SubjectRef(Realm("users"), SubjectId("user-42")),
            ClientContext("parallel-browser"),
        )
    val encoded = issued.credential.encoded(com.quantipixels.ogiri.session.OpaqueTokenCodec())
    val executor = Executors.newFixedThreadPool(8)
    val outcomes =
        try {
          executor.invokeAll(
              List(20) {
                Callable {
                  runCatching { sessions.rotate(encoded) }
                      .fold({ "rotated" }, { it::class.simpleName.orEmpty() })
                }
              })
        } finally {
          executor.shutdown()
        }

    assertEquals(1, outcomes.count { it.get() == "rotated" })
    assertEquals(19, outcomes.count { it.get() == "Conflict" })
  }

  @Test
  fun `rotation preserves consumer managed entities in an enclosing transaction`() {
    val issued =
        sessions.issue(
            SubjectRef(Realm("users"), SubjectId("user-42"), TenantId("consumer-transaction")),
            ClientContext("consumer-transaction-browser"),
        )
    val encoded = issued.credential.encoded(com.quantipixels.ogiri.session.OpaqueTokenCodec())
    val recordId = "consumer-${System.nanoTime()}"

    TransactionTemplate(transactionManager).executeWithoutResult {
      entityManager.persist(ConsumerRecord(recordId, "before-rotation"))
      entityManager.flush()
      val record = entityManager.find(ConsumerRecord::class.java, recordId)

      sessions.rotate(encoded)
      record.value = "after-rotation"
    }

    val stored =
        TransactionTemplate(transactionManager).execute {
          entityManager.find(ConsumerRecord::class.java, recordId).value
        }
    assertEquals("after-rotation", stored)
  }

  @SpringBootApplication
  class TestApplication {
    @Bean
    fun subjectStatusChecker(): com.quantipixels.ogiri.session.SubjectStatusChecker =
        com.quantipixels.ogiri.session.SubjectStatusChecker {
          it.realm.value == "users" && it.subjectId.value == "user-42"
        }

    @Bean
    fun users(): UserDetailsService =
        InMemoryUserDetailsManager(
            User.withUsername("user-42").password("{noop}password").roles("USER").build())

    @Bean
    fun leaseRaceStatementInspector(): LeaseRaceStatementInspector = LeaseRaceStatementInspector()

    @Bean
    fun hibernatePropertiesCustomizer(
        inspector: LeaseRaceStatementInspector
    ): HibernatePropertiesCustomizer = HibernatePropertiesCustomizer {
      it["hibernate.session_factory.statement_inspector"] = inspector
    }
  }
}

@Entity
@Table(name = "consumer_records")
class ConsumerRecord(
    @Id val id: String,
    @Column(name = "record_value") var value: String,
) {
  protected constructor() : this("", "")
}

class LeaseRaceStatementInspector : StatementInspector {
  private val active = AtomicReference<LeaseRaceControl?>()

  fun start(threadName: String): LeaseRaceControl =
      LeaseRaceControl(threadName).also { active.set(it) }

  fun stop() {
    active.set(null)
  }

  override fun inspect(sql: String): String {
    val control = active.get() ?: return sql
    if (Thread.currentThread().name != control.threadName ||
        !sql.contains("ogiri_job_leases", ignoreCase = true) ||
        !sql.trimStart().startsWith("select", ignoreCase = true)) {
      return sql
    }
    if (control.selects.incrementAndGet() == 2) {
      control.unsafeSecondSelect.complete(Unit)
      control.successorCommitted.get(5, TimeUnit.SECONDS)
    }
    return sql
  }
}

class LeaseRaceControl(val threadName: String) {
  val selects = AtomicInteger()
  val unsafeSecondSelect = CompletableFuture<Unit>()
  val successorCommitted = CompletableFuture<Unit>()
}
