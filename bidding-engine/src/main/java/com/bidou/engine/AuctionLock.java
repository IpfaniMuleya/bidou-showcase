package com.bidou.engine;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Per-auction mutual exclusion. Production uses a Redisson (Redis) lock so it
 * holds across several app instances; see docs/concurrency.md.
 *
 * Contract: everything {@code action} does, including committing to the
 * database, must be finished before the lock is released.
 */
public interface AuctionLock {

    /**
     * Runs {@code action} while holding the lock for {@code auctionId}.
     *
     * @return the action's (non-null) result, or empty if the lock was not
     *         acquired within {@code wait}
     */
    <T> Optional<T> runLocked(UUID auctionId, Duration wait, Supplier<T> action);
}
