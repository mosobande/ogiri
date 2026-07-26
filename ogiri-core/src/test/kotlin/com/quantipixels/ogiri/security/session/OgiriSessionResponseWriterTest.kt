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
import com.quantipixels.ogiri.session.SubjectId
import com.quantipixels.ogiri.session.SubjectRef
import com.quantipixels.ogiri.session.SubjectStatusChecker
import com.quantipixels.ogiri.test.InMemorySessionStore
import java.time.Clock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletResponse

class OgiriSessionResponseWriterTest {
  private val issued =
      SessionManager(
              InMemorySessionStore(),
              OpaqueTokenCodec(),
              HmacSha256TokenHasher("test", mapOf("test" to ByteArray(32) { 1 })),
              SubjectStatusChecker { true },
              Clock.systemUTC(),
          )
          .issue(
              SubjectRef(Realm("users"), SubjectId("subject")),
              ClientContext("browser"),
          )

  @Test
  fun `cookie mode exposes credential only through HttpOnly cookie`() {
    val response = MockHttpServletResponse()
    OgiriSessionResponseWriter(OgiriSessionProperties(transport = OgiriTransport.COOKIE))
        .writeCredential(response, issued)

    assertNull(response.getHeader(HttpHeaders.AUTHORIZATION))
    assertNull(response.getHeader("access-token"))
    assertTrue(response.getHeader(HttpHeaders.SET_COOKIE)!!.startsWith("__Host-ogiri-session="))
    assertTrue(response.getHeader(HttpHeaders.SET_COOKIE)!!.contains("HttpOnly"))
    assertEquals("no-store", response.getHeader(HttpHeaders.CACHE_CONTROL))
  }

  @Test
  fun `bearer mode emits no cookie or compatibility headers`() {
    val response = MockHttpServletResponse()
    OgiriSessionResponseWriter(OgiriSessionProperties(transport = OgiriTransport.BEARER))
        .writeCredential(response, issued)

    assertTrue(response.getHeader(HttpHeaders.AUTHORIZATION)!!.startsWith("Bearer "))
    assertNull(response.getHeader(HttpHeaders.SET_COOKIE))
    assertNull(response.getHeader("access-token"))
  }
}
