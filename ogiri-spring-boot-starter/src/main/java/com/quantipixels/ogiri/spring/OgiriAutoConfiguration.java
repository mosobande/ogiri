// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Quanti Pixels
package com.quantipixels.ogiri.spring;


import com.quantipixels.ogiri.JdbcSessions;
import com.quantipixels.ogiri.SessionPolicy;
import com.quantipixels.ogiri.Subject;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/** Boot owns discovery, configuration binding, pooling and native Security integration. */
@AutoConfiguration(
        afterName = {"org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
                "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
                "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"},
        beforeName = {"org.springframework.boot.security.autoconfigure.servlet.SecurityAutoConfiguration",
                "org.springframework.boot.security.oauth2.server.resource.autoconfigure.servlet.OAuth2ResourceServerAutoConfiguration"})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "ogiri", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(OgiriProperties.class)
@EnableWebSecurity
public final class OgiriAutoConfiguration {
    @Bean @ConditionalOnMissingBean
    SessionPolicy ogiriPolicy(OgiriProperties properties) {
        return new SessionPolicy(properties.lifetime(), properties.maximumSessions());
    }

    @Bean @ConditionalOnMissingBean
    JdbcSessions ogiriSessions(DataSource source, SessionPolicy policy,
            org.springframework.beans.factory.ObjectProvider<org.springframework.transaction.PlatformTransactionManager> managers) {
        var manager = managers.getIfAvailable();
        return manager == null ? new JdbcSessions(source, policy) : new JdbcSessions(source, policy, manager);
    }

    @Bean @ConditionalOnMissingBean
    PasswordEncoder ogiriPasswordEncoder() { return PasswordEncoderFactories.createDelegatingPasswordEncoder(); }

    @Bean @ConditionalOnMissingBean
    OgiriAccounts ogiriAccounts(UserDetailsService users, OgiriProperties properties) {
        return new OgiriAccounts() {
            public Subject subject(Authentication authentication) {
                return new Subject(properties.realm(), "", authentication.getName());
            }
            public UserDetails load(Subject subject) {
                if (!subject.realm().equals(properties.realm()) || !subject.tenantId().isEmpty())
                    throw new UsernameNotFoundException("Account outside default identity namespace");
                return users.loadUserByUsername(subject.subjectId());
            }
        };
    }

    @Bean @ConditionalOnMissingBean
    OgiriOpaqueTokenIntrospector ogiriIntrospector(JdbcSessions sessions, OgiriAccounts accounts) {
        return new OgiriOpaqueTokenIntrospector(sessions, accounts::load);
    }

    @Bean @ConditionalOnMissingBean
    OgiriSecurity ogiriSecurity(OgiriOpaqueTokenIntrospector introspector, OgiriProperties properties) {
        return new OgiriSecurity(introspector, properties);
    }

    @Bean @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ogiri", name = "endpoints-enabled", havingValue = "true", matchIfMissing = true)
    OgiriEndpoints ogiriEndpoints(JdbcSessions sessions, OgiriAccounts accounts,
            org.springframework.beans.factory.ObjectProvider<AuthenticationManager> managers,
            AuthenticationConfiguration configuration) throws Exception {
        var manager = managers.getIfAvailable();
        if (manager == null) manager = configuration.getAuthenticationManager();
        if (manager == null) throw new IllegalStateException("Provide a UserDetailsService, AuthenticationProvider or AuthenticationManager");
        return new OgiriEndpoints(sessions, accounts, manager);
    }

    @Bean @ConditionalOnMissingBean(SecurityFilterChain.class)
    SecurityFilterChain ogiriSecurityFilterChain(HttpSecurity http, OgiriSecurity security) throws Exception {
        return security.defaults(http);
    }
}
