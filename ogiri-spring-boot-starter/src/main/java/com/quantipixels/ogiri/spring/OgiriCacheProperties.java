// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Quanti Pixels
package com.quantipixels.ogiri.spring;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param enabled opt into bounded-staleness session caching using the application's CacheManager
 * @param name dedicated cache region, shared only by instances using the same session database
 * @param maxAge maximum age of a cached validation, never extended by cache hits
 */
@ConfigurationProperties("ogiri.cache")
public record OgiriCacheProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("ogiri.sessions") String name, @DefaultValue("5s") Duration maxAge) {
    public OgiriCacheProperties {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("ogiri.cache.name must not be blank");
        if (maxAge == null || maxAge.compareTo(Duration.ofMillis(1)) < 0 || maxAge.compareTo(Duration.ofMinutes(1)) > 0)
            throw new IllegalArgumentException("ogiri.cache.max-age must be between 1ms and 1m");
    }
}
