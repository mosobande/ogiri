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
package com.quantipixels.ogiri.jpa

import com.quantipixels.ogiri.session.ClientContext
import com.quantipixels.ogiri.session.Realm
import com.quantipixels.ogiri.session.SessionManager
import com.quantipixels.ogiri.session.SubjectId
import com.quantipixels.ogiri.session.SubjectRef
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.provisioning.InMemoryUserDetailsManager

@SpringBootTest(
    classes = [OgiriJpaStarterIntegrationTest.TestApplication::class],
    properties =
        [
            "spring.datasource.url=jdbc:h2:mem:ogiri-v4;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
            "ogiri.session.enabled=true",
            "ogiri.session.token-hash.current-key-id=test",
            "ogiri.session.token-hash.keys.test=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        ],
)
class OgiriJpaStarterIntegrationTest {
  @Autowired private lateinit var sessions: SessionManager

  @Test
  fun `blank consumer can issue authenticate and revoke through default JPA store`() {
    val subject = SubjectRef(Realm("users"), SubjectId("user-42"))
    val issued = sessions.issue(subject, ClientContext("browser"))
    val encoded = issued.credential.encoded(com.quantipixels.ogiri.session.OpaqueTokenCodec())

    assertEquals("user-42", sessions.authenticate(encoded).subject.subjectId.value)
    sessions.revokeAll(subject, com.quantipixels.ogiri.session.RevocationReason.SIGN_OUT_ALL)
    assertThrows(com.quantipixels.ogiri.session.SessionError.Revoked::class.java) {
      sessions.authenticate(encoded)
    }
  }

  @Test
  fun `concurrent JPA rotation commits exactly one successor`() {
    val issued =
        sessions.issue(
            SubjectRef(Realm("users"), SubjectId("user-42")),
            ClientContext("parallel-browser"),
        )
    val encoded = issued.credential.encoded(com.quantipixels.ogiri.session.OpaqueTokenCodec())
    val executor = Executors.newFixedThreadPool(8)
    val outcomes =
        try {
          executor.invokeAll(
              List(20) {
                Callable {
                  runCatching { sessions.rotate(encoded) }
                      .fold({ "rotated" }, { it::class.simpleName.orEmpty() })
                }
              })
        } finally {
          executor.shutdown()
        }

    assertEquals(1, outcomes.count { it.get() == "rotated" })
    assertEquals(19, outcomes.count { it.get() == "Conflict" })
  }

  @SpringBootApplication
  class TestApplication {
    @Bean
    fun users(): UserDetailsService =
        InMemoryUserDetailsManager(
            User.withUsername("user-42").password("{noop}password").roles("USER").build())
  }
}
