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
package com.quantipixels.ogiri.security.redis

import com.quantipixels.ogiri.security.config.OgiriConfigurationProperties
import com.quantipixels.ogiri.security.config.OgiriLookupTypeCondition
import com.quantipixels.ogiri.security.session.OgiriRateLimiter
import com.quantipixels.ogiri.security.session.OgiriSessionProperties
import com.quantipixels.ogiri.security.spi.OgiriTokenLookupCache
import com.quantipixels.ogiri.security.tokens.OgiriToken
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * Autoconfiguration for the Redis-backed [OgiriTokenLookupCache].
 *
 * Activates only when **all** of the following are true:
 * - `spring-boot-starter-data-redis` is on the classpath
 * - `ogiri.lookup.type=redis` is set in `application.yml` (case-insensitive, explicit opt-in)
 * - No `OgiriTokenLookupCache` bean is already registered (custom bean wins)
 *
 * The bean uses the application's existing [RedisConnectionFactory] — no extra Redis configuration
 * is needed beyond the standard `spring.data.redis.*` properties.
 */
@AutoConfiguration
@ConditionalOnClass(RedisTemplate::class)
@ConditionalOnMissingBean(OgiriTokenLookupCache::class)
@Conditional(OgiriRedisAutoConfiguration.OnRedisType::class)
class OgiriRedisAutoConfiguration {

  @Bean
  fun <T : OgiriToken> ogiriRedisTokenLookupCache(
      connectionFactory: RedisConnectionFactory,
      properties: OgiriConfigurationProperties,
  ): OgiriTokenLookupCache<T> = RedisOgiriTokenLookupCache(connectionFactory, properties)

  internal class OnRedisType : OgiriLookupTypeCondition("redis")
}

@AutoConfiguration
@ConditionalOnClass(StringRedisTemplate::class)
@ConditionalOnProperty(
    prefix = "ogiri.session.rate-limit",
    name = ["enabled"],
    havingValue = "true",
)
class OgiriRedisRateLimitAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean(OgiriRateLimiter::class)
  fun ogiriRedisRateLimiter(
      redis: StringRedisTemplate,
      properties: OgiriSessionProperties,
  ): OgiriRateLimiter = OgiriRedisRateLimiter(redis, properties)
}
