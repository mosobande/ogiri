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

import com.quantipixels.ogiri.session.SessionEvent
import com.quantipixels.ogiri.session.SessionEventPublisher
import io.micrometer.core.instrument.MeterRegistry
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

public class OgiriSessionMetrics(private val registry: MeterRegistry) : SessionEventPublisher {
  private val lastEvent = AtomicReference<Instant?>()

  override fun publish(event: SessionEvent) {
    registry
        .counter(
            "ogiri.session.events",
            "action",
            event.action.name.lowercase(),
            "result",
            event.reason?.name?.lowercase() ?: "success",
        )
        .increment()
    lastEvent.set(event.occurredAt)
  }

  public fun lastEventAt(): Instant? = lastEvent.get()
}
