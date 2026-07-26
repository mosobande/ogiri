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
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

@SpringBootTest(
    classes = [OgiriCookieSecurityTest.TestApplication::class],
    properties =
        [
            "ogiri.session.enabled=true",
            "ogiri.session.transport=COOKIE",
            "ogiri.session.token-hash.current-key-id=test",
            "ogiri.session.token-hash.keys.test=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "ogiri.session.public-paths[0]=/public",
        ],
)
@AutoConfigureMockMvc
class OgiriCookieSecurityTest {
  @Autowired private lateinit var mockMvc: MockMvc
  @Autowired private lateinit var sessions: SessionManager

  @Test
  fun `cookie authenticated mutation requires csrf token`() {
    val issued =
        sessions.issue(
            SubjectRef(Realm("users"), SubjectId("user-42")),
            ClientContext("browser"),
        )
    val cookie =
        Cookie(
            "__Host-ogiri-session",
            issued.credential.encoded(com.quantipixels.ogiri.session.OpaqueTokenCodec()),
        )
    val bootstrap = mockMvc.get("/public").andExpect { status { isOk() } }.andReturn().response
    val csrfCookie = bootstrap.getCookie("XSRF-TOKEN")
    assertNotNull(csrfCookie)

    mockMvc.post("/protected") { cookie(cookie) }.andExpect { status { isForbidden() } }
    mockMvc
        .post("/protected") {
          cookie(cookie, requireNotNull(csrfCookie))
          header("X-XSRF-TOKEN", csrfCookie.value)
        }
        .andExpect { status { isOk() } }
  }

  @SpringBootApplication
  class TestApplication {
    @Bean fun sessionStore(): SessionStore = InMemorySessionStore()

    @Bean
    fun userDetailsService(): UserDetailsService =
        InMemoryUserDetailsManager(
            User.withUsername("user-42").password("{noop}password").roles("USER").build())

    @Bean fun controller(): Controller = Controller()
  }

  @RestController
  class Controller {
    @GetMapping("/public") fun publicRoute(): String = "public"

    @PostMapping("/protected") fun protectedRoute(): String = "ok"
  }
}
