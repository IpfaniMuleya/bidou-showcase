package com.bidou.engine;

import java.util.UUID;

public record Bidder(UUID id, String name) {
    public static Bidder named(String name) {
        return new Bidder(UUID.randomUUID(), name);
    }
}
