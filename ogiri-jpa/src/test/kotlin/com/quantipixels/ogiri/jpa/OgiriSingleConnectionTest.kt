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

import com.quantipixels.ogiri.session.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(
    classes = [OgiriJpaStarterIntegrationTest.TestApplication::class],
    properties =
        [
            "spring.datasource.url=jdbc:h2:mem:ogiri-one-connection;DB_CLOSE_DELAY=-1",
            "spring.datasource.hikari.maximum-pool-size=1",
            "spring.datasource.hikari.connection-timeout=1000",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "ogiri.session.enabled=true",
            "ogiri.session.token-hash.current-key-id=test",
            "ogiri.session.token-hash.keys.test=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        ])
class OgiriSingleConnectionTest {
  @Autowired lateinit var sessions: SessionManager
  @Test
  fun `admission and user-wide revocation do not require nested pooled connections`() {
    val subject = SubjectRef(Realm("users"), SubjectId("user-42"))
    sessions.issue(subject, ClientContext("browser"))
    assertEquals(1, sessions.list(subject).size)
    sessions.revokeAll(subject, RevocationReason.SIGN_OUT_ALL)
    assertEquals(0, sessions.list(subject).size)
  }
}
