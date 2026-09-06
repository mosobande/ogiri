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

/** Converts successful primary authentication into the subject that will own a session. */
public fun interface OgiriSubjectResolver {
  /** Resolves the canonical session subject for [authentication]. */
  public fun resolve(authentication: Authentication): SubjectRef
}

/** Derives trusted client metadata for a newly issued session. */
public fun interface OgiriClientContextResolver {
  /** Resolves client metadata, treating [requestedClientId] as untrusted request input. */
  public fun resolve(request: HttpServletRequest, requestedClientId: String?): ClientContext
}

/** JSON request accepted by the optional sign-in endpoint. */
public data class SignInRequest(
    @field:NotBlank @field:jakarta.validation.constraints.Size(max = 255) val username: String,
    @field:NotBlank @field:jakarta.validation.constraints.Size(max = 4096) val password: String,
    @field:jakarta.validation.constraints.Size(max = 255) val clientId: String? = null,
)

/** Non-secret session representation returned by session-management endpoints. */
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

/**
 * Optional REST controller for the complete session lifecycle under the configured endpoint path.
 *
 * The controller is created only when `ogiri.session.endpoints.enabled=true`. Credentials are
 * written through [OgiriSessionResponseWriter] and never included in response bodies.
 */
@ConditionalOnProperty(
    prefix = "ogiri.session.endpoints",
    name = ["enabled"],
    havingValue = "true",
)
@RestController
@RequestMapping("\${ogiri.session.endpoints.base-path:/auth}")
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

  /** Authenticates primary credentials and issues a new client session. */
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

  /** Rotates the request's current session credential. */
  @PostMapping("/refresh")
  public fun refresh(request: HttpServletRequest, response: HttpServletResponse): SessionView {
    val issued = sessions.rotate(credentialResolver.resolveRequired(request))
    responses.writeCredential(response, issued)
    return issued.session.toView(current = true)
  }

  /** Revokes the current session and clears its response credential. */
  @DeleteMapping("/sign-out")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public fun signOut(authentication: Authentication, response: HttpServletResponse) {
    sessions.revoke(authentication.authenticatedSession())
    responses.clearCredential(response)
  }

  /** Returns the current session as stored, or reports it as revoked when no longer active. */
  @GetMapping("/session")
  public fun current(authentication: Authentication): SessionView {
    val current = authentication.authenticatedSession()
    return sessions
        .list(current.subject)
        .firstOrNull { it.id == current.sessionId }
        ?.toView(current = true)
        ?: throw SessionError.Revoked()
  }

  /** Lists every active session belonging to the current subject. */
  @GetMapping("/sessions")
  public fun list(authentication: Authentication): List<SessionView> {
    val current = authentication.authenticatedSession()
    return sessions.list(current.subject).map { it.toView(current = it.id == current.sessionId) }
  }

  /** Revokes a subject-owned session by ID without revealing whether another subject owns it. */
  @DeleteMapping("/sessions/{sessionId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public fun revoke(
      authentication: Authentication,
      @PathVariable sessionId: String,
  ) {
    sessions.revoke(authentication.authenticatedSession().subject, SessionId(sessionId))
  }

  /** Revokes every active session except the current session. */
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

/** Extracts a session credential from the transport selected in [OgiriSessionProperties]. */
public class OgiriRequestCredentialResolver(private val properties: OgiriSessionProperties) {
  /** Resolves a credential or throws when the configured transport contains none. */
  public fun resolveRequired(request: HttpServletRequest): String =
      resolve(request) ?: throw IllegalArgumentException("session credential is required")

  /** Resolves the configured credential transport, returning `null` when absent or malformed. */
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
