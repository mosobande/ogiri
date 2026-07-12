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
package com.quantipixels.ogiri.jpa

import com.quantipixels.ogiri.security.session.OgiriJobLease
import com.quantipixels.ogiri.security.session.OgiriSessionAutoConfiguration
import com.quantipixels.ogiri.session.SessionStore
import jakarta.persistence.EntityManager
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.transaction.PlatformTransactionManager

@AutoConfiguration(
    after = [HibernateJpaAutoConfiguration::class],
    before = [OgiriSessionAutoConfiguration::class],
)
@ConditionalOnClass(JpaRepository::class)
@Import(OgiriJpaEntityScanRegistrar::class)
public open class OgiriJpaAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean(SessionStore::class)
  public open fun ogiriJpaSessionStore(
      entityManager: EntityManager,
      transactionManager: PlatformTransactionManager,
  ): SessionStore = OgiriJpaSessionStore(entityManager, transactionManager)

  @Bean
  @ConditionalOnMissingBean(OgiriJobLease::class)
  public open fun ogiriJpaJobLease(
      entityManager: EntityManager,
      transactionManager: PlatformTransactionManager,
  ): OgiriJobLease = OgiriJpaJobLease(entityManager, transactionManager)
}
