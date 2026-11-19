package com.bidou.engine;

import java.math.BigDecimal;

/**
 * The rules of one auction format (Strategy pattern). {@link BiddingEngine}
 * picks the implementation from {@link Auction#type()}.
 *
 * Implementations assume the caller holds the auction's lock: they read and
 * write auction state freely and never synchronise themselves.
 */
public interface BiddingStrategy {

    /**
     * Validates and applies a bid, mutating the auction and recording bids.
     *
     * @throws BidRejectedException if the bid breaks a rule of this format
     */
    BidResult apply(BidRequest request, Auction auction, Bidder bidder);

    /** Lowest amount the next bid may be, shown to clients. */
    BigDecimal minimumNextBid(Auction auction);
}
