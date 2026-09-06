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

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@SpringBootTest(
    properties =
        [
            "demo.password=password",
            "ogiri.session.token-hash.keys.primary=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="])
@AutoConfigureMockMvc
class SessionIntegrationTest {
  @Autowired lateinit var mvc: MockMvc
  @Test
  fun `a Kotlin consumer needs no token repository or security chain`() {
    val credential =
        mvc.post("/auth/sign-in") {
              contentType = MediaType.APPLICATION_JSON
              content = """{"username":"demo","password":"password"}"""
            }
            .andExpect { status { isCreated() } }
            .andReturn()
            .response
            .getHeader("Authorization")!!
    mvc.get("/hello") { header("Authorization", credential) }
        .andExpect {
          status { isOk() }
          content { string("demo") }
        }
  }
}
