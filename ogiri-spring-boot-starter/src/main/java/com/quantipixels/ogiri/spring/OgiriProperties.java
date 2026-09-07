// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Quanti Pixels
package com.quantipixels.ogiri.spring;


import com.quantipixels.ogiri.SessionPolicy;
import com.quantipixels.ogiri.Subject;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param enabled whether Ogiri auto-configuration is enabled
 * @param realm default identity namespace for immutable-username applications
 * @param lifetime fixed session lifetime
 * @param maximumSessions maximum live sessions per complete account identity
 * @param endpointsEnabled whether built-in JSON session endpoints are registered
 * @param basePath canonical base path for the built-in endpoints
 */
@ConfigurationProperties("ogiri")
public record OgiriProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("users") String realm,
        @DefaultValue("7d") Duration lifetime,
        @DefaultValue("10") int maximumSessions,
        @DefaultValue("true") boolean endpointsEnabled,
        @DefaultValue("/auth") String basePath) {
    public OgiriProperties {
        new Subject(realm, "", "validation");
        new SessionPolicy(lifetime, maximumSessions);
        if (basePath == null || !basePath.matches("/(?:[A-Za-z0-9_~-]+(?:/[A-Za-z0-9_~-]+)*)"))
            throw new IllegalArgumentException("ogiri.base-path must be a canonical literal absolute path");
    }
}
