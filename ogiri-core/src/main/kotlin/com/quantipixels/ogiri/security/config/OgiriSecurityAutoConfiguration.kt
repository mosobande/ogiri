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
package com.quantipixels.ogiri.security.config

import com.quantipixels.ogiri.security.core.DefaultIdentifierPolicy
import com.quantipixels.ogiri.security.core.IdentifierPolicy
import com.quantipixels.ogiri.security.helpers.AuthenticationBypassDecider
import com.quantipixels.ogiri.security.routes.OgiriRouteCatalog
import com.quantipixels.ogiri.security.routes.OgiriRouteRegistry
import com.quantipixels.ogiri.security.spi.OgiriAuditHook
import com.quantipixels.ogiri.security.spi.OgiriRateLimitHook
import com.quantipixels.ogiri.security.spi.OgiriSpringCacheAdapter
import com.quantipixels.ogiri.security.spi.OgiriTokenLookupCache
import com.quantipixels.ogiri.security.spi.OgiriUserDirectory
import com.quantipixels.ogiri.security.tokens.DefaultOgiriSubTokenRegistry
import com.quantipixels.ogiri.security.tokens.DefaultOgiriTokenServiceResolver
import com.quantipixels.ogiri.security.tokens.OgiriSubTokenRegistration
import com.quantipixels.ogiri.security.tokens.OgiriSubTokenRegistry
import com.quantipixels.ogiri.security.tokens.OgiriToken
import com.quantipixels.ogiri.security.tokens.OgiriTokenCleanupJob
import com.quantipixels.ogiri.security.tokens.OgiriTokenRepository
import com.quantipixels.ogiri.security.tokens.OgiriTokenService
import com.quantipixels.ogiri.security.tokens.OgiriTokenServiceResolver
import com.quantipixels.ogiri.security.web.OgiriAuthenticationEntryPoint
import com.quantipixels.ogiri.security.web.OgiriTokenAuthenticationFilter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.cache.CacheManager
import org.springframework.context.MessageSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.util.matcher.AntPathRequestMatcher
import org.springframework.security.web.util.matcher.OrRequestMatcher
import org.springframework.security.web.util.matcher.RequestMatcher

/**
 * Auto-configures ogiri beans (token service, filters, registries).
 *
 * This configuration class:
 * - Enables the OgiriConfigurationProperties for property binding
 * - Creates core ogiri beans (TokenService, TokenAuthenticationFilter, etc.)
 * - Registers the security filter chain when enabled via properties
 * - Conditionally registers OgiriTokenCleanupJob for expired token deletion
 *
 * Configuration is loaded from application.yml/application.properties with prefix "ogiri". All
 * beans are registered with @ConditionalOnMissingBean for easy override.
 */
@Configuration
@EnableConfigurationProperties(OgiriConfigurationProperties::class)
class OgiriSecurityAutoConfiguration {
  companion object {
    private val logger = LoggerFactory.getLogger(OgiriSecurityAutoConfiguration::class.java)
  }

  /**
   * Auto-configures BCryptPasswordEncoder when no PasswordEncoder bean is present.
   *
   * @return a BCryptPasswordEncoder instance
   */
  @Bean
  @ConditionalOnMissingBean(PasswordEncoder::class)
  fun ogiriPasswordEncoder(): PasswordEncoder {
    logger.info("No PasswordEncoder bean found, auto-configuring BCryptPasswordEncoder")
    return BCryptPasswordEncoder()
  }

  /**
   * Provides a default identifier policy used by the token system.
   *
   * This supplies a DefaultIdentifierPolicy instance when no other IdentifierPolicy bean is
   * present.
   *
   * @return a new DefaultIdentifierPolicy instance
   */
  @Bean
  @ConditionalOnMissingBean(IdentifierPolicy::class)
  fun identifierPolicy(): DefaultIdentifierPolicy = DefaultIdentifierPolicy()

  /**
   * Creates an OgiriRouteCatalog by aggregating all available OgiriRouteRegistry beans in order.
   *
   * @param registries Provider of OgiriRouteRegistry instances; collected in the provider's
   *   declared order.
   * @return An OgiriRouteCatalog containing the ordered list of route registries.
   */
  @Bean
  @ConditionalOnMissingBean(OgiriRouteCatalog::class)
  fun routeCatalog(registries: ObjectProvider<OgiriRouteRegistry>): OgiriRouteCatalog =
      OgiriRouteCatalog(registries.orderedStream().toList())

  /**
   * Creates an AuthenticationBypassDecider that determines which routes should bypass
   * authentication using the provided route catalog and bypass paths from configuration.
   *
   * @param routeCatalog The catalog of registered routes used to evaluate bypass rules.
   * @param properties Ogiri configuration properties containing bypass paths.
   * @return An AuthenticationBypassDecider configured with the route catalog and bypass matcher.
   */
  @Bean
  @ConditionalOnMissingBean(AuthenticationBypassDecider::class)
  fun authenticationBypassDecider(
      routeCatalog: OgiriRouteCatalog,
      properties: OgiriConfigurationProperties,
  ): AuthenticationBypassDecider {
    val matchers = properties.security.bypassPaths.map { AntPathRequestMatcher(it) }
    val bypassMatcher: RequestMatcher =
        if (matchers.isEmpty()) RequestMatcher { false } else OrRequestMatcher(matchers.toList())
    return AuthenticationBypassDecider(routeCatalog, bypassMatcher)
  }

  /**
   * Creates a DefaultOgiriSubTokenRegistry initialized with the available sub-token registrations.
   *
   * @param registrations provider of OgiriSubTokenRegistration instances; the provider's ordered
   *   stream is used to preserve registration order
   * @return a DefaultOgiriSubTokenRegistry containing the registrations provided by `registrations`
   */
  @Bean
  @ConditionalOnMissingBean(OgiriSubTokenRegistry::class)
  fun subTokenRegistry(
      registrations: ObjectProvider<OgiriSubTokenRegistration>
  ): DefaultOgiriSubTokenRegistry =
      DefaultOgiriSubTokenRegistry(registrations.orderedStream().toList())

  /**
   * Tier 2 cache: bridges an existing Spring [CacheManager] into the [OgiriTokenLookupCache] SPI.
   *
   * Activated when:
   * - No custom [OgiriTokenLookupCache] bean is present (consumer Tier 1 takes precedence)
   * - A [CacheManager] bean is available (e.g., `spring.cache.type=redis`)
   * - `ogiri.cache.use-spring-cache-manager=true`
   *
   * @see OgiriSpringCacheAdapter for evictAll limitations on this tier.
   */
  @Bean
  @ConditionalOnMissingBean(OgiriTokenLookupCache::class)
  @ConditionalOnBean(CacheManager::class)
  @ConditionalOnProperty(
      prefix = "ogiri.cache",
      name = ["use-spring-cache-manager"],
      havingValue = "true",
  )
  fun <T : OgiriToken> ogiriSpringCacheAdapter(
      cacheManager: CacheManager,
      properties: OgiriConfigurationProperties,
  ): OgiriTokenLookupCache<T> = OgiriSpringCacheAdapter(cacheManager, properties.cache.cacheName)

  /**
   * Creates a default OgiriTokenService configured with the provided collaborators for token
   * management. Optional extension points ([OgiriAuditHook], [OgiriRateLimitHook],
   * [OgiriTokenLookupCache]) are resolved via [ObjectProvider] and wired via setter injection after
   * construction, following the Spring Security [AuthorizationFilter] pattern.
   *
   * @param repository Repository used to persist and retrieve tokens.
   * @param passwordEncoder Encoder used to hash or verify token secrets.
   * @param ogiriUserDirectory Directory used to resolve and validate users associated with tokens.
   * @param identifierPolicy Policy responsible for generating token identifiers.
   * @param subTokenRegistry Registry of sub-token registrations used by the service.
   * @param properties Ogiri configuration properties influencing token behavior.
   * @param auditHook Optional audit hook; resolved from context when present and wired via setter.
   * @param rateLimitHook Optional rate-limit hook; resolved from context when present and wired via
   *   setter.
   * @param lookupCache Optional token lookup cache; resolved from context when present and wired
   *   via setter.
   * @return An instance of OgiriTokenService configured with the given collaborators.
   */
  @Bean
  @ConditionalOnMissingBean(OgiriTokenService::class)
  @ConditionalOnProperty(
      prefix = "ogiri.auth",
      name = ["register-token-service"],
      havingValue = "true",
      matchIfMissing = true,
  )
  fun <T : OgiriToken> ogiriTokenService(
      repository: OgiriTokenRepository<T>,
      passwordEncoder: PasswordEncoder,
      ogiriUserDirectory: OgiriUserDirectory,
      identifierPolicy: IdentifierPolicy,
      subTokenRegistry: OgiriSubTokenRegistry,
      properties: OgiriConfigurationProperties,
      auditHook: ObjectProvider<OgiriAuditHook>,
      rateLimitHook: ObjectProvider<OgiriRateLimitHook>,
      lookupCache: ObjectProvider<OgiriTokenLookupCache<T>>,
  ): OgiriTokenService<T> {
    val service =
        OgiriTokenService(
            repository,
            passwordEncoder,
            ogiriUserDirectory,
            identifierPolicy,
            subTokenRegistry,
            properties,
        )
    auditHook.ifAvailable { service.setAuditHook(it) }
    rateLimitHook.ifAvailable { service.setRateLimitHook(it) }
    lookupCache.ifAvailable { service.setLookupCache(it) }
    return service
  }

  /**
   * Creates a resolver that selects and exposes available OgiriTokenService instances.
   *
   * @param tokenServices Map of all registered `OgiriTokenService` beans keyed by bean name.
   * @param properties Ogiri configuration properties used to influence resolver behavior.
   * @param beanFactory Spring bean factory used for any runtime bean resolution required by the
   *   resolver.
   * @return A `DefaultOgiriTokenServiceResolver` that resolves the appropriate `OgiriTokenService`
   *   implementations.
   */
  @Bean
  @ConditionalOnMissingBean(OgiriTokenServiceResolver::class)
  fun ogiriTokenServiceResolver(
      tokenServices: Map<String, OgiriTokenService<*>>,
      properties: OgiriConfigurationProperties,
      beanFactory: ConfigurableListableBeanFactory,
  ): DefaultOgiriTokenServiceResolver =
      DefaultOgiriTokenServiceResolver(tokenServices, properties, beanFactory)

  /**
   * Creates an OgiriAuthenticationEntryPoint that produces localized authentication failure
   * responses.
   *
   * When cookies are enabled, the entry point clears authentication cookies on 401 responses to
   * prevent clients from being stuck in a 401 loop with stale credentials.
   *
   * @param messageSource source of localized messages used by the entry point
   * @param properties Ogiri configuration properties containing cookie settings
   * @return an OgiriAuthenticationEntryPoint configured with the given MessageSource and properties
   */
  @Bean
  @ConditionalOnMissingBean(OgiriAuthenticationEntryPoint::class)
  fun ogiriAuthenticationEntryPoint(
      messageSource: MessageSource,
      properties: OgiriConfigurationProperties,
  ): OgiriAuthenticationEntryPoint = OgiriAuthenticationEntryPoint(messageSource, properties)

  /**
   * Creates an OgiriTokenAuthenticationFilter configured with the resolved token service and
   * provided collaborators.
   *
   * @param ogiriUserDirectory Directory used to resolve users from tokens.
   * @param tokenServiceResolver Resolver used to obtain the active OgiriTokenService instance.
   * @param authenticationEntryPoint Entry point invoked on authentication failures.
   * @param authenticationBypassDecider Decider that determines whether authentication should be
   *   bypassed for a request.
   * @param identifierPolicy Policy used to extract and validate identifiers from requests/tokens.
   * @param properties Ogiri configuration properties influencing filter behavior.
   * @return A configured OgiriTokenAuthenticationFilter instance.
   */
  @Bean
  @ConditionalOnMissingBean(OgiriTokenAuthenticationFilter::class)
  fun ogiriTokenAuthenticationFilter(
      ogiriUserDirectory: OgiriUserDirectory,
      tokenServiceResolver: OgiriTokenServiceResolver,
      @Qualifier("ogiriAuthenticationEntryPoint")
      authenticationEntryPoint: AuthenticationEntryPoint,
      authenticationBypassDecider: AuthenticationBypassDecider,
      identifierPolicy: IdentifierPolicy,
      properties: OgiriConfigurationProperties,
  ): OgiriTokenAuthenticationFilter =
      OgiriTokenAuthenticationFilter(
          ogiriUserDirectory,
          tokenServiceResolver.resolve(),
          authenticationEntryPoint,
          authenticationBypassDecider,
          identifierPolicy,
          properties,
      )

  /**
   * Creates a periodic job that cleans up expired Ogiri tokens using the provided token service
   * resolver.
   *
   * @param tokenServiceResolver Resolver used to locate available token services for cleanup.
   * @return The configured [OgiriTokenCleanupJob] responsible for expiring and removing tokens.
   */
  @Bean
  @ConditionalOnMissingBean(OgiriTokenCleanupJob::class)
  @ConditionalOnProperty(
      prefix = "ogiri.cleanup",
      name = ["enabled"],
      havingValue = "true",
      matchIfMissing = true,
  )
  fun ogiriTokenCleanupJob(tokenServiceResolver: OgiriTokenServiceResolver): OgiriTokenCleanupJob =
      OgiriTokenCleanupJob(tokenServiceResolver)

  /**
   * Configures a stateless SecurityFilterChain that applies Ogiri token authentication.
   *
   * This chain configures:
   * - CSRF (disabled by default; enabled automatically when `ogiri.cookies.same-site=None`)
   * - Stateless session management
   * - Authentication failure delegation to the provided entry point
   * - Ogiri token authentication filter inserted before UsernamePasswordAuthenticationFilter
   *
   * **This bean does NOT call `authorizeHttpRequests()`.** Ogiri handles authentication (verifying
   * who the caller is) but not authorization (which routes they may access). Without an
   * `authorizeHttpRequests()` call, Spring Security's default behaviour applies — all routes are
   * open to unauthenticated requests. Consumers MUST define their own `SecurityFilterChain` bean
   * with `authorizeHttpRequests()` rules to restrict access.
   *
   * @return A SecurityFilterChain that enforces token-based authentication, configures CSRF and
   *   stateless sessions, and delegates authentication failures to the provided
   *   AuthenticationEntryPoint. Route authorization rules are not configured.
   */
  @Bean(name = ["ogiriSecurityFilterChain"])
  @ConditionalOnMissingBean(name = ["ogiriSecurityFilterChain"])
  @ConditionalOnProperty(
      prefix = "ogiri.security",
      name = ["register-filter"],
      havingValue = "true",
      matchIfMissing = true,
  )
  @ConditionalOnWebApplication
  fun ogiriSecurityFilterChain(
      http: HttpSecurity,
      ogiriTokenAuthenticationFilter: OgiriTokenAuthenticationFilter,
      @Qualifier("ogiriAuthenticationEntryPoint")
      authenticationEntryPoint: AuthenticationEntryPoint,
      properties: OgiriConfigurationProperties,
  ): SecurityFilterChain =
      // In auto mode, only enable CSRF when cross-site cookies are in play.
      http
          .csrf { csrf ->
            if (shouldEnableCsrf(properties)) {
              csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
            } else {
              csrf.disable()
            }
          }
          .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
          .exceptionHandling { it.authenticationEntryPoint(authenticationEntryPoint) }
          .addFilterBefore(
              ogiriTokenAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
          // NOTE: Callers must configure their own authorizeHttpRequests() rules if needed.
          // By default, this chain only configures token-based authentication and stateless
          // sessions.
          .build()

  private fun shouldEnableCsrf(properties: OgiriConfigurationProperties): Boolean {
    val configured = properties.security.csrf.enabled.trim().lowercase()
    val autoEnabled =
        properties.cookies.enabled && properties.cookies.sameSite.equals("None", ignoreCase = true)

    return when (configured) {
      "true" -> true
      "false" -> false
      "auto" -> autoEnabled
      else -> {
        logger.warn(
            "Invalid ogiri.security.csrf.enabled='{}' (expected auto|true|false). Falling back to auto.",
            properties.security.csrf.enabled)
        autoEnabled
      }
    }
  }
}
