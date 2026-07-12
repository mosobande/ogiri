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
package com.quantipixels.ogiri.session

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionModelTest {
  @Test
  fun `session identifiers have value equality`() {
    val first = SessionId("session-1")
    val second = SessionId("session-1")

    assertEquals(first, second)
    assertEquals(first.hashCode(), second.hashCode())
    assertEquals("value", mapOf(first to "value")[second])
  }

  @Test
  fun `credential string representations redact transport material`() {
    val credential =
        SessionCredential(SessionId("session-1"), "distinct-selector", "distinct-verifier")
    val issued =
        IssuedSession(
            StoredSession(
                id = credential.sessionId,
                selector = credential.selector,
                subject = SubjectRef(Realm("users"), SubjectId("subject")),
                client = ClientContext("browser"),
                currentDigest = TokenDigest("key", "digest"),
                previousDigest = null,
                previousValidUntil = null,
                version = 0,
                familyId = "family",
                createdAt = Instant.EPOCH,
                lastUsedAt = Instant.EPOCH,
                expiresAt = Instant.EPOCH.plusSeconds(60),
            ),
            credential,
        )

    assertFalse(credential.toString().contains(credential.selector))
    assertFalse(credential.toString().contains(credential.verifier))
    assertFalse(issued.toString().contains(credential.selector))
    assertFalse(issued.toString().contains(credential.verifier))
    assertTrue(credential.toString().contains("[REDACTED]"))
  }
}
