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
package com.quantipixels.ogiri.security.redis

import com.quantipixels.ogiri.security.session.OgiriSessionProperties
import com.redis.testcontainers.RedisContainer
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers(disabledWithoutDocker = true)
class OgiriRedisRateLimiterTest {
  companion object {
    @Container val redis: RedisContainer = RedisContainer("redis:7-alpine")
  }

  private lateinit var template: StringRedisTemplate
  private lateinit var limiter: OgiriRedisRateLimiter

  @BeforeEach
  fun setup() {
    val factory =
        LettuceConnectionFactory(
                RedisStandaloneConfiguration(redis.host, redis.getMappedPort(6379)))
            .also { it.afterPropertiesSet() }
    template = StringRedisTemplate(factory).also { it.afterPropertiesSet() }
    factory.connection.use { it.serverCommands().flushAll() }
    val properties =
        OgiriSessionProperties(
            enabled = true,
            realm = "users",
            rateLimit = OgiriSessionProperties.RateLimit(enabled = true),
        )
    limiter =
        OgiriRedisRateLimitAutoConfiguration().ogiriRedisRateLimiter(template, properties)
            as OgiriRedisRateLimiter
  }

  @Test
  fun `atomic bucket allows limit then returns retry after`() {
    val now = Instant.parse("2026-07-11T00:00:00Z")

    val first = limiter.consume("sign-in:ip:127.0.0.1", 2, Duration.ofSeconds(30), now)
    val second = limiter.consume("sign-in:ip:127.0.0.1", 2, Duration.ofSeconds(30), now)
    val denied = limiter.consume("sign-in:ip:127.0.0.1", 2, Duration.ofSeconds(30), now)

    assertTrue(first.allowed)
    assertEquals(1, first.remaining)
    assertTrue(second.allowed)
    assertFalse(denied.allowed)
    assertEquals(0, denied.remaining)
    assertTrue(!denied.retryAfter.isZero)
  }

  @Test
  fun `different normalized scopes use isolated buckets`() {
    val now = Instant.EPOCH
    assertTrue(limiter.consume("sign-in:ip:one", 1, Duration.ofMinutes(1), now).allowed)
    assertTrue(limiter.consume("sign-in:ip:two", 1, Duration.ofMinutes(1), now).allowed)
  }

  @Test
  fun `invalid bucket policy is rejected before Redis`() {
    assertThrows(IllegalArgumentException::class.java) {
      limiter.consume("key", 0, Duration.ofMinutes(1), Instant.EPOCH)
    }
    assertThrows(IllegalArgumentException::class.java) {
      limiter.consume("key", 1, Duration.ZERO, Instant.EPOCH)
    }
  }
}
