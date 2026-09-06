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

import com.quantipixels.ogiri.session.OpaqueTokenCodec
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/** HTTP transport used to receive and return session credentials. */
public enum class OgiriTransport {
  BEARER,
  COOKIE,
  DTA_COMPAT,
}

/** Supported values for a session cookie's `SameSite` attribute. */
public enum class OgiriSameSite {
  STRICT,
  LAX,
  NONE,
}

/**
 * Validated configuration for the `ogiri.session` subsystem.
 *
 * Security-sensitive defaults keep the subsystem and optional endpoints disabled, use strict
 * cookies, and require token-hash key material before a default
 * [com.quantipixels.ogiri.session.TokenHasher] can be created.
 */
@Validated
@ConfigurationProperties("ogiri.session")
public data class OgiriSessionProperties(
    val enabled: Boolean = false,
    val realm: String = "users",
    val transport: OgiriTransport = OgiriTransport.BEARER,
    @field:Min(OpaqueTokenCodec.MIN_CREDENTIAL_CHARS.toLong())
    @field:Max(4096)
    val maximumCredentialBytes: Int = 256,
    val lifetime: Duration = Duration.ofDays(14),
    val previousVersionGrace: Duration = Duration.ofSeconds(5),
    @field:Min(1) val maximumActiveSessions: Int = 10,
    val evictOldestWhenFull: Boolean = true,
    val publicPaths: List<String> = listOf("/actuator/health"),
    @field:Valid val tokenHash: TokenHash = TokenHash(),
    @field:Valid val cookie: Cookie = Cookie(),
    @field:Valid val endpoints: Endpoints = Endpoints(),
    @field:Valid val cleanup: Cleanup = Cleanup(),
    @field:Valid val rateLimit: RateLimit = RateLimit(),
) {
  init {
    require(realm.matches(Regex("[a-z0-9][a-z0-9._-]{0,62}"))) {
      "ogiri.session.realm has invalid syntax"
    }
    require(maximumCredentialBytes >= OpaqueTokenCodec.MIN_CREDENTIAL_CHARS) {
      "ogiri.session.maximum-credential-bytes must accept default credentials"
    }
    require(!lifetime.isNegative && !lifetime.isZero) { "ogiri.session.lifetime must be positive" }
    require(!previousVersionGrace.isNegative) {
      "ogiri.session.previous-version-grace must not be negative"
    }
  }

  /** Cookie attributes used when [transport] is [OgiriTransport.COOKIE]. */
  public data class Cookie(
      @field:NotBlank
      @field:Pattern(regexp = "(?:__Host-)?[!#$%&'*+.^_`|~0-9A-Za-z-]+")
      val name: String = "__Host-ogiri-session",
      val secure: Boolean = true,
      val httpOnly: Boolean = true,
      val sameSite: OgiriSameSite = OgiriSameSite.STRICT,
      val path: String = "/",
      val maxAgeSeconds: Long = 1_209_600,
  ) {
    @get:AssertTrue(message = "SameSite=None requires Secure=true")
    val secureSameSiteNone: Boolean
      get() = sameSite != OgiriSameSite.NONE || secure

    @get:AssertTrue(message = "__Host- cookies require Secure=true and Path=/")
    val validHostPrefix: Boolean
      get() = !name.startsWith("__Host-") || (secure && path == "/")
  }

  /** HMAC key ring used to hash credential verifiers; values are Base64-encoded key bytes. */
  public data class TokenHash(
      val currentKeyId: String = "",
      val keys: Map<String, String> = emptyMap(),
  )

  /** Scheduling, lease, and page-size settings for expired-session cleanup. */
  public data class Cleanup(
      val enabled: Boolean = false,
      val interval: Duration = Duration.ofHours(6),
      val lease: Duration = Duration.ofMinutes(30),
      val maxRunDuration: Duration = Duration.ofMinutes(5),
      @field:Min(1) @field:Max(10_000) val batchSize: Int = 500,
  ) {
    init {
      require(!interval.isNegative && !interval.isZero) {
        "ogiri.session.cleanup.interval must be positive"
      }
      require(!lease.isNegative && !lease.isZero) { "ogiri.session.cleanup.lease must be positive" }
      require(!maxRunDuration.isNegative && !maxRunDuration.isZero) {
        "ogiri.session.cleanup.max-run-duration must be positive"
      }
      require(maxRunDuration < lease) {
        "ogiri.session.cleanup.max-run-duration must be shorter than the lease"
      }
    }
  }

  /** Fixed-window rate-limit settings for the optional sign-in endpoint. */
  public data class RateLimit(
      val enabled: Boolean = false,
      @field:Min(1) val signInPermits: Long = 10,
      val window: Duration = Duration.ofMinutes(1),
      @field:NotBlank val keyPrefix: String = "ogiri:rate-limit:",
  ) {
    init {
      require(!window.isNegative && window.toMillis() > 0) {
        "ogiri.session.rate-limit.window must be at least one millisecond"
      }
    }
  }

  /** Settings for the optional built-in session-management HTTP endpoints. */
  public data class Endpoints(
      val enabled: Boolean = false,
      @field:NotBlank val basePath: String = "/auth",
  ) {
    init {
      require(basePath.matches(Regex("/(?:[A-Za-z0-9._~-]+(?:/[A-Za-z0-9._~-]+)*)"))) {
        "ogiri.session.endpoints.base-path must be a canonical absolute literal path"
      }
    }
  }
}
