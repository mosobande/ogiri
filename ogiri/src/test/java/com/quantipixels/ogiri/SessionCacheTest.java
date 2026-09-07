// SPDX-License-Identifier: Apache-2.0
package com.quantipixels.ogiri;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

class SessionCacheTest {
    private static final Instant START = Instant.parse("2030-01-01T00:00:00Z");
    private static final byte[] KEY = new byte[32];
    private static Session session(Instant expiry) {
        return new Session(UUID.randomUUID(), new Subject("users", "tenant", "42"), "phone", START, expiry);
    }

    @Test void fixedAgeAndHardExpiryAreEnforcedEvenWhenProviderNeverExpiresValues() {
        var clock = new MutableClock(START);
        var region = new ConcurrentMapCache("sessions");
        var cache = new SessionCache(region, Duration.ofSeconds(5), clock);
        Session live = session(START.plusSeconds(100));
        var calls = new AtomicInteger();
        java.util.function.Supplier<Optional<Session>> loader = () -> {
            calls.incrementAndGet(); return Optional.of(live);
        };
        assertEquals(live, cache.lookup(KEY, loader).orElseThrow());
        clock.now = START.plusSeconds(4);
        assertEquals(live, cache.lookup(KEY, loader).orElseThrow());
        assertEquals(1, calls.get());
        clock.now = START.plusSeconds(5);
        assertTrue(cache.lookup(KEY, Optional::empty).isEmpty(), "Hits must not slide the original validation age");

        region.clear(); clock.now = START;
        Session expiring = session(START.plusSeconds(1));
        cache.lookup(KEY, () -> Optional.of(expiring));
        clock.now = expiring.expiresAt();
        assertTrue(cache.lookup(KEY, Optional::empty).isEmpty(), "Session expiry wins over cache age");
        clock.now = START.minusSeconds(1);
        assertTrue(cache.lookup(KEY, Optional::empty).isEmpty(), "Clock rollback must not accept a future validation");
    }

    @Test void slowProviderReadsAndStricterReadersCannotExtendTheDeadline() {
        var clock = new MutableClock(START);
        var region = new ConcurrentMapCache("sessions") {
            boolean delay;
            @Override public <T> T get(Object key, Class<T> type) {
                T value = super.get(key, type);
                if (delay) clock.now = START.plusSeconds(5);
                delay = true;
                return value;
            }
        };
        var cache = new SessionCache(region, Duration.ofSeconds(5), clock);
        cache.lookup(KEY, () -> Optional.of(session(START.plusSeconds(100))));
        assertTrue(cache.lookup(KEY, Optional::empty).isEmpty(), "Validate age after provider I/O, not before it");
        var ordinary = new ConcurrentMapCache("shared"); clock.now = START;
        new SessionCache(ordinary, Duration.ofSeconds(30), clock)
                .lookup(KEY, () -> Optional.of(session(START.plusSeconds(100))));
        clock.now = START.plusSeconds(5);
        assertTrue(new SessionCache(ordinary, Duration.ofSeconds(2), clock).lookup(KEY, Optional::empty).isEmpty());
    }

    @Test void fillAfterConcurrentRevocationRetainsOnlyTheOriginalBoundedWindow() throws Exception {
        var clock = new MutableClock(START);
        var cache = new SessionCache(new ConcurrentMapCache("race"), Duration.ofSeconds(5), clock);
        var read = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            Session old = session(START.plusSeconds(100));
            var pending = executor.submit(() -> cache.lookup(KEY, () -> {
                read.countDown();
                try { assertTrue(resume.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                return Optional.of(old);
            }));
            assertTrue(read.await(5, TimeUnit.SECONDS));
            cache.evict(KEY); // The authoritative revocation has committed while a prior read is in flight.
            clock.now = START.plusSeconds(4);
            resume.countDown();
            assertEquals(old, pending.get(5, TimeUnit.SECONDS).orElseThrow());
            assertEquals(old, cache.lookup(KEY, Optional::empty).orElseThrow(), "Bounded-staleness mode allows this race");
            clock.now = START.plusSeconds(5);
            assertTrue(cache.lookup(KEY, Optional::empty).isEmpty(), "The late fill must not earn another five seconds");
        } finally { resume.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test void failuresAndMissesAreNotCachedAndProviderFaultsUseTheAuthoritativeResult() {
        var region = new ConcurrentMapCache("sessions");
        var cache = new SessionCache(region, Duration.ofSeconds(5), new MutableClock(START));
        assertTrue(cache.lookup(KEY, Optional::empty).isEmpty());
        assertTrue(region.getNativeCache().isEmpty());
        assertThrows(IllegalStateException.class, () -> cache.lookup(KEY, () -> { throw new IllegalStateException("database offline"); }));
        assertTrue(region.getNativeCache().isEmpty());
        var unavailable = new ConcurrentMapCache("offline") {
            @Override public <T> T get(Object key, Class<T> type) { throw new IllegalStateException("get offline"); }
            @Override public void put(Object key, Object value) { throw new IllegalStateException("put offline"); }
            @Override public boolean evictIfPresent(Object key) { throw new IllegalStateException("evict offline"); }
        };
        var fallback = new SessionCache(unavailable, Duration.ofSeconds(5), new MutableClock(START));
        var expected = session(START.plusSeconds(100));
        assertEquals(expected, fallback.lookup(KEY, () -> Optional.of(expected)).orElseThrow());
        assertDoesNotThrow(() -> fallback.evict(KEY));
        assertThrows(IllegalStateException.class, () -> fallback.lookup(KEY, () -> { throw new IllegalStateException("database offline"); }));
    }

    @Test void serializedCachePayloadRoundTripsWithoutStoringTheBearerCredential() {
        var manager = new ConcurrentMapCacheManager("shared");
        manager.setStoreByValue(true);
        manager.setBeanClassLoader(SessionCacheTest.class.getClassLoader());
        var region = Objects.requireNonNull(manager.getCache("shared"));
        var clock = new MutableClock(START);
        var first = new SessionCache(region, Duration.ofSeconds(5), clock);
        var second = new SessionCache(region, Duration.ofSeconds(5), clock);
        var original = session(START.plusSeconds(100));
        String token = Tokens.generate();
        byte[] hash = Tokens.digest(token);
        first.lookup(hash, () -> Optional.of(original));
        var copy = second.lookup(hash, () -> { throw new AssertionError("Cache did not round-trip"); }).orElseThrow();
        assertEquals(original, copy);
        assertNotSame(original, copy);
        var keys = ((ConcurrentMapCache) region).getNativeCache().keySet();
        assertEquals(Set.of("ogiri:session:v1:" + HexFormat.of().formatHex(hash)), keys);
        assertFalse(keys.toString().contains(token));
        second.evict(hash);
        assertTrue(first.lookup(hash, Optional::empty).isEmpty());
    }

    @Test void invalidCacheAgesAreRejectedRatherThanDisablingExpiry() {
        var region = new ConcurrentMapCache("sessions");
        for (Duration invalid : List.of(Duration.ZERO, Duration.ofMillis(-1), Duration.ofMinutes(2)))
            assertThrows(IllegalArgumentException.class, () -> new SessionCache(region, invalid));
    }

    private static final class MutableClock extends Clock {
        volatile Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
