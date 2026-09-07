// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Quanti Pixels
package com.quantipixels.ogiri.spring;

import com.quantipixels.ogiri.JdbcSessions;
import com.quantipixels.ogiri.Session;
import com.quantipixels.ogiri.SessionStoreException;
import com.quantipixels.ogiri.Subject;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.security.authentication.AccountStatusException;
import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;

/**
 * Connects Ogiri to Spring Security's native opaque-token resource server. The application supplies
 * a full-identity account loader and owns its security chain, login endpoints and CSRF policy.
 * Account state and authorities are loaded on every request, after credential verification.
 * The returned principal contains metadata, never the credential or stored digest.
 */
public final class OgiriOpaqueTokenIntrospector implements OpaqueTokenIntrospector {
    private final JdbcSessions sessions;
    private final Function<Subject, UserDetails> accounts;
    private final AccountStatusUserDetailsChecker status = new AccountStatusUserDetailsChecker();

    public OgiriOpaqueTokenIntrospector(JdbcSessions sessions, Function<Subject, UserDetails> accounts) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
    }

    @Override public OAuth2AuthenticatedPrincipal introspect(String token) {
        final Session session;
        try { session = sessions.authenticate(token).orElseThrow(() -> new BadOpaqueTokenException("Invalid session")); }
        catch (SessionStoreException failure) { throw new OAuth2IntrospectionException("Session store unavailable", failure); }
        final UserDetails account;
        try {
            account = accounts.apply(session.subject());
            if (account == null) throw new UsernameNotFoundException("Unknown account");
            status.check(account);
        } catch (UsernameNotFoundException | AccountStatusException unavailable) {
            throw new BadOpaqueTokenException("Invalid session");
        } catch (RuntimeException failure) {
            throw new OAuth2IntrospectionException("Account store unavailable", failure);
        }
        return new DefaultOAuth2AuthenticatedPrincipal(session.subject().subjectId(), Map.of(
                "sub", session.subject().subjectId(),
                "realm", session.subject().realm(),
                "tenant_id", session.subject().tenantId(),
                "session_id", session.id().toString(),
                "client", session.client(), "ogiri_session", session), java.util.List.copyOf(account.getAuthorities()));
    }
}
