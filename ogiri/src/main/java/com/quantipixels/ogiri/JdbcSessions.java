// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Quanti Pixels
package com.quantipixels.ogiri;


import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Thread-safe PostgreSQL/MySQL session lifecycle using Spring JDBC and transaction management.
 * Uses the supplied native DataSource, never a transaction-aware or replica-routing proxy.
 * Mutations commit independently; reads suspend a caller transaction to avoid stale snapshots.
 * SQL has a five-second timeout. Pool acquisition and network timeouts are host configuration.
 */
public final class JdbcSessions {
    private static final String COLUMNS = "id, realm, tenant_id, subject_id, client, created_at, expires_at";
    private static final String OWNER = "realm = ? AND tenant_id = ? AND subject_id = ?";
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final TransactionTemplate mutations;
    private final TransactionTemplate reads;
    private final Database database;
    private final SessionPolicy policy;
    private final SessionCache cache;

    public JdbcSessions(DataSource source) { this(source, SessionPolicy.defaults()); }

    public JdbcSessions(DataSource source, SessionPolicy policy) {
        this(source, policy, jdbcTransactions(source));
    }

    /** Use the application's transaction manager for this DataSource, including JPA-backed hosts. */
    public JdbcSessions(DataSource source, SessionPolicy policy, PlatformTransactionManager manager) {
        this(source, policy, manager, null);
    }

    /** Opt into bounded-staleness lookups; a null cache preserves authoritative reads. */
    public JdbcSessions(DataSource source, SessionPolicy policy, PlatformTransactionManager manager, SessionCache cache) {
        this.cache = cache;
        this.policy = Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(source, "source");
        if (source instanceof org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy)
            throw new IllegalArgumentException("Supply the underlying DataSource, not TransactionAwareDataSourceProxy");
        this.database = Database.detect(source);
        this.jdbc = new JdbcTemplate(source);
        this.jdbc.setQueryTimeout(5);
        this.named = new NamedParameterJdbcTemplate(jdbc);
        Objects.requireNonNull(manager, "manager");
        this.mutations = new TransactionTemplate(manager);
        mutations.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        mutations.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        mutations.setTimeout(10);
        this.reads = new TransactionTemplate(manager);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    private static JdbcTransactionManager jdbcTransactions(DataSource source) {
        var manager = new JdbcTransactionManager(source);
        manager.setRollbackOnCommitFailure(true);
        return manager;
    }

    /** Issue only after the caller has authenticated and authorized the full subject. */
    public IssuedSession issue(Subject subject, String client) {
        Objects.requireNonNull(subject, "subject");
        if (client == null || client.isBlank() || client.length() > 255 || client.indexOf(0) >= 0)
            throw new IllegalArgumentException("client must contain 1 to 255 characters");
        String token = Tokens.generate();
        return write(() -> {
            lock(subject);
            // Read database time after waiting for admission, not at transaction start.
            long now = jdbc.queryForObject("SELECT " + database.now, Long.class);
            int count = jdbc.queryForObject("SELECT count(*) FROM ogiri_sessions WHERE " + OWNER + " AND expires_at > ?",
                    Integer.class, subject.realm(), subject.tenantId(), subject.subjectId(), now);
            if (count >= policy.maximumSessions()) throw new SessionLimitException();
            var session = new Session(UUID.randomUUID(), subject, client, Instant.ofEpochMilli(now),
                    Instant.ofEpochMilli(Math.addExact(now, policy.lifetime().toMillis())));
            jdbc.update("INSERT INTO ogiri_sessions (" + COLUMNS + ", token_hash) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    session.id().toString(), subject.realm(), subject.tenantId(), subject.subjectId(), client,
                    now, session.expiresAt().toEpochMilli(), Tokens.digest(token));
            return new IssuedSession(session, token);
        });
    }

    /** One indexed read by default; configured cache hits may use a bounded-age session snapshot. */
    public Optional<Session> authenticate(String token) {
        byte[] digest = Tokens.digest(token);
        if (digest == null) return Optional.empty();
        Supplier<Optional<Session>> authoritative = () -> read(() -> jdbc.query(
                "SELECT " + COLUMNS + " FROM ogiri_sessions WHERE token_hash = ? AND expires_at > " + database.now,
                JdbcSessions::row, digest).stream().findFirst());
        return cache == null ? authoritative.get() : cache.lookup(digest, authoritative);
    }

    /** List live session metadata for an application-authorized complete subject. */
    public List<Session> list(Subject subject) {
        Objects.requireNonNull(subject, "subject");
        return read(() -> List.copyOf(jdbc.query("SELECT " + COLUMNS + " FROM ogiri_sessions WHERE " + OWNER
                + " AND expires_at > " + database.now + " ORDER BY created_at, id", JdbcSessions::row,
                subject.realm(), subject.tenantId(), subject.subjectId())));
    }

    /** Delete one owned session; foreign or absent identifiers return false. */
    public boolean revoke(Subject subject, UUID id) {
        Objects.requireNonNull(subject, "subject"); Objects.requireNonNull(id, "id");
        List<byte[]> hashes = new ArrayList<>();
        boolean removed = write(() -> {
            if (cache != null) hashes.addAll(jdbc.queryForList(
                    "SELECT token_hash FROM ogiri_sessions WHERE " + OWNER + " AND id = ?", byte[].class,
                    subject.realm(), subject.tenantId(), subject.subjectId(), id.toString()));
            return jdbc.update("DELETE FROM ogiri_sessions WHERE " + OWNER + " AND id = ?",
                    subject.realm(), subject.tenantId(), subject.subjectId(), id.toString()) == 1;
        });
        if (cache != null) hashes.forEach(cache::evict); // Only after the independent commit succeeded.
        return removed;
    }

    /** Serialize with issuance and revoke existing sessions; this does not ban future login. */
    public int revokeAll(Subject subject) {
        Objects.requireNonNull(subject, "subject");
        List<byte[]> hashes = new ArrayList<>();
        int removed = write(() -> {
            lock(subject);
            // Expired entries cannot authenticate, so avoid collecting historical hashes.
            if (cache != null) hashes.addAll(jdbc.queryForList(
                    "SELECT token_hash FROM ogiri_sessions WHERE " + OWNER + " AND expires_at > " + database.now,
                    byte[].class, subject.realm(), subject.tenantId(), subject.subjectId()));
            return jdbc.update("DELETE FROM ogiri_sessions WHERE " + OWNER,
                    subject.realm(), subject.tenantId(), subject.subjectId());
        });
        if (cache != null) hashes.forEach(cache::evict);
        return removed;
    }

    /** Bounded cleanup, safe with concurrent workers. Expiry enforcement never waits for cleanup. */
    public int cleanup(int batchSize) {
        if (batchSize < 1 || batchSize > 10_000) throw new IllegalArgumentException("batchSize must be between 1 and 10000");
        return write(() -> {
            var ids = jdbc.queryForList("SELECT id FROM ogiri_sessions WHERE expires_at <= " + database.now
                    + " ORDER BY expires_at, id LIMIT ? FOR UPDATE SKIP LOCKED", String.class, batchSize);
            return ids.isEmpty() ? 0 : named.update("DELETE FROM ogiri_sessions WHERE id IN (:ids)", Map.of("ids", ids));
        });
    }

    private void lock(Subject subject) {
        byte[] key = Tokens.lockKey(subject);
        jdbc.update(database.insertLock, key);
        jdbc.queryForObject("SELECT lock_key FROM ogiri_subject_locks WHERE lock_key = ? FOR UPDATE", byte[].class, key);
    }

    private <T> T write(Supplier<T> operation) { return execute(mutations, operation); }
    private <T> T read(Supplier<T> operation) { return execute(reads, operation); }
    private <T> T execute(TransactionTemplate template, Supplier<T> operation) {
        try { return template.execute(status -> operation.get()); }
        catch (DataAccessException | TransactionException failure) { throw new SessionStoreException(failure); }
    }

    private static Session row(ResultSet rows, int index) throws SQLException {
        return new Session(UUID.fromString(rows.getString("id")),
                new Subject(rows.getString("realm"), rows.getString("tenant_id"), rows.getString("subject_id")),
                rows.getString("client"), Instant.ofEpochMilli(rows.getLong("created_at")),
                Instant.ofEpochMilli(rows.getLong("expires_at")));
    }
}
