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
import org.postgresql.ds.PGSimpleDataSource;

class PostgresSessionsTest {
    private static PGSimpleDataSource dataSource;
    private static final Subject OWNER = new Subject("users", "tenant-a", "user-42");
    private PostgresSessions sessions;

    @BeforeAll static void database() throws Exception {
        dataSource = source();
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement();
             var schema = PostgresSessions.class.getResourceAsStream("/META-INF/ogiri/schema-postgresql.sql")) {
            assertNotNull(schema);
            statement.execute("DROP TABLE IF EXISTS ogiri_sessions");
            statement.execute(new String(schema.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static PGSimpleDataSource source() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(java.util.Objects.requireNonNull(System.getenv("OGIRI_TEST_JDBC_URL"), "Set OGIRI_TEST_JDBC_URL to a disposable PostgreSQL database"));
        source.setUser(System.getenv("OGIRI_TEST_JDBC_USER"));
        source.setPassword(System.getenv("OGIRI_TEST_JDBC_PASSWORD"));
        source.setConnectTimeout(3);
        source.setSocketTimeout(10);
        return source;
    }

    @BeforeEach void reset() throws Exception {
        sql("TRUNCATE ogiri_sessions");
        sessions = new PostgresSessions(dataSource);
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
        PGSimpleDataSource unavailable = source();
        unavailable.setPortNumbers(new int[]{1});
        var offline = new PostgresSessions(unavailable);
        for (String token : new String[]{"", "og1_", "og1_" + "A".repeat(42), "og1_" + "A".repeat(44), "og1_" + "A".repeat(42) + "B", "og1_" + "!".repeat(43), "Bearer " + "A".repeat(43), "x".repeat(100_000)}) {
            assertTrue(offline.authenticate(token).isEmpty(), "Malformed credential must fail before I/O");
        }
        assertTrue(offline.authenticate(null).isEmpty());
        assertThrows(SessionStoreException.class, () -> offline.authenticate("og1_" + "A".repeat(43)));
    }

    @Test void authenticationDoesNotWriteOrSlideExpiryAndExpiredRowsCannotAuthenticate() throws Exception {
        var issued = sessions.issue(OWNER, "browser");
        String before = scalar("SELECT xmin::text FROM ogiri_sessions");
        for (int i = 0; i < 5; i++) assertEquals(issued.session(), sessions.authenticate(issued.token()).orElseThrow());
        assertEquals(before, scalar("SELECT xmin::text FROM ogiri_sessions"), "Read authentication must not create new row versions");
        sql("UPDATE ogiri_sessions SET created_at = '2000-01-01Z', expires_at = '2000-01-02Z'");
        assertTrue(sessions.authenticate(issued.token()).isEmpty());
        assertTrue(sessions.list(OWNER).isEmpty());
    }

    @Test void managementUsesEveryIdentityComponentAndNeverTrustsTheSessionIdAlone() {
        var a = sessions.issue(OWNER, "a");
        for (Subject foreign : List.of(new Subject("admins", "tenant-a", "user-42"), new Subject("users", "tenant-b", "user-42"), new Subject("users", "Tenant-a", "user-42"), new Subject("users", "tenant-a", "USER-42"), new Subject("users", "tenant-a", "other"), new Subject("users", "", "user-42"))) {
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
        var limited = new PostgresSessions(dataSource, new SessionPolicy(Duration.ofHours(1), 1));
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
                            new PostgresSessions(dataSource, new SessionPolicy(Duration.ofHours(1), 2)).issue(subject, "parallel");
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
        sql("UPDATE ogiri_sessions SET created_at = '2000-01-01Z', expires_at = '2000-01-02Z'");
        var live = sessions.issue(OWNER, "live");
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement(); var rows = statement.executeQuery("SELECT id FROM ogiri_sessions WHERE expires_at < now() ORDER BY expires_at, id LIMIT 1 FOR UPDATE")) {
                assertTrue(rows.next());
                UUID locked = rows.getObject(1, UUID.class);
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
        PGSimpleDataSource failing = new PGSimpleDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection actual = dataSource.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("commit")) throw new SQLException("simulated pre-commit failure");
                    try { return method.invoke(actual, args); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
            }
        };
        assertThrows(SessionStoreException.class, () -> new PostgresSessions(failing).issue(OWNER, "phone"));
        assertTrue(sessions.list(OWNER).isEmpty());
        assertTrue(sessions.authenticate(sessions.issue(OWNER, "retry").token()).isPresent());
    }

    @Test void callerOwnedTransactionsAreRejectedForAuthenticationAndMutation() throws Exception {
        var valid = sessions.issue(OWNER, "existing");
        PGSimpleDataSource enlisted = new PGSimpleDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection connection = dataSource.getConnection();
                connection.setAutoCommit(false);
                return connection;
            }
        };
        var invalid = new PostgresSessions(enlisted);
        assertThrows(SessionStoreException.class, () -> invalid.authenticate(valid.token()));
        assertThrows(SessionStoreException.class, () -> invalid.list(OWNER));
        assertThrows(SessionStoreException.class, () -> invalid.issue(OWNER, "new"));
        assertEquals(List.of(valid.session()), sessions.list(OWNER));
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

    private static void sql(String sql) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) { statement.execute(sql); }
    }
    private static String scalar(String sql) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) { rows.next(); return rows.getString(1); }
    }
}
