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
package com.quantipixels.ogiri.samples.kotlin

import java.security.Principal
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@SpringBootApplication
@RestController
class Application {
  @Bean
  fun passwordEncoder(): PasswordEncoder =
      PasswordEncoderFactories.createDelegatingPasswordEncoder()
  @Bean
  fun users(
      encoder: PasswordEncoder,
      @Value("\${demo.password}") password: String
  ): UserDetailsService =
      InMemoryUserDetailsManager(
          User.withUsername("demo").password(encoder.encode(password)).roles("USER").build())
  @GetMapping("/hello") fun hello(principal: Principal): String = principal.name
}

fun main(args: Array<String>) {
  runApplication<Application>(*args)
}
