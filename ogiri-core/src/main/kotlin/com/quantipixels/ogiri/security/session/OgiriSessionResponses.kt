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
import com.quantipixels.ogiri.session.IssuedSession
import com.quantipixels.ogiri.session.OpaqueTokenCodec
import com.quantipixels.ogiri.session.TokenCodec
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.net.URI
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseCookie
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.authentication.AuthenticationFailureHandler

/**
 * Writes issued credentials using the configured response transport.
 *
 * Every response is marked `no-store`; credential values are emitted only in headers or cookies,
 * never in an endpoint response body.
 */
public class OgiriSessionResponseWriter(
    private val properties: OgiriSessionProperties,
    private val codec: TokenCodec = OpaqueTokenCodec(),
) {
  /** Writes [issued]'s credential and the response cache controls required for secret material. */
  public fun writeCredential(response: HttpServletResponse, issued: IssuedSession) {
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
    response.setHeader(HttpHeaders.PRAGMA, "no-cache")
    val encoded = issued.credential.encoded(codec)
    when (properties.transport) {
      OgiriTransport.BEARER -> response.setHeader(HttpHeaders.AUTHORIZATION, "Bearer $encoded")
      OgiriTransport.COOKIE ->
          response.addHeader(HttpHeaders.SET_COOKIE, credentialCookie(encoded).toString())
      OgiriTransport.DTA_COMPAT -> {
        response.setHeader("access-token", encoded)
        response.setHeader("client", issued.session.client.clientId)
        response.setHeader("uid", issued.session.subject.subjectId.value)
      }
    }
  }

  /** Expires the configured credential cookie, when applicable, and disables response caching. */
  public fun clearCredential(response: HttpServletResponse) {
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
    response.setHeader(HttpHeaders.PRAGMA, "no-cache")
    if (properties.transport == OgiriTransport.COOKIE) {
      response.addHeader(
          HttpHeaders.SET_COOKIE,
          credentialCookie("").mutate().maxAge(0).build().toString(),
      )
    }
  }

  private fun credentialCookie(value: String): ResponseCookie {
    val cookie = properties.cookie
    return ResponseCookie.from(cookie.name, value)
        .httpOnly(cookie.httpOnly)
        .secure(cookie.secure)
        .path(cookie.path)
        .sameSite(cookie.sameSite.name.lowercase().replaceFirstChar(Char::uppercase))
        .maxAge(cookie.maxAgeSeconds)
        .build()
  }
}

/**
 * Renders authentication failures as safe RFC 9457 problem details.
 *
 * Exception messages are exposed only when they match the restricted machine-code grammar.
 */
public class OgiriProblemAuthenticationEntryPoint(private val mapper: ObjectMapper) :
    AuthenticationEntryPoint, AuthenticationFailureHandler {
  override fun commence(
      request: HttpServletRequest,
      response: HttpServletResponse,
      authException: AuthenticationException,
  ) {
    write(request, response, authException)
  }

  override fun onAuthenticationFailure(
      request: HttpServletRequest,
      response: HttpServletResponse,
      exception: AuthenticationException,
  ) {
    write(request, response, exception)
  }

  private fun write(
      request: HttpServletRequest,
      response: HttpServletResponse,
      exception: AuthenticationException,
  ) {
    val code = exception.message?.takeIf { it.matches(SAFE_CODE) } ?: "invalid_credential"
    val problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication failed")
    problem.type = URI.create("https://quantipixels.com/problems/$code")
    problem.title = "Unauthorized"
    problem.instance = URI.create(request.requestURI)
    problem.setProperty("code", code)
    response.status = HttpStatus.UNAUTHORIZED.value()
    response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"")
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
    response.setHeader(HttpHeaders.PRAGMA, "no-cache")
    mapper.writeValue(response.outputStream, problem)
  }

  private companion object {
    private val SAFE_CODE = Regex("[a-z][a-z0-9_]{0,63}")
  }
}
