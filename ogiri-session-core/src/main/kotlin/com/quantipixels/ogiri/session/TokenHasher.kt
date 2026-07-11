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

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Produces and verifies one-way digests of secret credential verifiers. */
public interface TokenHasher {
  /** Digests [verifier] with the current key and records that key's identifier. */
  public fun digest(verifier: String): TokenDigest

  /** Verifies [verifier] against [digest] in constant time when its key is available. */
  public fun matches(verifier: String, digest: TokenDigest): Boolean
}

/**
 * HMAC-SHA-256 token hasher with key identifiers for non-disruptive key rotation.
 *
 * New digests use [currentKeyId]; verification accepts any supplied key. Key byte arrays are copied
 * during construction so later caller mutation cannot change verification behavior.
 */
public class HmacSha256TokenHasher(
    private val currentKeyId: String,
    keys: Map<String, ByteArray>,
) : TokenHasher {
  private val keys: Map<String, ByteArray> = keys.mapValues { (_, key) -> key.copyOf() }

  init {
    require(currentKeyId in keys) { "current token-hash key is missing" }
    require(this.keys.isNotEmpty()) { "at least one token-hash key is required" }
    this.keys.forEach { (id, key) ->
      require(id.isNotBlank()) { "token-hash key ID must not be blank" }
      require(key.size >= MINIMUM_KEY_BYTES) {
        "token-hash key '$id' must contain at least 256 bits"
      }
    }
  }

  override fun digest(verifier: String): TokenDigest =
      TokenDigest(currentKeyId, encode(mac(keys.getValue(currentKeyId), verifier)))

  override fun matches(verifier: String, digest: TokenDigest): Boolean {
    val key = keys[digest.keyId] ?: return false
    val expected = runCatching { DECODER.decode(digest.value) }.getOrNull() ?: return false
    return MessageDigest.isEqual(mac(key, verifier), expected)
  }

  private fun mac(key: ByteArray, verifier: String): ByteArray =
      Mac.getInstance(ALGORITHM).run {
        init(SecretKeySpec(key, ALGORITHM))
        doFinal(verifier.toByteArray(StandardCharsets.US_ASCII))
      }

  private fun encode(value: ByteArray): String = ENCODER.encodeToString(value)

  public companion object {
    /** Minimum accepted HMAC key size, in bytes. */
    public const val MINIMUM_KEY_BYTES: Int = 32
    private const val ALGORITHM = "HmacSHA256"
    private val ENCODER = Base64.getUrlEncoder().withoutPadding()
    private val DECODER = Base64.getUrlDecoder()
  }
}
