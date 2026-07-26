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
package com.quantipixels.ogiri.samples.kotlin

import com.quantipixels.ogiri.session.HmacSha256TokenHasher
import com.quantipixels.ogiri.session.OgiriSessions
import com.quantipixels.ogiri.session.OpaqueTokenCodec
import com.quantipixels.ogiri.session.SessionManager
import com.quantipixels.ogiri.session.SubjectStatusChecker
import com.quantipixels.ogiri.test.InMemorySessionStore
import java.time.Clock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class V4QuickstartTest {
  @Test
  fun `documented v4 core flow round-trips`() {
    val codec = OpaqueTokenCodec()
    val sessions =
        SessionManager(
            InMemorySessionStore(),
            codec,
            HmacSha256TokenHasher("primary", mapOf("primary" to ByteArray(32) { 7 })),
            SubjectStatusChecker { true },
            Clock.systemUTC(),
        )
    val issued =
        sessions.issue(
            OgiriSessions.subject("users", "opaque-user-id", "tenant-a"),
            OgiriSessions.client("browser-id", "Work laptop"),
        )

    val authenticated = sessions.authenticate(issued.credential.encoded(codec))

    assertEquals("opaque-user-id", authenticated.subject.subjectId.value)
  }
}
