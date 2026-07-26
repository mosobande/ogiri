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

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.boot.autoconfigure.AutoConfigurationPackages
import org.springframework.boot.autoconfigure.domain.EntityScanPackages

internal class OgiriJpaEntityScanRegistrar : BeanDefinitionRegistryPostProcessor {
  override fun postProcessBeanDefinitionRegistry(registry: BeanDefinitionRegistry) {
    val applicationPackages =
        (registry as? ConfigurableListableBeanFactory)?.let { beanFactory ->
          runCatching { AutoConfigurationPackages.get(beanFactory) }.getOrDefault(emptyList())
        }
            ?: emptyList()
    EntityScanPackages.register(
        registry,
        applicationPackages + OgiriSessionEntity::class.java.packageName,
    )
  }

  override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory): Unit = Unit
}
