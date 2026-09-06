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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "demo.password=password",
      "ogiri.session.token-hash.keys.primary=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    })
@AutoConfigureMockMvc
class SessionIntegrationTest {
  @Autowired MockMvc mvc;

  private String login() throws Exception {
    return mvc.perform(
            post("/auth/sign-in")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"demo\",\"password\":\"password\"}"))
        .andExpect(status().isCreated())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andReturn()
        .getResponse()
        .getHeader("Authorization");
  }

  @Test
  void lifecycleUsesNativeUserDirectoryAndJpaWithoutSecurityBoilerplate() throws Exception {
    mvc.perform(get("/hello")).andExpect(status().isUnauthorized());
    String credential = login();
    assertNotNull(credential);
    mvc.perform(get("/hello").header("Authorization", credential))
        .andExpect(status().isOk())
        .andExpect(content().string("demo"));
    mvc.perform(get("/admin").header("Authorization", credential))
        .andExpect(status().isForbidden());
    mvc.perform(get("/auth/sessions").header("Authorization", credential))
        .andExpect(status().isOk());
    String rotated =
        mvc.perform(post("/auth/refresh").header("Authorization", credential))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getHeader("Authorization");
    assertNotNull(rotated);
    assertNotEquals(credential, rotated);
    mvc.perform(delete("/auth/sign-out").header("Authorization", rotated))
        .andExpect(status().isNoContent());
    mvc.perform(get("/hello").header("Authorization", rotated))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void wrongPasswordAndMalformedRequestsDoNotBecomeServerErrors() throws Exception {
    mvc.perform(
            post("/auth/sign-in")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"demo\",\"password\":\"wrong\"}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/auth/sign-in").contentType(MediaType.APPLICATION_JSON).content("{"))
        .andExpect(status().isBadRequest());
  }
}
