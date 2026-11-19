package com.bidou.engine;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Bid persistence. Production uses a Spring Data JPA repository on Postgres;
 * the engine only needs these queries.
 */
public interface BidStore {

    Bid save(Bid bid);

    /** The bid currently marked as winning, if any. */
    Optional<Bid> leadingBid(UUID auctionId);

    /** All bids, highest amount first; ties go to the earlier bid. */
    List<Bid> byAmountDescending(UUID auctionId);

    boolean hasBid(UUID auctionId, Bidder bidder);

    List<Bid> all(UUID auctionId);
}
