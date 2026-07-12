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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class OgiriSessionPropertiesTest {
  @Test
  fun `endpoint base path accepts canonical literal paths`() {
    assertEquals(
        "/api/session-auth",
        OgiriSessionProperties.Endpoints(basePath = "/api/session-auth").basePath)
  }

  @Test
  fun `endpoint base path rejects ambiguous mappings`() {
    listOf("", "auth", "/", "/auth/", "/api//auth", "/auth?internal=true", "/auth/**", "/{auth}")
        .forEach { path ->
          assertThrows(
              IllegalArgumentException::class.java,
              { OgiriSessionProperties.Endpoints(basePath = path) },
              path)
        }
  }
}
