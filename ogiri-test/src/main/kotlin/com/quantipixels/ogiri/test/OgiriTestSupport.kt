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

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.request.RequestPostProcessor

/** Thread-safe, UTC [Clock] whose instant can be advanced deterministically in tests. */
public class OgiriFakeClock
@JvmOverloads
public constructor(private var current: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock() {
  override fun getZone(): ZoneId = ZoneOffset.UTC

  override fun withZone(zone: ZoneId): Clock = this

  override fun instant(): Instant = synchronized(this) { current }

  /** Advances the clock by [duration] and returns the resulting instant. */
  public fun advance(duration: Duration): Instant =
      synchronized(this) {
        current = current.plus(duration)
        current
      }

  /** Replaces the current instant. */
  public fun set(instant: Instant): Unit = synchronized(this) { current = instant }
}

/** Java-friendly MockMvc request processors for Ogiri credential transports. */
public object OgiriMockMvc {
  @JvmStatic
  /** Adds a Bearer authorization header carrying [credential]. */
  public fun bearer(credential: String): RequestPostProcessor = RequestPostProcessor { request ->
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer $credential")
    request
  }

  @JvmStatic
  /** Adds legacy DTA-compatible access-token, client, and uid headers. */
  public fun dta(credential: String, client: String, uid: String): RequestPostProcessor =
      RequestPostProcessor { request ->
        request.addHeader("access-token", credential)
        request.addHeader("client", client)
        request.addHeader("uid", uid)
        request
      }
}
