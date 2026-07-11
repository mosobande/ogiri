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

import java.time.Duration
import java.time.Instant

/** Result of atomically consuming one rate-limit permit. */
public data class OgiriRateLimitDecision(
    val allowed: Boolean,
    val remaining: Long,
    val retryAfter: Duration,
)

/** Application or infrastructure boundary for fixed-window request limiting. */
public fun interface OgiriRateLimiter {
  /** Atomically consumes one permit for a namespaced, non-secret key. */
  public fun consume(
      key: String,
      permits: Long,
      window: Duration,
      now: Instant,
  ): OgiriRateLimitDecision
}

/** Signals an exhausted rate limit and carries the duration clients should wait before retrying. */
public class OgiriRateLimitExceeded(public val retryAfter: Duration) :
    RuntimeException("rate_limit_exceeded")
