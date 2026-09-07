// SPDX-License-Identifier: Apache-2.0
package com.quantipixels.ogiri;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;

class JdbcSessionsTest {
    private static DriverManagerDataSource dataSource;
    private static final Subject OWNER = new Subject("users", "tenant-a", "user-42");
    private JdbcSessions sessions;

    @BeforeAll static void database() throws Exception {
        dataSource = source();
        sql("DROP TABLE IF EXISTS ogiri_sessions");
        sql("DROP TABLE IF EXISTS ogiri_subject_locks");
        String vendor = System.getenv("OGIRI_TEST_DATABASE");
        if (!java.util.Set.of("postgresql", "mysql").contains(vendor)) throw new IllegalArgumentException("Set OGIRI_TEST_DATABASE");
        new ResourceDatabasePopulator(new ClassPathResource("META-INF/ogiri/schema-" + vendor + ".sql")).execute(dataSource);
    }

    private static DriverManagerDataSource source() {
        return new DriverManagerDataSource(java.util.Objects.requireNonNull(System.getenv("OGIRI_TEST_JDBC_URL"), "Disposable database required"),
                System.getenv("OGIRI_TEST_JDBC_USER"), System.getenv("OGIRI_TEST_JDBC_PASSWORD"));
    }

    @BeforeEach void reset() throws Exception {
        sql("TRUNCATE ogiri_sessions");
        sessions = new JdbcSessions(dataSource);
    }

    @Test void issuanceRoundTripsWithoutPersistingOrPrintingTheCredential() throws Exception {
        var issued = sessions.issue(OWNER, "phone");
        assertEquals(47, issued.token().length());
        assertEquals(issued.session(), sessions.authenticate(issued.token()).orElseThrow());
        assertTrue(sessions.authenticate(issued.session().id().toString()).isEmpty());
        assertFalse(issued.toString().contains(issued.token()));
        assertFalse(issued.session().toString().contains(issued.token()));
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT token_hash, octet_length(token_hash), expires_at - created_at FROM ogiri_sessions")) {
            assertTrue(rows.next());
            assertEquals(32, rows.getInt(2));
            assertArrayEquals(java.security.MessageDigest.getInstance("SHA-256").digest(issued.token().getBytes(StandardCharsets.US_ASCII)), rows.getBytes(1));
        }
        assertEquals(Duration.ofDays(7), Duration.between(issued.session().createdAt(), issued.session().expiresAt()));
    }

    @Test void malformedAndNonCanonicalCredentialsNeverBecomeDatabaseLookups() {
        var unavailable = new java.util.concurrent.atomic.AtomicBoolean(false);
        var faultable = new DelegatingDataSource(dataSource) {
            @Override public Connection getConnection() throws SQLException {
                if (unavailable.get()) throw new SQLException("controlled unavailable store");
                return super.getConnection();
            }
        };
        var offline = new JdbcSessions(faultable);
        unavailable.set(true);
        for (String token : new String[]{"", "og1_", "og1_" + "A".repeat(42), "og1_" + "A".repeat(44), "og1_" + "A".repeat(42) + "B", "og1_" + "!".repeat(43), "Bearer " + "A".repeat(43), "x".repeat(100_000)}) {
            assertTrue(offline.authenticate(token).isEmpty(), "Malformed credential must fail before I/O");
        }
        assertTrue(offline.authenticate(null).isEmpty());
        assertThrows(SessionStoreException.class, () -> offline.authenticate("og1_" + "A".repeat(43)));
    }

    @Test void authenticationDoesNotWriteOrSlideExpiryAndExpiredRowsCannotAuthenticate() throws Exception {
        var issued = sessions.issue(OWNER, "browser");
        String before = scalar("SELECT expires_at FROM ogiri_sessions");
        var readOnly = new DelegatingDataSource(dataSource) {
            @Override public Connection getConnection() throws SQLException {
                Connection actual = super.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("prepareStatement") && !((String) args[0]).stripLeading().startsWith("SELECT"))
                        throw new AssertionError("Authentication attempted a non-read statement");
                    try { return method.invoke(actual, args); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
            }
        };
        var reader = new JdbcSessions(readOnly);
        for (int i = 0; i < 5; i++) assertEquals(issued.session(), reader.authenticate(issued.token()).orElseThrow());
        assertEquals(before, scalar("SELECT expires_at FROM ogiri_sessions"), "Read authentication must not create new row versions");
        sql("UPDATE ogiri_sessions SET created_at = 1, expires_at = 2");
        assertTrue(sessions.authenticate(issued.token()).isEmpty());
        assertTrue(sessions.list(OWNER).isEmpty());
    }

    @Test void managementUsesEveryIdentityComponentAndNeverTrustsTheSessionIdAlone() {
        var a = sessions.issue(OWNER, "a");
        for (Subject foreign : List.of(new Subject("admins", "tenant-a", "user-42"), new Subject("users", "tenant-b", "user-42"), new Subject("users", "Tenant-a", "user-42"), new Subject("users", "tenant-a", "USER-42"), new Subject("users", "tenant-a ", "user-42"), new Subject("users", "tenant-a", "other"), new Subject("users", "", "user-42"))) {
            var b = sessions.issue(foreign, "b");
            assertEquals(List.of(b.session()), sessions.list(foreign));
            assertFalse(sessions.revoke(foreign, a.session().id()));
            assertEquals(1, sessions.revokeAll(foreign));
            assertTrue(sessions.authenticate(a.token()).isPresent());
        }
        var unusual = new Subject("users", "tenant:a", "x' OR '1'='1");
        var c = sessions.issue(unusual, "device");
        assertEquals(List.of(c.session()), sessions.list(unusual));
        assertEquals(List.of(a.session()), sessions.list(OWNER));
    }

    @Test void revocationIsImmediateIdempotentAndDoesNotBanFutureSignIns() {
        var first = sessions.issue(OWNER, "phone");
        var second = sessions.issue(OWNER, "laptop");
        assertTrue(sessions.revoke(OWNER, first.session().id()));
        assertFalse(sessions.revoke(OWNER, first.session().id()));
        assertTrue(sessions.authenticate(first.token()).isEmpty());
        assertTrue(sessions.authenticate(second.token()).isPresent());
        assertEquals(1, sessions.revokeAll(OWNER));
        assertEquals(0, sessions.revokeAll(OWNER));
        assertTrue(sessions.authenticate(second.token()).isEmpty());
        assertTrue(sessions.authenticate(sessions.issue(OWNER, "new login").token()).isPresent());
    }

    @Test void admissionRejectsRatherThanEvictingAndRevocationFreesCapacity() {
        var limited = new JdbcSessions(dataSource, new SessionPolicy(Duration.ofHours(1), 1));
        var first = limited.issue(OWNER, "phone");
        assertThrows(SessionLimitException.class, () -> limited.issue(OWNER, "laptop"));
        assertTrue(limited.authenticate(first.token()).isPresent());
        assertEquals(List.of(first.session()), limited.list(OWNER));
        limited.revoke(OWNER, first.session().id());
        assertTrue(limited.authenticate(limited.issue(OWNER, "laptop").token()).isPresent());
    }

    @Test void concurrentFirstSignInsCannotExceedTheAccountLimit() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            for (int round = 0; round < 4; round++) {
                var subject = new Subject("race", "", "account-" + round);
                var start = new CyclicBarrier(8);
                List<Future<Boolean>> results = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    results.add(workers.submit(() -> {
                        start.await(5, TimeUnit.SECONDS);
                        try {
                            new JdbcSessions(dataSource, new SessionPolicy(Duration.ofHours(1), 2)).issue(subject, "parallel");
                            return true;
                        } catch (SessionLimitException expected) { return false; }
                    }));
                }
                int accepted = 0;
                for (var result : results) if (result.get(15, TimeUnit.SECONDS)) accepted++;
                assertEquals(2, accepted);
                assertEquals(2, sessions.list(subject).size());
            }
        } finally { workers.shutdownNow(); assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    @Test void cleanupIsBoundedSkipsLockedRowsAndPreservesLiveSessions() throws Exception {
        for (int i = 0; i < 4; i++) sessions.issue(OWNER, "expired");
        sql("UPDATE ogiri_sessions SET created_at = 1, expires_at = 2");
        var live = sessions.issue(OWNER, "live");
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement(); var rows = statement.executeQuery("SELECT id FROM ogiri_sessions WHERE expires_at = 2 ORDER BY expires_at, id LIMIT 1 FOR UPDATE")) {
                assertTrue(rows.next());
                UUID locked = UUID.fromString(rows.getString(1));
                assertEquals(2, sessions.cleanup(2));
                assertEquals(3, Integer.parseInt(scalar("SELECT count(*) FROM ogiri_sessions")));
                assertEquals(1, sessions.cleanup(10));
                assertEquals(1, Integer.parseInt(scalar("SELECT count(*) FROM ogiri_sessions WHERE id = '" + locked + "'")));
            }
            blocker.rollback();
        }
        assertEquals(1, sessions.cleanup(10));
        assertEquals(0, sessions.cleanup(10));
        assertEquals(List.of(live.session()), sessions.list(OWNER));
        assertTrue(sessions.authenticate(live.token()).isPresent());
    }

    @Test void failedCommitRollsBackAndNeverReturnsACredential() {
        DelegatingDataSource failing = new DelegatingDataSource(dataSource) {
            @Override public Connection getConnection() throws SQLException {
                Connection actual = dataSource.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("commit")) throw new SQLException("simulated pre-commit failure");
                    try { return method.invoke(actual, args); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
            }
        };
        assertThrows(SessionStoreException.class, () -> new JdbcSessions(failing).issue(OWNER, "phone"));
        assertTrue(sessions.list(OWNER).isEmpty());
        assertTrue(sessions.authenticate(sessions.issue(OWNER, "retry").token()).isPresent());
    }

    @Test void springTransactionsDoNotUndoCommittedSessionChangesOrCacheRevocation() {
        var outer = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.support.JdbcTransactionManager(dataSource));
        outer.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        var issued = outer.execute(status -> {
            jdbc.queryForObject("SELECT count(*) FROM ogiri_sessions", Integer.class);
            var fresh = sessions.issue(OWNER, "committed independently");
            assertTrue(sessions.authenticate(fresh.token()).isPresent());
            assertTrue(sessions.revoke(OWNER, fresh.session().id()));
            assertTrue(sessions.authenticate(fresh.token()).isEmpty());
            var survivor = sessions.issue(OWNER, "survives outer rollback");
            status.setRollbackOnly();
            return survivor;
        });
        assertTrue(sessions.authenticate(issued.token()).isPresent());
        assertEquals(List.of(issued.session()), sessions.list(OWNER));
    }

    @Test void invalidResourceBoundsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SessionPolicy(Duration.ZERO, 1));
        assertThrows(IllegalArgumentException.class, () -> new SessionPolicy(Duration.ofDays(366), 1));
        assertThrows(IllegalArgumentException.class, () -> new SessionPolicy(Duration.ofDays(1), 0));
        assertThrows(IllegalArgumentException.class, () -> sessions.cleanup(10_001));
        assertThrows(IllegalArgumentException.class, () -> sessions.issue(OWNER, ""));
        assertThrows(IllegalArgumentException.class, () -> new Subject("users", "", "x\0y"));
        assertThrows(IllegalArgumentException.class, () -> new Subject("users", "", String.valueOf((char) 0xD800)));
    }


    @Test void cachedReadersAvoidDatabaseIoAndSharedEvictionTargetsOnlyRevokedSessions() {
        var manager = new org.springframework.cache.concurrent.ConcurrentMapCacheManager("shared");
        manager.setStoreByValue(true);
        var region = java.util.Objects.requireNonNull(manager.getCache("shared"));
        var offline = new java.util.concurrent.atomic.AtomicBoolean(false);
        var source = new DelegatingDataSource(dataSource) {
            @Override public Connection getConnection() throws SQLException {
                if (offline.get()) throw new SQLException("controlled database outage");
                return super.getConnection();
            }
        };
        var reader = new JdbcSessions(source, SessionPolicy.defaults(),
                new org.springframework.jdbc.support.JdbcTransactionManager(source), new SessionCache(region, Duration.ofMinutes(1)));
        var writer = new JdbcSessions(dataSource, SessionPolicy.defaults(),
                new org.springframework.jdbc.support.JdbcTransactionManager(dataSource), new SessionCache(region, Duration.ofMinutes(1)));
        var first = writer.issue(OWNER, "first");
        var second = writer.issue(OWNER, "second");
        var foreign = writer.issue(new Subject("users", "other-tenant", "user-42"), "foreign");
        for (var issued : List.of(first, second, foreign)) assertEquals(issued.session(), reader.authenticate(issued.token()).orElseThrow());
        offline.set(true);
        assertEquals(first.session(), reader.authenticate(first.token()).orElseThrow(), "Hit must avoid database I/O");
        assertTrue(reader.authenticate("malformed").isEmpty());
        assertThrows(SessionStoreException.class, () -> reader.authenticate("og1_" + "A".repeat(43)));
        assertFalse(writer.revoke(foreign.session().subject(), first.session().id()));
        assertEquals(first.session(), reader.authenticate(first.token()).orElseThrow());
        assertTrue(writer.revoke(OWNER, first.session().id()));
        assertThrows(SessionStoreException.class, () -> reader.authenticate(first.token()), "Revocation evicts across readers sharing the region");
        assertEquals(second.session(), reader.authenticate(second.token()).orElseThrow());
        assertEquals(1, writer.revokeAll(OWNER));
        assertThrows(SessionStoreException.class, () -> reader.authenticate(second.token()));
        assertEquals(foreign.session(), reader.authenticate(foreign.token()).orElseThrow(), "Other owners are not evicted");
        offline.set(false);
        assertTrue(reader.authenticate(first.token()).isEmpty());
        assertTrue(reader.authenticate(second.token()).isEmpty());
    }

    @Test void cacheEvictionOccursAfterCommitAndItsFailureCannotUndoRevocation() {
        var issued = sessions.issue(OWNER, "phone");
        var region = new org.springframework.cache.concurrent.ConcurrentMapCache("failure") {
            @Override public boolean evictIfPresent(Object key) {
                assertTrue(sessions.authenticate(issued.token()).isEmpty(), "Another connection must see the committed revocation");
                throw new IllegalStateException("controlled cache eviction failure");
            }
        };
        var cached = new JdbcSessions(dataSource, SessionPolicy.defaults(),
                new org.springframework.jdbc.support.JdbcTransactionManager(dataSource), new SessionCache(region, Duration.ofSeconds(5)));
        assertEquals(issued.session(), cached.authenticate(issued.token()).orElseThrow());
        assertTrue(cached.revoke(OWNER, issued.session().id()));
        assertTrue(sessions.authenticate(issued.token()).isEmpty());
    }

    private static void sql(String sql) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) { statement.execute(sql); }
    }
    private static String scalar(String sql) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) { rows.next(); return rows.getString(1); }
    }
}
