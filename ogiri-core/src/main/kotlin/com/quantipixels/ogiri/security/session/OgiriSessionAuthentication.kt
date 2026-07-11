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
import com.quantipixels.ogiri.session.SessionError
import com.quantipixels.ogiri.session.SessionManager
import jakarta.servlet.http.HttpServletRequest
import java.nio.charset.StandardCharsets
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.authentication.AuthenticationProvider
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.core.Authentication
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.web.authentication.AuthenticationConverter

public data class OgiriSessionPrincipal(
    val subject: String,
    val realm: String,
    val tenant: String?,
    val sessionId: String,
    val clientId: String,
    val version: Long,
    val familyId: String,
)

public fun interface OgiriAuthorityResolver {
  public fun resolve(session: AuthenticatedSession): Collection<GrantedAuthority>
}

public class OgiriSessionAuthenticationToken
private constructor(
    private val rawCredential: String?,
    private val sessionPrincipal: OgiriSessionPrincipal?,
    authorities: Collection<GrantedAuthority>,
) : AbstractAuthenticationToken(authorities) {
  init {
    isAuthenticated = sessionPrincipal != null
  }

  override fun getCredentials(): Any = rawCredential.orEmpty()

  override fun getPrincipal(): Any = sessionPrincipal ?: ""

  public companion object {
    public fun unauthenticated(credential: String): OgiriSessionAuthenticationToken =
        OgiriSessionAuthenticationToken(credential, null, emptyList())

    public fun authenticated(
        principal: OgiriSessionPrincipal,
        authorities: Collection<GrantedAuthority>,
    ): OgiriSessionAuthenticationToken =
        OgiriSessionAuthenticationToken(null, principal, authorities)
  }
}

public class OgiriBearerAuthenticationConverter(
    private val maximumCredentialBytes: Int = 256,
) : AuthenticationConverter {
  init {
    require(maximumCredentialBytes in 64..4096) {
      "maximum credential bytes must be between 64 and 4096"
    }
  }

  override fun convert(request: HttpServletRequest): Authentication? {
    val values = request.getHeaders(HttpHeaders.AUTHORIZATION).toList()
    if (values.isEmpty()) return null
    if (values.size != 1) throw BadCredentialsException("multiple_authorization_headers")
    val value = values.single()
    if (value.toByteArray(StandardCharsets.ISO_8859_1).size > maximumCredentialBytes) {
      throw BadCredentialsException("credential_too_large")
    }
    val separator = value.indexOf(' ')
    if (separator <= 0 || value.substring(0, separator).lowercase() != "bearer") {
      throw BadCredentialsException("unsupported_authorization_scheme")
    }
    val credential = value.substring(separator + 1)
    if (!credential.matches(OPAQUE_CREDENTIAL)) {
      throw BadCredentialsException("malformed_bearer_credential")
    }
    return OgiriSessionAuthenticationToken.unauthenticated(credential)
  }

  private companion object {
    private val OPAQUE_CREDENTIAL = Regex("[A-Za-z0-9_-]{22,86}\\.[A-Za-z0-9_-]{22,86}")
  }
}

public class OgiriSessionAuthenticationProvider(
    private val sessions: SessionManager,
    private val authorityResolver: OgiriAuthorityResolver = OgiriAuthorityResolver {
      listOf(SimpleGrantedAuthority("ROLE_USER"))
    },
) : AuthenticationProvider {
  override fun authenticate(authentication: Authentication): Authentication {
    val raw =
        authentication.credentials as? String ?: throw BadCredentialsException("invalid_credential")
    val authenticated =
        try {
          sessions.authenticate(raw)
        } catch (error: SessionError) {
          throw BadCredentialsException(error.code, error)
        }
    val principal =
        OgiriSessionPrincipal(
            subject = authenticated.subject.subjectId.value,
            realm = authenticated.subject.realm.value,
            tenant = authenticated.subject.tenantId?.value,
            sessionId = authenticated.sessionId.value,
            clientId = authenticated.client.clientId,
            version = authenticated.version,
            familyId = authenticated.familyId,
        )
    return OgiriSessionAuthenticationToken.authenticated(
        principal,
        authorityResolver.resolve(authenticated),
    )
  }

  override fun supports(authentication: Class<*>): Boolean =
      OgiriSessionAuthenticationToken::class.java.isAssignableFrom(authentication)
}
