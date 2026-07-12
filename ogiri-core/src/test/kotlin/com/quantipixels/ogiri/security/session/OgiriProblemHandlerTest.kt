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

import com.quantipixels.ogiri.session.SessionError
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus

class OgiriProblemHandlerTest {
  private val handler = OgiriProblemHandler()

  @Test
  fun `session errors retain stable protocol statuses`() {
    val unauthorized = handler.sessionError(SessionError.Revoked())
    val forbidden = handler.sessionError(SessionError.SubjectUnavailable())
    val conflict = handler.sessionError(SessionError.Conflict())

    assertEquals(HttpStatus.UNAUTHORIZED, unauthorized.statusCode)
    assertNotNull(unauthorized.headers.getFirst(HttpHeaders.WWW_AUTHENTICATE))
    assertEquals(HttpStatus.FORBIDDEN, forbidden.statusCode)
    assertEquals(HttpStatus.CONFLICT, conflict.statusCode)
  }

  @Test
  fun `rate limits include a retry after header`() {
    val response = handler.rateLimited(OgiriRateLimitExceeded(Duration.ofSeconds(7)))

    assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.statusCode)
    assertEquals("7", response.headers.getFirst(HttpHeaders.RETRY_AFTER))
  }
}
