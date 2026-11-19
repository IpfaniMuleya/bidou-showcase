package com.bidou.engine;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;

/**
 * Dutch auction: the price starts high and drops on a schedule
 * ({@link AuctionSettlement#dropDutchPrice}). The first bidder to accept the
 * current price wins and the auction closes at once.
 *
 * The drop and the acceptance share the auction lock, so the buyer always
 * pays exactly the price they saw accepted, never one lowered mid-purchase.
 */
public final class DutchStrategy implements BiddingStrategy {

    private final BidStore bids;
    private final Clock clock;

    public DutchStrategy(BidStore bids, Clock clock) {
        this.bids = bids;
        this.clock = clock;
    }

    @Override
    public BidResult apply(BidRequest request, Auction auction, Bidder bidder) {
        Instant now = clock.instant();
        validate(request, auction, bidder, now);

        BigDecimal price = auction.currentPrice();
        Bid bid = bids.save(new Bid(auction.id(), bidder, price, null, false, true, now));
        auction.incrementBidCount();
        auction.setCurrentWinner(bidder);
        auction.setStatus(AuctionStatus.COMPLETED);
        auction.setEndTime(now);

        return new BidResult(true, "You won the auction at " + price, bid, price, true, null, false);
    }

    @Override
    public BigDecimal minimumNextBid(Auction auction) {
        return auction.currentPrice();
    }

    private void validate(BidRequest request, Auction auction, Bidder bidder, Instant now) {
        if (!auction.isOpenAt(now)) {
            throw new BidRejectedException("Auction is not active");
        }
        if (auction.seller().equals(bidder)) {
            throw new BidRejectedException("Seller cannot bid on own auction");
        }
        if (auction.reservePrice() != null && auction.currentPrice().compareTo(auction.reservePrice()) < 0) {
            throw new BidRejectedException("Auction has dropped below its reserve price and cannot accept bids");
        }
        // The client sends the price it is accepting. If the price dropped since,
        // accepting a higher figure is fine (they pay the current, lower price).
        if (request.amount().compareTo(auction.currentPrice()) < 0) {
            throw new BidRejectedException("Bid amount must match the current asking price of " + auction.currentPrice());
        }
    }
}
