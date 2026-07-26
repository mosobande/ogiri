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

import java.time.Duration

/**
 * Java-friendly factories for the session domain API.
 *
 * Kotlin callers may construct the corresponding value types directly.
 */
public object OgiriSessions {
  @JvmStatic
  @JvmOverloads
  /** Creates a validated subject reference from primitive values. */
  public fun subject(realm: String, subjectId: String, tenantId: String? = null): SubjectRef =
      SubjectRef(Realm(realm), SubjectId(subjectId), tenantId?.let(::TenantId))

  @JvmStatic
  @JvmOverloads
  /** Creates validated client metadata from primitive values. */
  public fun client(
      clientId: String,
      label: String? = null,
      userAgent: String? = null,
      ipAddress: String? = null,
  ): ClientContext = ClientContext(clientId, label, userAgent, ipAddress)

  /** Starts a builder initialized with the default [SessionPolicy]. */
  @JvmStatic public fun policy(): PolicyBuilder = PolicyBuilder()

  /** Fluent Java builder for [SessionPolicy]. */
  public class PolicyBuilder internal constructor() {
    private var lifetime: Duration = Duration.ofDays(14)
    private var grace: Duration = Duration.ofSeconds(5)
    private var maximum: Int = 10
    private var evictOldest: Boolean = true

    /** Sets how long newly issued sessions remain valid. */
    public fun lifetime(value: Duration): PolicyBuilder = apply { lifetime = value }

    /** Sets how long the immediately previous credential remains valid after rotation. */
    public fun previousVersionGrace(value: Duration): PolicyBuilder = apply { grace = value }

    /** Sets the maximum number of active sessions allowed for one subject. */
    public fun maximumActiveSessions(value: Int): PolicyBuilder = apply { maximum = value }

    /**
     * Chooses whether issuance evicts the oldest session when the active-session limit is reached.
     */
    public fun evictOldestWhenFull(value: Boolean): PolicyBuilder = apply { evictOldest = value }

    /** Builds and validates an immutable policy. */
    public fun build(): SessionPolicy = SessionPolicy(lifetime, grace, maximum, evictOldest)
  }
}
