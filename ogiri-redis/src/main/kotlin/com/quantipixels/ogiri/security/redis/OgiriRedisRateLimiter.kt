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

import com.quantipixels.ogiri.security.session.OgiriRateLimitDecision
import com.quantipixels.ogiri.security.session.OgiriRateLimiter
import com.quantipixels.ogiri.security.session.OgiriSessionProperties
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript

public class OgiriRedisRateLimiter(
    private val redis: StringRedisTemplate,
    properties: OgiriSessionProperties,
) : OgiriRateLimiter {
  private val prefix = "${properties.rateLimit.keyPrefix}${properties.realm}:"

  override fun consume(
      key: String,
      permits: Long,
      window: Duration,
      now: Instant,
  ): OgiriRateLimitDecision {
    require(permits > 0) { "rate-limit permits must be positive" }
    require(!window.isNegative && !window.isZero) { "rate-limit window must be positive" }
    val redisKey = prefix + sha256(key)
    val result =
        redis.execute(SCRIPT, listOf(redisKey), window.toMillis().toString())
            ?: throw IllegalStateException("Redis rate-limit script returned no result")
    val consumed = (result[0] as Number).toLong()
    val ttlMillis = (result[1] as Number).toLong().coerceAtLeast(1)
    return OgiriRateLimitDecision(
        allowed = consumed <= permits,
        remaining = (permits - consumed).coerceAtLeast(0),
        retryAfter = Duration.ofMillis(ttlMillis),
    )
  }

  private fun sha256(value: String): String =
      HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(value.toByteArray(StandardCharsets.UTF_8)))

  private companion object {
    private val SCRIPT =
        DefaultRedisScript(
            """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            local ttl = redis.call('PTTL', KEYS[1])
            return {current, ttl}
            """
                .trimIndent(),
            List::class.java,
        )
  }
}
