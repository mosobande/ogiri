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

/** Parsed credential parts. The verifier must never be logged or persisted. */
public data class DecodedCredential(public val selector: String, public val verifier: String) {
  override fun toString(): String = "DecodedCredential(selector=$selector, verifier=[REDACTED])"
}

/** Generates, encodes, and parses session credentials. */
public interface TokenCodec {
  public fun generate(): DecodedCredential
  public fun encode(selector: String, verifier: String): String
  public fun decode(encoded: String): DecodedCredential
}

/** Canonical unpadded Base64URL: a public selector and a secret verifier separated by a dot. */
public class OpaqueTokenCodec
@JvmOverloads
public constructor(
    private val secureRandom: SecureRandom = SecureRandom(),
    private val selectorBytes: Int = 16,
    private val verifierBytes: Int = 32,
) : TokenCodec {
  init {
    require(selectorBytes in 16..48) { "selector must contain 16 to 48 bytes" }
    require(verifierBytes in 32..64) { "verifier must contain 32 to 64 bytes" }
    require(
        encodedLength(selectorBytes) + encodedLength(verifierBytes) + 1 <= MAX_CREDENTIAL_CHARS) {
          "encoded credential exceeds the transport limit"
        }
  }
  override fun generate(): DecodedCredential =
      DecodedCredential(randomPart(selectorBytes), randomPart(verifierBytes))

  override fun encode(selector: String, verifier: String): String {
    validatePart(selector, 16, 48)
    validatePart(verifier, 32, 64)
    if (selector.length + verifier.length + 1 > MAX_CREDENTIAL_CHARS)
        throw SessionError.InvalidCredential()
    return "$selector.$verifier"
  }

  override fun decode(encoded: String): DecodedCredential {
    if (encoded.length !in MIN_CREDENTIAL_CHARS..MAX_CREDENTIAL_CHARS)
        throw SessionError.InvalidCredential()
    val separator = encoded.indexOf('.')
    if (separator <= 0 || separator != encoded.lastIndexOf('.'))
        throw SessionError.InvalidCredential()
    val selector = encoded.substring(0, separator)
    val verifier = encoded.substring(separator + 1)
    validatePart(selector, 16, 48)
    validatePart(verifier, 32, 64)
    return DecodedCredential(selector, verifier)
  }

  private fun randomPart(size: Int): String =
      ENCODER.encodeToString(ByteArray(size).also(secureRandom::nextBytes))

  private fun validatePart(value: String, minimum: Int, maximum: Int) {
    if (value.length !in encodedLength(minimum)..encodedLength(maximum) ||
        value.any {
          it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' && it != '-' && it != '_'
        }) {
      throw SessionError.InvalidCredential()
    }
    val decoded =
        try {
          DECODER.decode(value)
        } catch (_: IllegalArgumentException) {
          throw SessionError.InvalidCredential()
        }
    if (decoded.size !in minimum..maximum || ENCODER.encodeToString(decoded) != value) {
      throw SessionError.InvalidCredential()
    }
  }

  public companion object {
    public const val MIN_CREDENTIAL_CHARS: Int = 66
    public const val MAX_CREDENTIAL_CHARS: Int = 128
    private val ENCODER = Base64.getUrlEncoder().withoutPadding()
    private val DECODER = Base64.getUrlDecoder()
    private fun encodedLength(bytes: Int): Int = (bytes * 8 + 5) / 6
  }
}
