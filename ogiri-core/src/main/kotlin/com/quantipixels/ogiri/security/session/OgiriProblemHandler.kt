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

import com.quantipixels.ogiri.session.SessionError
import java.net.URI
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(assignableTypes = [OgiriSessionEndpointController::class])
@ConditionalOnProperty(
    prefix = "ogiri.session.endpoints",
    name = ["enabled"],
    havingValue = "true",
)
public class OgiriProblemHandler {
  @ExceptionHandler(SessionError::class)
  public fun sessionError(error: SessionError): ResponseEntity<ProblemDetail> {
    val status =
        when (error) {
          is SessionError.SubjectUnavailable -> HttpStatus.FORBIDDEN
          is SessionError.Conflict,
          is SessionError.SessionLimitReached -> HttpStatus.CONFLICT
          else -> HttpStatus.UNAUTHORIZED
        }
    return response(status, error.code)
  }

  @ExceptionHandler(OgiriRateLimitExceeded::class)
  public fun rateLimited(error: OgiriRateLimitExceeded): ResponseEntity<ProblemDetail> =
      response(
          HttpStatus.TOO_MANY_REQUESTS,
          "rate_limit_exceeded",
          error.retryAfter.seconds.coerceAtLeast(1),
      )

  @ExceptionHandler(IllegalArgumentException::class)
  public fun malformedRequest(): ResponseEntity<ProblemDetail> =
      response(HttpStatus.BAD_REQUEST, "malformed_request")

  @ExceptionHandler(MethodArgumentNotValidException::class)
  public fun invalidRequest(): ResponseEntity<ProblemDetail> =
      response(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_request")

  private fun response(
      status: HttpStatus,
      code: String,
      retryAfterSeconds: Long? = null,
  ): ResponseEntity<ProblemDetail> {
    val problem = ProblemDetail.forStatusAndDetail(status, status.reasonPhrase)
    problem.type = URI.create("https://quantipixels.com/problems/$code")
    problem.title = status.reasonPhrase
    problem.setProperty("code", code)
    val headers = HttpHeaders()
    headers.cacheControl = CacheControl.noStore().headerValue
    headers.pragma = "no-cache"
    if (status == HttpStatus.UNAUTHORIZED) {
      headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"")
    }
    retryAfterSeconds?.let { headers.set(HttpHeaders.RETRY_AFTER, it.toString()) }
    return ResponseEntity(problem, headers, status)
  }
}
