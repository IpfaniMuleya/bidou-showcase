package com.bidou.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory {@link BidStore}. An auction's bids are only written
 * while that auction's lock is held, but other threads (tests, dashboards) may
 * read concurrently, so each list is synchronised.
 */
public final class InMemoryBidStore implements BidStore {

    private final Map<UUID, List<Bid>> bids = new ConcurrentHashMap<>();

    private List<Bid> listFor(UUID auctionId) {
        return bids.computeIfAbsent(auctionId, id -> Collections.synchronizedList(new ArrayList<>()));
    }

    @Override
    public Bid save(Bid bid) {
        List<Bid> list = listFor(bid.auctionId());
        synchronized (list) {
            if (!list.contains(bid)) list.add(bid);
        }
        return bid;
    }

    @Override
    public Optional<Bid> leadingBid(UUID auctionId) {
        List<Bid> list = listFor(auctionId);
        synchronized (list) {
            Bid latest = null;
            for (Bid b : list) if (b.isWinning()) latest = b;
            return Optional.ofNullable(latest);
        }
    }

    @Override
    public List<Bid> byAmountDescending(UUID auctionId) {
        return all(auctionId).stream()
                .sorted(Comparator.comparing(Bid::amount).reversed().thenComparing(Bid::placedAt))
                .toList();
    }

    @Override
    public boolean hasBid(UUID auctionId, Bidder bidder) {
        return all(auctionId).stream().anyMatch(b -> b.bidder().equals(bidder));
    }

    @Override
    public List<Bid> all(UUID auctionId) {
        List<Bid> list = listFor(auctionId);
        synchronized (list) {
            return List.copyOf(list);
        }
    }
}
