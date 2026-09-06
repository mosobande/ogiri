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
package example.consumer

import com.quantipixels.ogiri.security.session.OgiriProblemHandler
import com.quantipixels.ogiri.session.SessionStore
import com.quantipixels.ogiri.test.InMemorySessionStore
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

@SpringBootTest(
    classes = [OgiriEndpointAutoConfigurationTest.TestApplication::class],
    properties =
        [
            "ogiri.session.enabled=true",
            "ogiri.session.token-hash.current-key-id=test",
            "ogiri.session.token-hash.keys.test=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "ogiri.session.endpoints.enabled=true",
            "ogiri.session.endpoints.base-path=/api/session-auth",
            "ogiri.session.public-paths[0]=/public-action",
        ],
)
@AutoConfigureMockMvc
class OgiriEndpointAutoConfigurationTest {
  @Autowired private lateinit var mockMvc: MockMvc
  @Autowired private lateinit var problemHandler: OgiriProblemHandler

  @Test
  fun `external consumer receives auto-configured problem responses on custom path`() {
    assertNotNull(problemHandler)

    mockMvc
        .post("/api/session-auth/sign-in") {
          contentType = MediaType.APPLICATION_JSON
          content = """{"username":"","password":""}"""
        }
        .andExpect {
          status { isUnprocessableEntity() }
          header { string(HttpHeaders.CACHE_CONTROL, "no-store") }
          jsonPath("$.code") { value("invalid_request") }
        }

    mockMvc
        .post("/api/session-auth/sign-in") {
          contentType = MediaType.APPLICATION_JSON
          content = """{"username":"user-42","password":"password","clientId":"browser"}"""
        }
        .andExpect {
          status { isCreated() }
          header { exists(HttpHeaders.AUTHORIZATION) }
        }

    mockMvc
        .post("/auth/sign-in") {
          with(csrf())
          contentType = MediaType.APPLICATION_JSON
          content = """{"username":"user-42","password":"password"}"""
        }
        .andExpect { status { isUnauthorized() } }
  }

  @Test
  fun `starter keeps CSRF protection on unrelated public writes`() {
    mockMvc.post("/public-action").andExpect { status { isForbidden() } }
    mockMvc
        .post("/public-action") { with(csrf()) }
        .andExpect {
          status { isOk() }
          content { string("done") }
        }
  }

  @Test
  fun `a browser simple request cannot acquire the JSON sign-in exemption`() {
    for (type in listOf(MediaType.TEXT_PLAIN, MediaType.APPLICATION_FORM_URLENCODED)) {
      mockMvc
          .post("/api/session-auth/sign-in") {
            contentType = type
            content = """{"username":"user-42","password":"password"}"""
          }
          .andExpect { status { isForbidden() } }
    }
  }

  @SpringBootApplication
  @RestController
  class TestApplication {
    @PostMapping("/public-action") fun publicAction(): String = "done"

    @Bean fun sessionStore(): SessionStore = InMemorySessionStore()

    @Bean
    fun userDetailsService(): UserDetailsService =
        InMemoryUserDetailsManager(
            User.withUsername("user-42").password("{noop}password").roles("USER").build())
  }
}
