package com.bidou.engine;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A clock tests can move forward, so timing rules are deterministic. */
final class TestClock extends Clock {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T12:00:00Z"));

    void advance(Duration d) {
        now.updateAndGet(t -> t.plus(d));
    }

    @Override public Instant instant()              { return now.get(); }
    @Override public ZoneId getZone()               { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone)    { return this; }
}
