package com.bidou.engine;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entry point for placing bids. For each bid:
 *
 * <ol>
 *   <li>acquire the auction's lock (give up after {@link #LOCK_WAIT})</li>
 *   <li>re-read the auction and apply the format's rules</li>
 *   <li>persist — in production, COMMIT the transaction</li>
 *   <li>publish events</li>
 *   <li>release the lock</li>
 * </ol>
 *
 * Steps 2–4 all happen inside the lock. Releasing it before the commit lets
 * the next bidder read a stale price; docs/concurrency.md walks through the
 * production bug this design fixes.
 */
public final class BiddingEngine {

    static final Duration LOCK_WAIT = Duration.ofSeconds(5);

    private final Map<UUID, Auction> auctions = new ConcurrentHashMap<>();
    private final Map<AuctionType, BiddingStrategy> strategies = new EnumMap<>(AuctionType.class);
    private final BidStore bids;
    private final AuctionLock lock;
    private final BidEvents events;

    public BiddingEngine(BidStore bids, AuctionLock lock, BidEvents events, Clock clock) {
        this.bids   = bids;
        this.lock   = lock;
        this.events = events;
        strategies.put(AuctionType.PROXY,   new ProxyStrategy(bids, clock));
        strategies.put(AuctionType.CANDLE,  new CandleStrategy(bids, clock));
        strategies.put(AuctionType.DUTCH,   new DutchStrategy(bids, clock));
        strategies.put(AuctionType.VICKREY, new VickreyStrategy(bids, clock));
    }

    /** Convenience setup: in-memory store and lock, no events, system clock. */
    public static BiddingEngine inMemory() {
        return new BiddingEngine(new InMemoryBidStore(), new InMemoryAuctionLock(), BidEvents.NONE, Clock.systemUTC());
    }

    public Auction register(Auction auction) {
        auctions.put(auction.id(), auction);
        return auction;
    }

    public Auction auction(UUID id) {
        return Objects.requireNonNull(auctions.get(id), "Unknown auction " + id);
    }

    public BidStore bids() {
        return bids;
    }

    public AuctionLock lock() {
        return lock;
    }

    public BidEvents events() {
        return events;
    }

    /**
     * Places a bid. Never throws for a rule violation: the result says
     * whether it was accepted and why not.
     */
    public BidResult placeBid(BidRequest request, Bidder bidder) {
        return lock.runLocked(request.auctionId(), LOCK_WAIT, () -> applyLocked(request, bidder))
                .orElseGet(() -> BidResult.rejected("System is busy, please try again"));
    }

    private BidResult applyLocked(BidRequest request, Bidder bidder) {
        Auction auction = auction(request.auctionId());
        BidResult result;
        try {
            result = strategies.get(auction.type()).apply(request, auction, bidder);
        } catch (BidRejectedException e) {
            // Strategies validate before they mutate, so there is nothing to undo.
            // (In production the transaction rolls back here.)
            return BidResult.rejected(e.getMessage());
        }

        // Production: transaction commits here, then we broadcast — still under the lock.
        events.bidAccepted(auction, result);
        if (result.outbidBidder() != null) {
            events.outbid(result.outbidBidder(), auction);
        }
        if (auction.status() != AuctionStatus.ACTIVE) {
            events.auctionEnded(auction.id(), auction.currentWinner(), auction.currentPrice());
        }
        return result;
    }
}
