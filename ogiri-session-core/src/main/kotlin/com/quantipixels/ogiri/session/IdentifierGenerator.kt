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
import java.util.UUID

/** Supplies opaque identifiers for sessions, token families, and lifecycle events. */
public fun interface IdentifierGenerator {
  /** Returns a new identifier suitable for durable storage. */
  public fun next(): String
}

/** Generates UUID-shaped identifiers from the supplied cryptographically secure random source. */
public class SecureRandomIdentifierGenerator(
    private val secureRandom: SecureRandom,
) : IdentifierGenerator {
  override fun next(): String = UUID(secureRandom.nextLong(), secureRandom.nextLong()).toString()
}
