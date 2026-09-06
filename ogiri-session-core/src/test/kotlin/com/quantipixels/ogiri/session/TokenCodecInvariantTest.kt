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

import java.security.SecureRandom
import java.util.Base64
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TokenCodecInvariantTest {
  @Test
  fun `supported boundary sizes round trip`() {
    for ((selector, verifier) in listOf(16 to 32, 48 to 32, 16 to 64)) {
      val codec = OpaqueTokenCodec(SecureRandom(), selector, verifier)
      val parts = codec.generate()
      assertEquals(parts, codec.decode(codec.encode(parts.selector, parts.verifier)))
      assertFalse(parts.toString().contains(parts.verifier))
    }
    assertThrows(IllegalArgumentException::class.java) { OpaqueTokenCodec(SecureRandom(), 48, 64) }
  }
  @Test
  fun `canonical representation and 256 bit verifier are required`() {
    val enc = Base64.getUrlEncoder().withoutPadding()
    val selector = enc.encodeToString(ByteArray(16))
    val verifier = enc.encodeToString(ByteArray(32))
    for (value in
        listOf(
            "$selector.$selector",
            "${selector.dropLast(1)}B.$verifier",
            "$selector.${verifier.dropLast(1)}B",
            "$selector.$verifier=",
            "$selector.$verifier.extra")) {
      assertThrows(SessionError.InvalidCredential::class.java) { OpaqueTokenCodec().decode(value) }
    }
  }
}
