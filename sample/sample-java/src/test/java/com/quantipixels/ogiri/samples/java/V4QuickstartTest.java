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
package com.quantipixels.ogiri.samples.java;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.quantipixels.ogiri.session.HmacSha256TokenHasher;
import com.quantipixels.ogiri.session.OgiriSessions;
import com.quantipixels.ogiri.session.OpaqueTokenCodec;
import com.quantipixels.ogiri.session.SessionManager;
import com.quantipixels.ogiri.test.InMemorySessionStore;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;

class V4QuickstartTest {
  @Test
  void documentedV4CoreFlowRoundTrips() {
    OpaqueTokenCodec codec = new OpaqueTokenCodec();
    SessionManager sessions =
        new SessionManager(
            new InMemorySessionStore(),
            codec,
            new HmacSha256TokenHasher("primary", Map.of("primary", new byte[32])),
            subject -> true,
            Clock.systemUTC());
    var issued =
        sessions.issue(
            OgiriSessions.subject("users", "opaque-user-id", "tenant-a"),
            OgiriSessions.client("browser-id", "Work laptop"));

    var authenticated = sessions.authenticate(issued.getCredential().encoded(codec));

    assertEquals("opaque-user-id", authenticated.getSubject().subjectValue());
  }
}
