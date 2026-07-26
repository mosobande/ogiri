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

import com.quantipixels.ogiri.session.SessionManager
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

@AutoConfiguration(after = [OgiriSessionAutoConfiguration::class])
@ConditionalOnClass(HealthIndicator::class)
@ConditionalOnBean(SessionManager::class)
public open class OgiriSessionObservabilityAutoConfiguration {
  @Bean("ogiriSessionHealthIndicator")
  @ConditionalOnMissingBean(name = ["ogiriSessionHealthIndicator"])
  public open fun ogiriSessionHealthIndicator(
      properties: OgiriSessionProperties,
  ): HealthIndicator = HealthIndicator {
    Health.up()
        .withDetail("realm", properties.realm)
        .withDetail("transport", properties.transport.name.lowercase())
        .withDetail("cleanupEnabled", properties.cleanup.enabled)
        .withDetail("rateLimitEnabled", properties.rateLimit.enabled)
        .build()
  }
}
