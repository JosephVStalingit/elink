package com.example.mcp2p.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * A small cache with a lifetime, used to keep account lookups off the network.
 *
 * <p>The account service allows roughly 60 requests per minute, and every join attempt would otherwise
 * cost one. Reusing a positive answer for a few minutes is safe here: it says "this account proved
 * itself recently", and the proof itself was made with the account's token.
 *
 * @param <K> key type
 * @param <V> value type
 */
final class ResultCache<K, V> {
    private final Duration lifetime;
    private final int maxEntries;
    private final Map<K, Entry<V>> entries = new ConcurrentHashMap<>();

    private record Entry<V>(V value, Instant expiresAt) {
        boolean isExpired(final Instant now) {
            return !now.isBefore(expiresAt);
        }
    }

    ResultCache(final Duration lifetime, final int maxEntries) {
        this.lifetime = lifetime;
        this.maxEntries = Math.max(1, maxEntries);
    }

    Optional<V> get(final K key) {
        final Entry<V> entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.isExpired(Instant.now())) {
            entries.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    void put(final K key, final V value) {
        if (lifetime.isZero() || lifetime.isNegative()) {
            return;
        }
        if (entries.size() >= maxEntries) {
            entries.clear();
        }
        entries.put(key, new Entry<>(value, Instant.now().plus(lifetime)));
    }

    /** Returns the cached value or loads and stores it. */
    V computeIfAbsent(final K key, final Supplier<V> loader) {
        final Optional<V> cached = get(key);
        if (cached.isPresent()) {
            return cached.get();
        }
        final V value = loader.get();
        if (value != null) {
            put(key, value);
        }
        return value;
    }

    void clear() {
        entries.clear();
    }

    int size() {
        return entries.size();
    }
}
