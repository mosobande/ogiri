// SPDX-License-Identifier: Apache-2.0
package com.quantipixels.ogiri.spring;

import static org.junit.jupiter.api.Assertions.*;
import com.quantipixels.ogiri.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OAuth2IntrospectionException;

class OgiriOpaqueTokenIntrospectorTest {
    private static DriverManagerDataSource dataSource;
    private JdbcSessions sessions;
    private static final Subject OWNER = new Subject("users", "tenant-a", "stable-42");

    @BeforeAll static void database() throws Exception {
        dataSource = new DriverManagerDataSource(java.util.Objects.requireNonNull(System.getenv("OGIRI_TEST_JDBC_URL")),
                System.getenv("OGIRI_TEST_JDBC_USER"), System.getenv("OGIRI_TEST_JDBC_PASSWORD"));
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS ogiri_sessions");
            statement.execute("DROP TABLE IF EXISTS ogiri_subject_locks");
        }
        new ResourceDatabasePopulator(new ClassPathResource("META-INF/ogiri/schema-" + System.getenv("OGIRI_TEST_DATABASE") + ".sql")).execute(dataSource);
    }

    @BeforeEach void reset() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) { statement.execute("TRUNCATE ogiri_sessions"); }
        sessions = new JdbcSessions(dataSource);
    }

    @Test void principalCarriesStableScopedIdentityAndCurrentAuthoritiesButNoSecret() {
        var issued = sessions.issue(OWNER, "browser");
        var seen = new AtomicReference<Subject>();
        var adapter = new OgiriOpaqueTokenIntrospector(sessions, subject -> {
            seen.set(subject);
            return User.withUsername("renamable-login").password("unused").roles("ADMIN").build();
        });
        var principal = adapter.introspect(issued.token());
        assertEquals(OWNER, seen.get());
        assertEquals("stable-42", principal.getName());
        assertEquals("tenant-a", principal.getAttribute("tenant_id"));
        assertEquals("users", principal.getAttribute("realm"));
        assertEquals(issued.session().id().toString(), principal.getAttribute("session_id"));
        assertTrue(principal.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
        assertFalse(principal.getAttributes().toString().contains(issued.token()));
        assertFalse(principal.getAttributes().containsKey("token_hash"));
    }

    @Test void disabledLockedExpiredAndMissingAccountsFailClosedWithoutCachingState() {
        var token = sessions.issue(OWNER, "phone").token();
        var current = new AtomicReference<UserDetails>(User.withUsername("login").password("unused").roles("USER").build());
        var adapter = new OgiriOpaqueTokenIntrospector(sessions, subject -> current.get());
        assertNotNull(adapter.introspect(token));
        for (UserDetails disallowed : java.util.List.of(
                User.withUsername("login").password("unused").roles("USER").disabled(true).build(),
                User.withUsername("login").password("unused").roles("USER").accountLocked(true).build(),
                User.withUsername("login").password("unused").roles("USER").accountExpired(true).build(),
                User.withUsername("login").password("unused").roles("USER").credentialsExpired(true).build())) {
            current.set(disallowed);
            assertThrows(BadOpaqueTokenException.class, () -> adapter.introspect(token));
        }
        current.set(null);
        assertThrows(BadOpaqueTokenException.class, () -> adapter.introspect(token));
        current.set(User.withUsername("new-login").password("unused").roles("ADMIN").build());
        assertTrue(adapter.introspect(token).getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
    }

    @Test void invalidCredentialsDoNotQueryAccountsAndOutagesAreNotInvalidTokenErrors() {
        var calls = new AtomicInteger();
        var adapter = new OgiriOpaqueTokenIntrospector(sessions, subject -> { calls.incrementAndGet(); throw new IllegalStateException("directory offline"); });
        assertThrows(BadOpaqueTokenException.class, () -> adapter.introspect("bad"));
        assertEquals(0, calls.get());
        var token = sessions.issue(OWNER, "phone").token();
        var outage = assertThrows(OAuth2IntrospectionException.class, () -> adapter.introspect(token));
        assertFalse(outage instanceof BadOpaqueTokenException);
        assertEquals(1, calls.get());
        assertInstanceOf(IllegalStateException.class, outage.getCause());
    }
}
