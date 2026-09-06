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

import com.fasterxml.jackson.databind.ObjectMapper
import com.quantipixels.ogiri.session.HmacSha256TokenHasher
import com.quantipixels.ogiri.session.IdentifierGenerator
import com.quantipixels.ogiri.session.NoOpSessionEventPublisher
import com.quantipixels.ogiri.session.OpaqueTokenCodec
import com.quantipixels.ogiri.session.SecureRandomIdentifierGenerator
import com.quantipixels.ogiri.session.SessionEventPublisher
import com.quantipixels.ogiri.session.SessionManager
import com.quantipixels.ogiri.session.SessionPolicy
import com.quantipixels.ogiri.session.SessionStore
import com.quantipixels.ogiri.session.SubjectStatusChecker
import com.quantipixels.ogiri.session.TokenCodec
import com.quantipixels.ogiri.session.TokenHasher
import io.micrometer.core.instrument.MeterRegistry
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.autoconfigure.security.ConditionalOnDefaultWebSecurity
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.security.authentication.AccountStatusException
import org.springframework.security.authentication.AccountStatusUserDetailsChecker
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.ProviderManager
import org.springframework.security.authentication.dao.DaoAuthenticationProvider
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.security.web.util.matcher.RequestMatcher

/**
 * Spring Boot auto-configuration for the session subsystem.
 *
 * Activates only when `ogiri.session.enabled=true`. Every extension boundary with an application-
 * specific policy or infrastructure implementation backs off when a user bean is present.
 */
@AutoConfiguration(before = [SecurityAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(OgiriSessionProperties::class)
@ConditionalOnProperty(
    prefix = "ogiri.session",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
public open class OgiriSessionAutoConfiguration {
  @Bean @ConditionalOnMissingBean public open fun ogiriClock(): Clock = Clock.systemUTC()

  @Bean @ConditionalOnMissingBean public open fun ogiriSecureRandom(): SecureRandom = SecureRandom()

  @Bean
  @ConditionalOnMissingBean
  public open fun ogiriIdentifierGenerator(secureRandom: SecureRandom): IdentifierGenerator =
      SecureRandomIdentifierGenerator(secureRandom)

  @Bean
  @ConditionalOnMissingBean
  public open fun ogiriTokenCodec(secureRandom: SecureRandom): TokenCodec =
      OpaqueTokenCodec(secureRandom)

  /**
   * Creates the default key-rotatable verifier hasher.
   *
   * Fails startup rather than issuing credentials when key material is absent or malformed.
   */
  @Bean
  @ConditionalOnMissingBean
  public open fun ogiriTokenHasher(properties: OgiriSessionProperties): TokenHasher {
    val configured = properties.tokenHash
    require(configured.currentKeyId.isNotBlank()) {
      "ogiri.session.token-hash.current-key-id is required unless a TokenHasher bean is supplied"
    }
    require(configured.keys.isNotEmpty()) {
      "ogiri.session.token-hash.keys must contain at least one Base64-encoded 256-bit key"
    }
    val keys =
        configured.keys.mapValues { (id, encoded) ->
          runCatching { Base64.getDecoder().decode(encoded) }
              .getOrElse {
                throw IllegalArgumentException("token-hash key '$id' is not valid Base64")
              }
        }
    return HmacSha256TokenHasher(configured.currentKeyId, keys)
  }

  @Bean
  @ConditionalOnMissingBean
  public open fun ogiriSessionEventPublisher(
      registry: ObjectProvider<MeterRegistry>
  ): SessionEventPublisher =
      registry.getIfAvailable()?.let(::OgiriSessionMetrics) ?: NoOpSessionEventPublisher

  @Bean
  @ConditionalOnBean(UserDetailsService::class)
  @ConditionalOnMissingBean
  public open fun ogiriSubjectStatusChecker(
      users: UserDetailsService,
      properties: OgiriSessionProperties = OgiriSessionProperties()
  ): SubjectStatusChecker {
    val checker = AccountStatusUserDetailsChecker()
    return SubjectStatusChecker { subject ->
      if (subject.realm.value != properties.realm || subject.tenantId != null) {
        return@SubjectStatusChecker false
      }
      try {
        checker.check(users.loadUserByUsername(subject.subjectId.value))
        true
      } catch (_: UsernameNotFoundException) {
        false
      } catch (_: AccountStatusException) {
        false
      }
    }
  }

  /** Builds the session coordinator once persistence and subject-status policies are available. */
  @Bean
  @ConditionalOnMissingBean
  public open fun ogiriSessionManager(
      store: SessionStore,
      codec: TokenCodec,
      hasher: TokenHasher,
      statusChecker: SubjectStatusChecker,
      clock: Clock,
      events: SessionEventPublisher,
      identifiers: IdentifierGenerator,
      properties: OgiriSessionProperties,
  ): SessionManager =
      SessionManager(
          store,
          codec,
          hasher,
          statusChecker,
          clock,
          SessionPolicy(
              properties.lifetime,
              properties.previousVersionGrace,
              properties.maximumActiveSessions,
              properties.evictOldestWhenFull,
          ),
          events,
          identifiers,
      )

  @Bean
  @ConditionalOnBean(SessionManager::class)
  @ConditionalOnMissingBean
  public open fun ogiriAuthorityResolver(
      users: ObjectProvider<UserDetailsService>,
      properties: OgiriSessionProperties
  ): OgiriAuthorityResolver = OgiriAuthorityResolver { session ->
    val directory = users.getIfAvailable()
    if (directory == null ||
        session.subject.realm.value != properties.realm ||
        session.subject.tenantId != null) {
      emptyList()
    } else {
      val user = directory.loadUserByUsername(session.subject.subjectId.value)
      AccountStatusUserDetailsChecker().check(user)
      user.authorities
    }
  }

  @Bean
  @ConditionalOnBean(SessionManager::class)
  public open fun ogiriSessionAuthenticationProvider(
      sessions: SessionManager,
      authorities: OgiriAuthorityResolver,
  ): OgiriSessionAuthenticationProvider = OgiriSessionAuthenticationProvider(sessions, authorities)

  @Bean
  public open fun ogiriProblemAuthenticationEntryPoint(
      mapper: ObjectMapper
  ): OgiriProblemAuthenticationEntryPoint = OgiriProblemAuthenticationEntryPoint(mapper)

  @Bean
  @ConditionalOnBean(OgiriSessionAuthenticationProvider::class)
  public open fun ogiriHttpConfigurer(
      provider: OgiriSessionAuthenticationProvider,
      entryPoint: OgiriProblemAuthenticationEntryPoint,
      properties: OgiriSessionProperties,
  ): OgiriHttpConfigurer = OgiriHttpConfigurer(provider, entryPoint, properties)

  @Bean
  public open fun ogiriSessionResponseWriter(
      properties: OgiriSessionProperties,
      codec: TokenCodec,
  ): OgiriSessionResponseWriter = OgiriSessionResponseWriter(properties, codec)

  @Bean
  public open fun ogiriRequestCredentialResolver(
      properties: OgiriSessionProperties
  ): OgiriRequestCredentialResolver = OgiriRequestCredentialResolver(properties)

  @Bean
  @ConditionalOnMissingBean
  public open fun ogiriSubjectResolver(properties: OgiriSessionProperties): OgiriSubjectResolver =
      OgiriSubjectResolver { authentication ->
        com.quantipixels.ogiri.session.SubjectRef(
            com.quantipixels.ogiri.session.Realm(properties.realm),
            com.quantipixels.ogiri.session.SubjectId(authentication.name),
        )
      }

  @Bean
  @ConditionalOnMissingBean
  public open fun ogiriClientContextResolver(
      identifiers: IdentifierGenerator
  ): OgiriClientContextResolver = OgiriClientContextResolver { request, requestedClientId ->
    com.quantipixels.ogiri.session.ClientContext(
        requestedClientId?.takeIf(String::isNotBlank) ?: identifiers.next(),
        userAgent = request.getHeader("User-Agent")?.take(512),
        ipAddress = request.remoteAddr,
    )
  }

  @Bean
  @ConditionalOnBean(SessionManager::class)
  @ConditionalOnProperty(
      prefix = "ogiri.session.endpoints",
      name = ["enabled"],
      havingValue = "true",
  )
  public open fun ogiriSessionEndpointController(
      authenticationConfiguration: AuthenticationConfiguration,
      authenticationManagers: ObjectProvider<AuthenticationManager>,
      users: ObjectProvider<UserDetailsService>,
      encoders: ObjectProvider<PasswordEncoder>,
      sessions: SessionManager,
      subjectResolver: OgiriSubjectResolver,
      clientResolver: OgiriClientContextResolver,
      credentialResolver: OgiriRequestCredentialResolver,
      responses: OgiriSessionResponseWriter,
      rateLimiters: ObjectProvider<OgiriRateLimiter>,
      clock: Clock,
      properties: OgiriSessionProperties,
  ): OgiriSessionEndpointController =
      OgiriSessionEndpointController(
          authenticationManagers.getIfAvailable {
            val directory = users.getIfAvailable()
            if (directory == null) authenticationConfiguration.authenticationManager
            else
                ProviderManager(
                    DaoAuthenticationProvider(directory).apply {
                      setPasswordEncoder(
                          encoders.getIfAvailable {
                            PasswordEncoderFactories.createDelegatingPasswordEncoder()
                          })
                    })
          },
          sessions,
          subjectResolver,
          clientResolver,
          credentialResolver,
          responses,
          rateLimiters.getIfAvailable(),
          clock,
          properties.rateLimit,
      )

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(
      prefix = "ogiri.session.endpoints",
      name = ["enabled"],
      havingValue = "true",
  )
  public open fun ogiriProblemHandler(): OgiriProblemHandler = OgiriProblemHandler()

  @Bean
  @ConditionalOnProperty(
      prefix = "ogiri.session.cleanup",
      name = ["enabled"],
      havingValue = "true",
  )
  @ConditionalOnMissingBean(OgiriJobLease::class)
  public open fun ogiriMissingJobLease(): OgiriJobLease =
      throw IllegalStateException(
          "ogiri.session.cleanup.enabled requires a cluster-safe OgiriJobLease bean")

  @Bean(destroyMethod = "shutdown")
  @ConditionalOnProperty(
      prefix = "ogiri.session.cleanup",
      name = ["enabled"],
      havingValue = "true",
  )
  @ConditionalOnMissingBean(TaskScheduler::class)
  public open fun ogiriCleanupTaskScheduler(): ThreadPoolTaskScheduler =
      ThreadPoolTaskScheduler().apply {
        poolSize = 1
        setThreadNamePrefix("ogiri-cleanup-")
        setWaitForTasksToCompleteOnShutdown(true)
        initialize()
      }

  @Bean
  @ConditionalOnBean(SessionManager::class, OgiriJobLease::class)
  @ConditionalOnProperty(
      prefix = "ogiri.session.cleanup",
      name = ["enabled"],
      havingValue = "true",
  )
  public open fun ogiriSessionCleanupScheduler(
      sessions: SessionManager,
      lease: OgiriJobLease,
      scheduler: TaskScheduler,
      clock: Clock,
      properties: OgiriSessionProperties,
  ): OgiriSessionCleanupScheduler =
      OgiriSessionCleanupScheduler(sessions, lease, scheduler, clock, properties.cleanup)

  /**
   * Supplies a stateless default security chain when the application has not declared one.
   *
   * Configured public paths are permitted and every other request requires authentication.
   */
  @Bean("ogiriSecureSecurityFilterChain")
  @ConditionalOnBean(OgiriHttpConfigurer::class)
  @ConditionalOnDefaultWebSecurity
  public open fun ogiriSecureSecurityFilterChain(
      http: HttpSecurity,
      configurer: OgiriHttpConfigurer,
      properties: OgiriSessionProperties,
  ): SecurityFilterChain {
    http
        .formLogin { it.disable() }
        .httpBasic { it.disable() }
        .logout { it.disable() }
        .requestCache { it.disable() }
        .with(configurer) {}
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .authorizeHttpRequests {
          if (properties.endpoints.enabled) {
            it.requestMatchers(HttpMethod.POST, "${properties.endpoints.basePath}/sign-in")
                .permitAll()
          }
          if (properties.publicPaths.isNotEmpty()) {
            it.requestMatchers(*properties.publicPaths.toTypedArray()).permitAll()
          }
          it.anyRequest().authenticated()
        }
    if (properties.transport != OgiriTransport.COOKIE) {
      val credentials = OgiriRequestCredentialResolver(properties)
      val signIn =
          PathPatternRequestMatcher.withDefaults()
              .matcher(HttpMethod.POST, "${properties.endpoints.basePath}/sign-in")
      // Exempt explicit, non-ambient credentials, not every request in the application.
      http.csrf { csrf ->
        csrf.ignoringRequestMatchers(
            RequestMatcher { request ->
              val jsonSignIn =
                  properties.endpoints.enabled &&
                      signIn.matches(request) &&
                      MediaType.APPLICATION_JSON_VALUE.equals(
                          request.contentType?.substringBefore(';')?.trim(), ignoreCase = true)
              jsonSignIn ||
                  try {
                    credentials.resolve(request) != null
                  } catch (_: AuthenticationException) {
                    false
                  }
            })
      }
    }
    return http.build()
  }
}
