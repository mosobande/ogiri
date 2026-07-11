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

import com.quantipixels.ogiri.session.OpaqueTokenCodec
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.BadCredentialsException

class OgiriBearerAuthenticationConverterTest {
  private val converter = OgiriBearerAuthenticationConverter(128)

  @Test
  fun `scheme is case insensitive and exactly one opaque credential is accepted`() {
    val request = MockHttpServletRequest()
    val generated = com.quantipixels.ogiri.session.OpaqueTokenCodec().generate()
    request.addHeader(
        HttpHeaders.AUTHORIZATION,
        "bEaReR ${generated.selector}.${generated.verifier}",
    )
    assertNotNull(converter.convert(request))
  }

  @Test
  fun `minimum configured size accepts a default credential`() {
    assertThrows(IllegalArgumentException::class.java) {
      OgiriBearerAuthenticationConverter(OpaqueTokenCodec.MIN_CREDENTIAL_CHARS - 1)
    }
    val generated = OpaqueTokenCodec().generate()
    val request =
        MockHttpServletRequest().apply {
          addHeader(
              HttpHeaders.AUTHORIZATION,
              "Bearer ${generated.selector}.${generated.verifier}",
          )
        }

    assertNotNull(
        OgiriBearerAuthenticationConverter(OpaqueTokenCodec.MIN_CREDENTIAL_CHARS).convert(request))
  }

  @Test
  fun `duplicate headers are rejected`() {
    val request = MockHttpServletRequest()
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer first.value")
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer second.value")
    assertThrows(BadCredentialsException::class.java) { converter.convert(request) }
  }

  @Test
  fun `whitespace JSON and oversized values are rejected as credentials`() {
    listOf(
            "Bearer value with-space",
            "Bearer {\"token\":true}",
            "Bearer ${"a".repeat(200)}",
        )
        .forEach { value ->
          val request = MockHttpServletRequest()
          request.addHeader(HttpHeaders.AUTHORIZATION, value)
          assertThrows(BadCredentialsException::class.java) { converter.convert(request) }
        }
  }
}
