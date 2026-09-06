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
package example.composition

import com.quantipixels.ogiri.security.session.*
import com.quantipixels.ogiri.session.*
import com.quantipixels.ogiri.test.InMemorySessionStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.Authentication
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

@SpringBootTest(
    classes = [OgiriChainCompositionTest.Application::class],
    properties =
        [
            "ogiri.session.enabled=true",
            "ogiri.session.token-hash.current-key-id=test",
            "ogiri.session.token-hash.keys.test=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        ])
@AutoConfigureMockMvc
class OgiriChainCompositionTest {
  @Autowired lateinit var mvc: MockMvc
  @Autowired lateinit var sessions: SessionManager
  @Autowired lateinit var provider: OgiriSessionAuthenticationProvider
  private fun credential(): String =
      sessions
          .issue(SubjectRef(Realm("users"), SubjectId("demo")), ClientContext("browser"))
          .credential
          .encoded(OpaqueTokenCodec())

  @Test
  fun `an application chain retains its existing CSRF protection`() {
    val authorization = "Bearer ${credential()}"
    mvc.post("/protected") { header("Authorization", authorization) }
        .andExpect { status { isForbidden() } }
    mvc.post("/protected") {
          header("Authorization", authorization)
          with(csrf())
        }
        .andExpect {
          status { isOk() }
          content { string("demo") }
        }
  }
  @Test
  fun `an application Basic provider still works alongside Ogiri`() {
    mvc.get("/protected") { with(httpBasic("demo", "password")) }
        .andExpect {
          status { isOk() }
          content { string("demo") }
        }
  }
  @Test
  fun `provider inputs lose credentials on success and failure`() {
    val valid = OgiriSessionAuthenticationToken.unauthenticated(credential())
    assertEquals("demo", provider.authenticate(valid).name)
    assertEquals("", valid.credentials)
    val invalid = OgiriSessionAuthenticationToken.unauthenticated("invalid")
    assertThrows(BadCredentialsException::class.java) { provider.authenticate(invalid) }
    assertEquals("", invalid.credentials)
  }

  @SpringBootApplication
  @RestController
  class Application {
    @Bean fun store(): SessionStore = InMemorySessionStore()
    @Bean
    fun users(): UserDetailsService =
        InMemoryUserDetailsManager(
            User.withUsername("demo").password("{noop}password").roles("USER").build())
    @Bean
    fun chain(
        http: HttpSecurity,
        ogiri: OgiriHttpConfigurer,
        users: UserDetailsService
    ): SecurityFilterChain =
        http
            .with(ogiri) {}
            .authenticationProvider(DaoAuthenticationProvider(users))
            .httpBasic {}
            .authorizeHttpRequests { it.anyRequest().authenticated() }
            .build()
    @GetMapping("/protected") fun get(authentication: Authentication): String = authentication.name
    @PostMapping("/protected")
    fun post(authentication: Authentication): String = authentication.name
  }
}
