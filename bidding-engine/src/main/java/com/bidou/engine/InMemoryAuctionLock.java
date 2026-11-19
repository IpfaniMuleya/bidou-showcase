package com.bidou.engine;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** Single-JVM {@link AuctionLock}: one fair {@link ReentrantLock} per auction. */
public final class InMemoryAuctionLock implements AuctionLock {

    private final Map<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public <T> Optional<T> runLocked(UUID auctionId, Duration wait, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(auctionId, id -> new ReentrantLock(true));
        try {
            if (!lock.tryLock(wait.toMillis(), TimeUnit.MILLISECONDS)) {
                return Optional.empty();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        try {
            return Optional.of(action.get());
        } finally {
            lock.unlock();
        }
    }
}
