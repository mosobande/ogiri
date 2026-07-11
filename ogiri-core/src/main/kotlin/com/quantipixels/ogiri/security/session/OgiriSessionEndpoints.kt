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

import com.quantipixels.ogiri.session.AuthenticatedSession
import com.quantipixels.ogiri.session.ClientContext
import com.quantipixels.ogiri.session.Realm
import com.quantipixels.ogiri.session.SessionError
import com.quantipixels.ogiri.session.SessionId
import com.quantipixels.ogiri.session.SessionManager
import com.quantipixels.ogiri.session.StoredSession
import com.quantipixels.ogiri.session.SubjectId
import com.quantipixels.ogiri.session.SubjectRef
import com.quantipixels.ogiri.session.TenantId
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import java.time.Clock
import java.time.Instant
import java.util.Locale
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

public fun interface OgiriSubjectResolver {
  public fun resolve(authentication: Authentication): SubjectRef
}

public fun interface OgiriClientContextResolver {
  public fun resolve(request: HttpServletRequest, requestedClientId: String?): ClientContext
}

public data class SignInRequest(
    @field:NotBlank val username: String,
    @field:NotBlank val password: String,
    val clientId: String? = null,
)

public data class SessionView(
    val id: String,
    val subject: String,
    val realm: String,
    val tenant: String?,
    val clientId: String,
    val clientLabel: String?,
    val createdAt: Instant,
    val lastUsedAt: Instant,
    val expiresAt: Instant,
    val current: Boolean,
)

@ConditionalOnProperty(
    prefix = "ogiri.session.endpoints",
    name = ["enabled"],
    havingValue = "true",
)
@RestController
@RequestMapping("/auth")
public class OgiriSessionEndpointController(
    private val authenticationManager: AuthenticationManager,
    private val sessions: SessionManager,
    private val subjectResolver: OgiriSubjectResolver,
    private val clientResolver: OgiriClientContextResolver,
    private val credentialResolver: OgiriRequestCredentialResolver,
    private val responses: OgiriSessionResponseWriter,
    private val rateLimiter: OgiriRateLimiter?,
    private val clock: Clock,
    private val rateLimit: OgiriSessionProperties.RateLimit,
) {
  init {
    require(!rateLimit.enabled || rateLimiter != null) {
      "ogiri.session.rate-limit.enabled requires an OgiriRateLimiter bean"
    }
  }

  @PostMapping("/sign-in")
  @ResponseStatus(HttpStatus.CREATED)
  public fun signIn(
      @Valid @RequestBody body: SignInRequest,
      request: HttpServletRequest,
      response: HttpServletResponse,
  ): SessionView {
    enforceSignInRateLimit(request, body.username)
    val authentication =
        authenticationManager.authenticate(
            UsernamePasswordAuthenticationToken.unauthenticated(body.username, body.password))
    val issued =
        sessions.issue(
            subjectResolver.resolve(authentication),
            clientResolver.resolve(request, body.clientId),
        )
    responses.writeCredential(response, issued)
    return issued.session.toView(current = true)
  }

  @PostMapping("/refresh")
  public fun refresh(request: HttpServletRequest, response: HttpServletResponse): SessionView {
    val issued = sessions.rotate(credentialResolver.resolveRequired(request))
    responses.writeCredential(response, issued)
    return issued.session.toView(current = true)
  }

  @DeleteMapping("/sign-out")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public fun signOut(authentication: Authentication, response: HttpServletResponse) {
    sessions.revoke(authentication.authenticatedSession())
    responses.clearCredential(response)
  }

  @GetMapping("/session")
  public fun current(authentication: Authentication): SessionView {
    val current = authentication.authenticatedSession()
    return sessions
        .list(current.subject)
        .firstOrNull { it.id == current.sessionId }
        ?.toView(current = true)
        ?: throw SessionError.Revoked()
  }

  @GetMapping("/sessions")
  public fun list(authentication: Authentication): List<SessionView> {
    val current = authentication.authenticatedSession()
    return sessions.list(current.subject).map { it.toView(current = it.id == current.sessionId) }
  }

  @DeleteMapping("/sessions/{sessionId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public fun revoke(
      authentication: Authentication,
      @PathVariable sessionId: String,
  ) {
    sessions.revoke(authentication.authenticatedSession().subject, SessionId(sessionId))
  }

  @DeleteMapping("/sessions")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public fun revokeOthers(authentication: Authentication) {
    sessions.revokeOthers(authentication.authenticatedSession())
  }

  private fun enforceSignInRateLimit(request: HttpServletRequest, username: String) {
    if (!rateLimit.enabled) return
    val limiter = requireNotNull(rateLimiter)
    val now = clock.instant()
    val keys =
        listOf(
            "sign-in:ip:${request.remoteAddr}",
            "sign-in:identifier:${username.trim().lowercase(Locale.ROOT)}",
        )
    keys.forEach { key ->
      val decision = limiter.consume(key, rateLimit.signInPermits, rateLimit.window, now)
      if (!decision.allowed) throw OgiriRateLimitExceeded(decision.retryAfter)
    }
  }

  private fun Authentication.authenticatedSession(): AuthenticatedSession {
    val value =
        principal as? OgiriSessionPrincipal
            ?: throw IllegalStateException("Ogiri session principal is required")
    return AuthenticatedSession(
        SessionId(value.sessionId),
        SubjectRef(
            Realm(value.realm),
            SubjectId(value.subject),
            value.tenant?.let(::TenantId),
        ),
        ClientContext(value.clientId),
        value.version,
        value.familyId,
        false,
    )
  }
}

public class OgiriRequestCredentialResolver(private val properties: OgiriSessionProperties) {
  public fun resolveRequired(request: HttpServletRequest): String =
      resolve(request) ?: throw IllegalArgumentException("session credential is required")

  public fun resolve(request: HttpServletRequest): String? {
    return when (properties.transport) {
      OgiriTransport.BEARER -> {
        val value = request.getHeader(HttpHeaders.AUTHORIZATION) ?: return null
        val parts = value.split(' ', limit = 2)
        if (parts.size != 2 || !parts[0].equals("Bearer", ignoreCase = true)) return null
        parts[1]
      }
      OgiriTransport.COOKIE ->
          request.cookies?.singleOrNull { it.name == properties.cookie.name }?.value
      OgiriTransport.DTA_COMPAT -> request.getHeader("access-token")
    }
  }
}

private fun StoredSession.toView(current: Boolean): SessionView =
    SessionView(
        id.value,
        subject.subjectId.value,
        subject.realm.value,
        subject.tenantId?.value,
        client.clientId,
        client.label,
        createdAt,
        lastUsedAt,
        expiresAt,
        current,
    )
