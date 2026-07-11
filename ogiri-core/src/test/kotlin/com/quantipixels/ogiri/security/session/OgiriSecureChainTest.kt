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
import com.quantipixels.ogiri.session.Realm
import com.quantipixels.ogiri.session.SessionManager
import com.quantipixels.ogiri.session.SessionStore
import com.quantipixels.ogiri.session.SubjectId
import com.quantipixels.ogiri.session.SubjectRef
import com.quantipixels.ogiri.test.InMemorySessionStore
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@SpringBootTest(
    classes = [OgiriSecureChainTest.TestApplication::class],
    properties =
        [
            "ogiri.session.enabled=true",
            "ogiri.session.token-hash.current-key-id=test",
            "ogiri.session.token-hash.keys.test=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "ogiri.session.public-paths=/public,/auth/sign-in",
            "ogiri.session.endpoints.enabled=true",
        ],
)
@AutoConfigureMockMvc
class OgiriSecureChainTest {
  @Autowired private lateinit var mockMvc: MockMvc
  @Autowired private lateinit var sessions: SessionManager

  @Test
  fun `protected controller rejects an anonymous request`() {
    mockMvc.get("/protected").andExpect { status { isUnauthorized() } }
  }

  @Test
  fun `only configured public route is anonymous`() {
    mockMvc.get("/public").andExpect {
      status { isOk() }
      content { string("public") }
    }
    mockMvc.get("/protected").andExpect { status { isUnauthorized() } }
  }

  @Test
  fun `issued bearer header round-trips through selected chain`() {
    val issued =
        sessions.issue(
            SubjectRef(Realm("users"), SubjectId("user-42")),
            ClientContext("browser"),
        )
    val header =
        "Bearer ${issued.credential.encoded(com.quantipixels.ogiri.session.OpaqueTokenCodec())}"

    mockMvc
        .get("/protected") { header(HttpHeaders.AUTHORIZATION, header) }
        .andExpect {
          status { isOk() }
          content { string("user-42") }
        }
  }

  @Test
  fun `endpoint starter signs in lists and idempotently revokes current session`() {
    val login =
        mockMvc
            .post("/auth/sign-in") {
              contentType = MediaType.APPLICATION_JSON
              content = """{"username":"user-42","password":"password","clientId":"browser"}"""
            }
            .andExpect {
              status { isCreated() }
              header { exists(HttpHeaders.AUTHORIZATION) }
              header { string(HttpHeaders.CACHE_CONTROL, "no-store") }
              jsonPath("$.subject") { value("user-42") }
            }
            .andReturn()
    val authorization = login.response.getHeader(HttpHeaders.AUTHORIZATION)!!

    mockMvc
        .get("/auth/sessions") { header(HttpHeaders.AUTHORIZATION, authorization) }
        .andExpect {
          status { isOk() }
          jsonPath("$[0].current") { value(true) }
        }
    mockMvc
        .delete("/auth/sign-out") { header(HttpHeaders.AUTHORIZATION, authorization) }
        .andExpect {
          status { isNoContent() }
          header { doesNotExist(HttpHeaders.AUTHORIZATION) }
        }
    mockMvc
        .get("/protected") { header(HttpHeaders.AUTHORIZATION, authorization) }
        .andExpect { status { isUnauthorized() } }
  }

  @SpringBootApplication
  class TestApplication {
    @Bean fun sessionStore(): SessionStore = InMemorySessionStore()

    @Bean
    fun userDetailsService(): UserDetailsService =
        InMemoryUserDetailsManager(
            User.withUsername("user-42").password("{noop}password").roles("USER").build())

    @Bean
    fun authenticationManager(users: UserDetailsService): AuthenticationManager =
        AuthenticationManager { request ->
          val user = users.loadUserByUsername(request.name)
          if (request.credentials != "password") throw BadCredentialsException("bad_credentials")
          UsernamePasswordAuthenticationToken.authenticated(user, null, user.authorities)
        }

    @Bean fun testController(): TestController = TestController()
  }

  @RestController
  class TestController {
    @GetMapping("/public") fun publicRoute(): String = "public"

    @GetMapping("/protected")
    fun protectedRoute(authentication: org.springframework.security.core.Authentication): String =
        (authentication.principal as OgiriSessionPrincipal).subject
  }
}
