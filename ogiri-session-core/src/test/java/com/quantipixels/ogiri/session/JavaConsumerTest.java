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
package com.quantipixels.ogiri.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class JavaConsumerTest {
  @Test
  void publicFactoriesAreUsableWithoutKotlinImplementationTypes() {
    SubjectRef subject = OgiriSessions.subject("users", "opaque-subject", "tenant-a");
    ClientContext client = OgiriSessions.client("browser", "Laptop");
    SessionPolicy policy =
        OgiriSessions.policy().lifetime(Duration.ofDays(7)).maximumActiveSessions(3).build();

    assertEquals("opaque-subject", subject.subjectValue());
    assertEquals("browser", client.getClientId());
    assertEquals(3, policy.getMaximumActiveSessions());
  }
}
