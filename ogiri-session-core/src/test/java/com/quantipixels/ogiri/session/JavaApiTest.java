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

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class JavaApiTest {
  @Test
  void identitiesAndCodecsAreUsableWithoutKotlinGeneratedNames() {
    SubjectRef subject =
        new SubjectRef(new Realm("users"), new SubjectId("user-42"), new TenantId("tenant-a"));
    assertEquals("user-42", subject.getSubjectId().getValue());
    assertEquals("tenant-a", subject.getTenantId().getValue());
    assertNull(new SubjectRef(new Realm("users"), new SubjectId("user-42")).getTenantId());
    assertEquals("browser", new ClientContext("browser").getClientId());
    TokenCodec codec = new OpaqueTokenCodec();
    DecodedCredential generated = codec.generate();
    assertEquals(
        generated, codec.decode(codec.encode(generated.getSelector(), generated.getVerifier())));
  }
}
