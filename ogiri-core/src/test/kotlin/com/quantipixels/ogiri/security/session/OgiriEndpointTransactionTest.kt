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
import com.quantipixels.ogiri.session.CreateSessionCommand
import com.quantipixels.ogiri.session.CreateSessionResult
import com.quantipixels.ogiri.session.HmacSha256TokenHasher
import com.quantipixels.ogiri.session.OpaqueTokenCodec
import com.quantipixels.ogiri.session.Realm
import com.quantipixels.ogiri.session.RevocationReason
import com.quantipixels.ogiri.session.SessionError
import com.quantipixels.ogiri.session.SessionManager
import com.quantipixels.ogiri.session.SessionStore
import com.quantipixels.ogiri.session.SubjectId
import com.quantipixels.ogiri.session.SubjectRef
import com.quantipixels.ogiri.session.SubjectStatusChecker
import com.quantipixels.ogiri.test.InMemorySessionStore
import java.time.Clock
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder

class OgiriEndpointTransactionTest {
  @Test
  fun `store failure cannot leak credential or security context`() {
    val delegate = InMemorySessionStore()
    val failingStore =
        object : SessionStore by delegate {
          override fun create(command: CreateSessionCommand): CreateSessionResult =
              throw IllegalStateException("commit failed")
        }
    val codec = OpaqueTokenCodec()
    val sessions =
        SessionManager(
            failingStore,
            codec,
            HmacSha256TokenHasher("test", mapOf("test" to ByteArray(32) { 1 })),
            SubjectStatusChecker { true },
            Clock.systemUTC(),
        )
    val authenticationManager = AuthenticationManager {
      UsernamePasswordAuthenticationToken.authenticated(it.name, null, emptyList())
    }
    val properties = OgiriSessionProperties()
    val controller =
        OgiriSessionEndpointController(
            authenticationManager,
            sessions,
            OgiriSubjectResolver { SubjectRef(Realm("users"), SubjectId(it.name)) },
            OgiriClientContextResolver { _, clientId ->
              com.quantipixels.ogiri.session.ClientContext(clientId ?: "browser")
            },
            OgiriRequestCredentialResolver(properties),
            OgiriSessionResponseWriter(properties, codec),
            null,
            Clock.systemUTC(),
            properties.rateLimit,
        )
    val response = MockHttpServletResponse()

    assertThrows(IllegalStateException::class.java) {
      controller.signIn(
          SignInRequest("user-42", "password", "browser"),
          MockHttpServletRequest(),
          response,
      )
    }
    assertNull(response.getHeader(HttpHeaders.AUTHORIZATION))
    assertNull(response.getHeader(HttpHeaders.SET_COOKIE))
    assertNull(SecurityContextHolder.getContext().authentication)
  }

  @Test
  fun `current endpoint reports a revoked session instead of throwing a lookup error`() {
    val store = InMemorySessionStore()
    val codec = OpaqueTokenCodec()
    val sessions =
        SessionManager(
            store,
            codec,
            HmacSha256TokenHasher("test", mapOf("test" to ByteArray(32) { 1 })),
            SubjectStatusChecker { true },
            Clock.systemUTC(),
        )
    val subject = SubjectRef(Realm("users"), SubjectId("user-42"))
    val issued = sessions.issue(subject, ClientContext("browser"))
    sessions.revokeAll(subject, RevocationReason.SIGN_OUT_ALL)
    val properties = OgiriSessionProperties()
    val controller =
        OgiriSessionEndpointController(
            AuthenticationManager { it },
            sessions,
            OgiriSubjectResolver { subject },
            OgiriClientContextResolver { _, _ -> ClientContext("browser") },
            OgiriRequestCredentialResolver(properties),
            OgiriSessionResponseWriter(properties, codec),
            null,
            Clock.systemUTC(),
            properties.rateLimit,
        )
    val authentication =
        OgiriSessionAuthenticationToken.authenticated(
            OgiriSessionPrincipal(
                "user-42",
                "users",
                null,
                issued.session.id.value,
                "browser",
                issued.session.version,
                issued.session.familyId,
            ),
            emptyList(),
        )

    assertThrows(SessionError.Revoked::class.java) { controller.current(authentication) }
  }
}
