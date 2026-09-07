// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Quanti Pixels
package com.quantipixels.ogiri;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Thread-safe PostgreSQL opaque-session lifecycle. Supply an ordinary pool, not a transaction-bound
 * proxy. Mutations use independent committed transactions. Authentication is a read-only lookup.
 * Pool acquisition/network timeouts remain the application's responsibility; each SQL statement
 * has a five-second query timeout. Schema creation and scheduling are explicitly application-owned.
 */
public final class PostgresSessions {
    private static final String COLUMNS = "id, realm, tenant_id, subject_id, client, created_at, expires_at";
    private static final String OWNER = "realm = ? AND tenant_id = ? AND subject_id = ?";
    private final DataSource dataSource;
    private final SessionPolicy policy;

    public PostgresSessions(DataSource dataSource) { this(dataSource, SessionPolicy.defaults()); }

    public PostgresSessions(DataSource dataSource, SessionPolicy policy) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /** Issue only after the application authenticates and authorizes the complete Subject. */
    public IssuedSession issue(Subject subject, String client) {
        Objects.requireNonNull(subject, "subject");
        if (client == null || client.isBlank() || client.length() > 255 || client.indexOf(0) >= 0)
            throw new IllegalArgumentException("client must contain 1 to 255 characters");
        String token = Tokens.generate();
        return write(connection -> {
            lock(connection, subject);
            Instant now;
            try (PreparedStatement statement = prepare(connection, "SELECT statement_timestamp()")) {
                try (ResultSet rows = statement.executeQuery()) { rows.next(); now = rows.getObject(1, OffsetDateTime.class).toInstant(); }
            }
            try (PreparedStatement statement = prepare(connection,
                    "SELECT count(*) FROM ogiri_sessions WHERE " + OWNER + " AND expires_at > ?")) {
                owner(statement, subject); instant(statement, 4, now);
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    if (rows.getInt(1) >= policy.maximumSessions()) throw new SessionLimitException();
                }
            }
            Session session = new Session(UUID.randomUUID(), subject, client, now, now.plusMillis(policy.lifetime().toMillis()));
            try (PreparedStatement statement = prepare(connection,
                    "INSERT INTO ogiri_sessions (" + COLUMNS + ", token_hash) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                statement.setObject(1, session.id());
                statement.setString(2, subject.realm()); statement.setString(3, subject.tenantId()); statement.setString(4, subject.subjectId());
                statement.setString(5, client); instant(statement, 6, now); instant(statement, 7, session.expiresAt());
                statement.setBytes(8, Tokens.digest(token)); statement.executeUpdate();
            }
            return new IssuedSession(session, token);
        });
    }

    /** Find a valid credential at the database statement's start; malformed, expired and revoked tokens return empty. */
    public Optional<Session> authenticate(String token) {
        byte[] digest = Tokens.digest(token);
        if (digest == null) return Optional.empty();
        try (Connection connection = connection();
                PreparedStatement statement = prepare(connection,
                    "SELECT " + COLUMNS + " FROM ogiri_sessions WHERE token_hash = ? AND expires_at > statement_timestamp()")) {
            statement.setBytes(1, digest);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? Optional.of(session(rows)) : Optional.empty(); }
        } catch (SQLException failure) { throw new SessionStoreException(failure); }
    }

    /** List live metadata for an application-authorized owner, in creation order. Never use a request-supplied owner unchecked. */
    public List<Session> list(Subject subject) {
        Objects.requireNonNull(subject, "subject");
        try (Connection connection = connection();
                PreparedStatement statement = prepare(connection,
                    "SELECT " + COLUMNS + " FROM ogiri_sessions WHERE " + OWNER + " AND expires_at > statement_timestamp() ORDER BY created_at, id")) {
            owner(statement, subject);
            try (ResultSet rows = statement.executeQuery()) {
                List<Session> sessions = new ArrayList<>();
                while (rows.next()) sessions.add(session(rows));
                return List.copyOf(sessions);
            }
        } catch (SQLException failure) { throw new SessionStoreException(failure); }
    }

    /** Revoke one owned session. A foreign or already absent identifier returns false. */
    public boolean revoke(Subject subject, UUID sessionId) {
        Objects.requireNonNull(subject, "subject"); Objects.requireNonNull(sessionId, "sessionId");
        return write(connection -> {
            try (PreparedStatement statement = prepare(connection, "DELETE FROM ogiri_sessions WHERE " + OWNER + " AND id = ?")) {
                owner(statement, subject); statement.setObject(4, sessionId);
                return statement.executeUpdate() == 1;
            }
        });
    }

    /** Serialize with issuance and remove all this owner's sessions. Later sign-ins remain possible. */
    public int revokeAll(Subject subject) {
        Objects.requireNonNull(subject, "subject");
        return write(connection -> {
            lock(connection, subject);
            try (PreparedStatement statement = prepare(connection, "DELETE FROM ogiri_sessions WHERE " + OWNER)) {
                owner(statement, subject); return statement.executeUpdate();
            }
        });
    }

    /** Delete at most batchSize expired rows. Concurrent workers skip locked rows; no leader election is needed. */
    public int cleanup(int batchSize) {
        if (batchSize < 1 || batchSize > 10_000) throw new IllegalArgumentException("batchSize must be between 1 and 10000");
        return write(connection -> {
            try (PreparedStatement statement = prepare(connection, """
                    WITH expired AS (
                        SELECT id FROM ogiri_sessions WHERE expires_at <= statement_timestamp()
                        ORDER BY expires_at, id LIMIT ? FOR UPDATE SKIP LOCKED
                    )
                    DELETE FROM ogiri_sessions AS sessions USING expired WHERE sessions.id = expired.id
                    """)) {
                statement.setInt(1, batchSize); return statement.executeUpdate();
            }
        });
    }

    private <T> T write(SqlWork<T> work) {
        try (Connection connection = connection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException | Error failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        } catch (SQLException failure) { throw new SessionStoreException(failure); }
    }

    private Connection connection() throws SQLException {
        Connection connection = dataSource.getConnection();
        try {
            if (!connection.getAutoCommit()) throw new SQLException("DataSource must supply auto-commit connections");
            return connection;
        } catch (SQLException failure) {
            try { connection.close(); } catch (SQLException close) { failure.addSuppressed(close); }
            throw failure;
        }
    }

    private static PreparedStatement prepare(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try { statement.setQueryTimeout(5); return statement; }
        catch (SQLException failure) { statement.close(); throw failure; }
    }

    private static void lock(Connection connection, Subject subject) throws SQLException {
        try (PreparedStatement statement = prepare(connection, "SELECT pg_advisory_xact_lock(?)")) {
            statement.setLong(1, Tokens.lockKey(subject)); statement.execute();
        }
    }

    private static void owner(PreparedStatement statement, Subject subject) throws SQLException {
        statement.setString(1, subject.realm()); statement.setString(2, subject.tenantId()); statement.setString(3, subject.subjectId());
    }

    private static void instant(PreparedStatement statement, int index, Instant time) throws SQLException {
        statement.setObject(index, time.atOffset(ZoneOffset.UTC));
    }

    private static Session session(ResultSet rows) throws SQLException {
        return new Session(rows.getObject("id", UUID.class),
                new Subject(rows.getString("realm"), rows.getString("tenant_id"), rows.getString("subject_id")),
                rows.getString("client"), rows.getObject("created_at", OffsetDateTime.class).toInstant(),
                rows.getObject("expires_at", OffsetDateTime.class).toInstant());
    }

    @FunctionalInterface private interface SqlWork<T> { T apply(Connection connection) throws SQLException; }
}
