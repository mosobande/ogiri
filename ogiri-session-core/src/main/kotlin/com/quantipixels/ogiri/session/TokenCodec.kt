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

/**
 * Parsed credential parts.
 *
 * [selector] may be used for indexed lookup; [verifier] is secret credential material.
 */
public data class DecodedCredential(public val selector: String, public val verifier: String)

/** Generates, encodes, and parses transport-safe session credentials. */
public interface TokenCodec {
  /** Generates a cryptographically random selector and verifier. */
  public fun generate(): DecodedCredential

  /** Encodes validated credential parts into their transport representation. */
  public fun encode(selector: String, verifier: String): String

  /** Parses a transport representation or throws when it is malformed. */
  public fun decode(encoded: String): DecodedCredential
}

/**
 * Dot-delimited, unpadded Base64URL credential codec.
 *
 * The default sizes provide a 128-bit selector and a 256-bit verifier.
 */
public class OpaqueTokenCodec
@JvmOverloads
public constructor(
    private val secureRandom: SecureRandom = SecureRandom(),
    private val selectorBytes: Int = 16,
    private val verifierBytes: Int = 32,
) : TokenCodec {
  init {
    require(selectorBytes >= 16) { "selector must contain at least 128 bits" }
    require(verifierBytes >= 32) { "verifier must contain at least 256 bits" }
  }

  override fun generate(): DecodedCredential =
      DecodedCredential(randomPart(selectorBytes), randomPart(verifierBytes))

  override fun encode(selector: String, verifier: String): String {
    validatePart(selector, "selector")
    validatePart(verifier, "verifier")
    return "$selector.$verifier"
  }

  override fun decode(encoded: String): DecodedCredential {
    if (encoded.length > MAX_CREDENTIAL_CHARS) throw SessionError.InvalidCredential()
    val separator = encoded.indexOf('.')
    if (separator <= 0 || separator != encoded.lastIndexOf('.') || separator == encoded.lastIndex) {
      throw SessionError.InvalidCredential()
    }
    val selector = encoded.substring(0, separator)
    val verifier = encoded.substring(separator + 1)
    validatePart(selector, "selector")
    validatePart(verifier, "verifier")
    return DecodedCredential(selector, verifier)
  }

  private fun randomPart(size: Int): String {
    val bytes = ByteArray(size)
    secureRandom.nextBytes(bytes)
    return ENCODER.encodeToString(bytes)
  }

  private fun validatePart(value: String, label: String) {
    if (value.length !in 22..86 || !value.matches(BASE64_URL)) {
      throw IllegalArgumentException("$label is not canonical unpadded Base64URL")
    }
    runCatching { DECODER.decode(value) }.getOrElse { throw SessionError.InvalidCredential() }
  }

  public companion object {
    /** Smallest encoded credential accepted by this codec. */
    public const val MIN_CREDENTIAL_CHARS: Int = 66
    /** Largest encoded credential accepted, bounding parsing work for untrusted input. */
    public const val MAX_CREDENTIAL_CHARS: Int = 128
    private val BASE64_URL = Regex("[A-Za-z0-9_-]+")
    private val ENCODER = Base64.getUrlEncoder().withoutPadding()
    private val DECODER = Base64.getUrlDecoder()
  }
}
