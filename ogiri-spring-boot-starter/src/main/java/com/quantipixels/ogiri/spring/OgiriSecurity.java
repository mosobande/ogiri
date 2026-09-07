// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Quanti Pixels
package com.quantipixels.ogiri.spring;


import jakarta.servlet.DispatcherType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.BearerTokenErrors;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;

/** Reusable native resource-server configuration; safe to use with multiple application-owned chains. */
public final class OgiriSecurity {
    private final OgiriOpaqueTokenIntrospector introspector;
    private final OgiriProperties properties;

    public OgiriSecurity(OgiriOpaqueTokenIntrospector introspector, OgiriProperties properties) {
        this.introspector = introspector; this.properties = properties;
    }

    /** Add bearer authentication only. Authorization, other mechanisms and login CSRF policy remain yours. */
    public HttpSecurity configure(HttpSecurity http) throws Exception {
        var nativeResolver = new DefaultBearerTokenResolver();
        return http.oauth2ResourceServer(resource -> resource
                .bearerTokenResolver(request -> {
                    var values = request.getHeaders(HttpHeaders.AUTHORIZATION);
                    if (values.hasMoreElements()) {
                        values.nextElement();
                        if (values.hasMoreElements()) throw new OAuth2AuthenticationException(
                                BearerTokenErrors.invalidRequest("Multiple Authorization headers"));
                    }
                    return nativeResolver.resolve(request);
                })
                .opaqueToken(opaque -> opaque.introspector(introspector)));
    }

    /** Only non-simple JSON sign-in; use for explicit CSRF exemptions in an application-owned chain. */
    public RequestMatcher signInRequest() {
        return request -> properties.endpointsEnabled()
                && request.getMethod().equals("POST")
                && request.getServletPath().equals(properties.basePath() + "/sign-in")
                && "Ogiri".equals(request.getHeader("X-Requested-With"))
                && request.getContentType() != null
                && request.getContentType().split(";", 2)[0].trim().equalsIgnoreCase(MediaType.APPLICATION_JSON_VALUE);
    }

    SecurityFilterChain defaults(HttpSecurity http) throws Exception {
        configure(http);
        return http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(routes -> {
                    routes.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
                    if (properties.endpointsEnabled()) routes.requestMatchers(signInRequest()).permitAll();
                    routes.anyRequest().authenticated();
                })
                .csrf(csrf -> csrf.ignoringRequestMatchers(signInRequest()))
                .build();
    }
}
