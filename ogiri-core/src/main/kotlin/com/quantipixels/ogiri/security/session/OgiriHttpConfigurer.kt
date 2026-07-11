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

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.InsufficientAuthenticationException
import org.springframework.security.authentication.ProviderManager
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.access.AccessDeniedHandlerImpl
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.security.web.authentication.AuthenticationConverter
import org.springframework.security.web.authentication.AuthenticationFilter
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfException

public class OgiriHttpConfigurer(
    private val provider: OgiriSessionAuthenticationProvider,
    private val entryPoint: OgiriProblemAuthenticationEntryPoint,
    private val properties: OgiriSessionProperties,
) : AbstractHttpConfigurer<OgiriHttpConfigurer, HttpSecurity>() {
  override fun init(http: HttpSecurity) {
    http
        .formLogin { it.disable() }
        .httpBasic { it.disable() }
        .logout { it.disable() }
        .requestCache { it.disable() }
        .anonymous { it.disable() }
    val forbidden = AccessDeniedHandlerImpl()
    http.exceptionHandling {
      it.authenticationEntryPoint(entryPoint).accessDeniedHandler { request, response, denied ->
        if (denied is CsrfException) {
          forbidden.handle(request, response, denied)
        } else {
          val authentication = SecurityContextHolder.getContext().authentication
          if (authentication == null ||
              !authentication.isAuthenticated ||
              authentication is AnonymousAuthenticationToken) {
            entryPoint.commence(
                request,
                response,
                InsufficientAuthenticationException("authentication_required", denied),
            )
          } else {
            forbidden.handle(request, response, denied)
          }
        }
      }
    }
    if (properties.transport == OgiriTransport.COOKIE) {
      http.csrf { it.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()) }
    } else {
      // Header credentials are not ambient browser authority; CSRF applies only to cookie mode.
      // lgtm[java/spring-disabled-csrf-protection]
      http.csrf { it.disable() }
    }
  }

  override fun configure(http: HttpSecurity) {
    val manager: AuthenticationManager = ProviderManager(provider)
    val filter = AuthenticationFilter(manager, converter())
    filter.setFailureHandler(entryPoint)
    filter.setSuccessHandler { _, _, _ -> }
    filter.setSecurityContextRepository(RequestAttributeSecurityContextRepository())
    http
        .authenticationProvider(provider)
        .addFilterBefore(filter, AnonymousAuthenticationFilter::class.java)
  }

  private fun converter(): AuthenticationConverter =
      when (properties.transport) {
        OgiriTransport.BEARER ->
            OgiriBearerAuthenticationConverter(properties.maximumCredentialBytes)
        OgiriTransport.COOKIE -> AuthenticationConverter(::cookieCredential)
        OgiriTransport.DTA_COMPAT -> AuthenticationConverter(::dtaCredential)
      }

  private fun cookieCredential(request: HttpServletRequest): OgiriSessionAuthenticationToken? {
    val values = request.cookies?.filter { it.name == properties.cookie.name }.orEmpty()
    if (values.isEmpty()) return null
    if (values.size != 1 || values.single().value.isBlank()) {
      throw BadCredentialsException("malformed_cookie_credential")
    }
    return OgiriSessionAuthenticationToken.unauthenticated(values.single().value)
  }

  private fun dtaCredential(request: HttpServletRequest): OgiriSessionAuthenticationToken? {
    if (request.getHeader(HttpHeaders.AUTHORIZATION) != null) {
      throw BadCredentialsException("ambiguous_credential_transport")
    }
    val token = request.getHeader("access-token") ?: return null
    if (token.isBlank()) throw BadCredentialsException("malformed_dta_credential")
    return OgiriSessionAuthenticationToken.unauthenticated(token)
  }

  public companion object {
    @JvmStatic
    public fun apply(http: HttpSecurity, configurer: OgiriHttpConfigurer): HttpSecurity =
        http.with(configurer) {}
  }
}
