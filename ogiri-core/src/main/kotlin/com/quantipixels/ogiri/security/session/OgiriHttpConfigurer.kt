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

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.function.Supplier
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.AuthenticationManager
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
import org.springframework.security.web.csrf.CsrfFilter
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler
import org.springframework.security.web.csrf.CsrfTokenRequestHandler
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler
import org.springframework.web.filter.OncePerRequestFilter

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
      http
          .csrf {
            it.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(OgiriSpaCsrfTokenRequestHandler())
          }
          .addFilterAfter(OgiriCsrfCookieFilter(), CsrfFilter::class.java)
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

  private fun converter(): AuthenticationConverter {
    val resolver = OgiriRequestCredentialResolver(properties)
    return AuthenticationConverter { request ->
      resolver.resolve(request)?.let(OgiriSessionAuthenticationToken::unauthenticated)
    }
  }

  public companion object {
    @JvmStatic
    public fun apply(http: HttpSecurity, configurer: OgiriHttpConfigurer): HttpSecurity =
        http.with(configurer) {}
  }
}

private class OgiriSpaCsrfTokenRequestHandler : CsrfTokenRequestHandler {
  private val plain = CsrfTokenRequestAttributeHandler()
  private val xor = XorCsrfTokenRequestAttributeHandler()

  override fun handle(
      request: HttpServletRequest,
      response: HttpServletResponse,
      csrfToken: Supplier<CsrfToken>,
  ) {
    xor.handle(request, response, csrfToken)
  }

  override fun resolveCsrfTokenValue(request: HttpServletRequest, csrfToken: CsrfToken): String? =
      if (request.getHeader(csrfToken.headerName).isNullOrBlank()) {
        xor.resolveCsrfTokenValue(request, csrfToken)
      } else {
        plain.resolveCsrfTokenValue(request, csrfToken)
      }
}

private class OgiriCsrfCookieFilter : OncePerRequestFilter() {
  override fun doFilterInternal(
      request: HttpServletRequest,
      response: HttpServletResponse,
      filterChain: FilterChain,
  ) {
    (request.getAttribute(CsrfToken::class.java.name) as? CsrfToken)?.token
    filterChain.doFilter(request, response)
  }
}
