// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Quanti Pixels
package com.quantipixels.ogiri;

import java.io.Serializable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.cache.Cache;

/**
 * Opt-in, bounded-staleness session lookup cache over a dedicated Spring Cache region.
 * Does not cache accounts, authorities, credentials or misses. Hits never renew the validation age.
 * Configure provider eviction to bound memory and keep participating clocks synchronized.
 * Revocation eviction is best effort: local providers, concurrent fills and provider failures
 * can retain a revoked session until maxAge. Leave caching disabled for immediate revocation.
 * Cache storage is authentication authority: restrict writes, access and deserialization.
 */
public final class SessionCache {
    private static final Log LOG = LogFactory.getLog(SessionCache.class);
    private final Cache cache;
    private final Duration maxAge;
    private final Clock clock;

    public SessionCache(Cache cache, Duration maxAge) { this(cache, maxAge, Clock.systemUTC()); }

    SessionCache(Cache cache, Duration maxAge, Clock clock) {
        this.cache = Objects.requireNonNull(cache, "cache");
        this.maxAge = Objects.requireNonNull(maxAge, "maxAge");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxAge.compareTo(Duration.ofMillis(1)) < 0 || maxAge.compareTo(Duration.ofMinutes(1)) > 0)
            throw new IllegalArgumentException("Session cache maxAge must be between 1ms and 1m");
    }

    Optional<Session> lookup(byte[] digest, Supplier<Optional<Session>> authoritative) {
        String key = key(digest);
        try {
            Entry entry = cache.get(key, Entry.class);
            if (entry != null && usable(entry, clock.instant())) return Optional.of(entry.session());
        } catch (RuntimeException unavailable) {
            // No provider exception text, keys or credentials in logs.
            LOG.debug("Session cache read failed; using authoritative storage");
        }
        Instant started = clock.instant();
        Optional<Session> result = authoritative.get(); // A failed lookup is never cached.
        result.ifPresent(session -> {
            Entry entry = new Entry(session, started);
            // Anchor age before I/O: a slow fill must not create a fresh staleness window.
            if (usable(entry, clock.instant())) {
                try { cache.put(key, entry); }
                catch (RuntimeException unavailable) { LOG.debug("Session cache write failed; retaining database result"); }
            }
        });
        return result;
    }

    void evict(byte[] digest) {
        try { cache.evictIfPresent(key(digest)); }
        catch (RuntimeException unavailable) { LOG.debug("Session cache eviction failed; database revocation remains committed"); }
    }

    private boolean usable(Entry entry, Instant now) {
        return entry.validatedAt() != null && entry.session() != null && entry.session().expiresAt() != null
                && !now.isBefore(entry.validatedAt())
                && Duration.between(entry.validatedAt(), now).compareTo(maxAge) < 0
                && now.isBefore(entry.session().expiresAt());
    }

    private static String key(byte[] digest) { return "ogiri:session:v1:" + HexFormat.of().formatHex(digest); }

    // Provider payload, not a public persistence format. Clear this dedicated region on upgrades.
    private record Entry(Session session, Instant validatedAt) implements Serializable {
        private static final long serialVersionUID = 1L;
    }
}
