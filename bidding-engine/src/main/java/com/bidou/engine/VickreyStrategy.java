package com.bidou.engine;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Vickrey (second-price, sealed-bid) auction.
 *
 * Each bidder submits one sealed bid. At close the highest bidder wins but
 * pays the second-highest bid ({@link AuctionSettlement#close}). Because you
 * never pay your own bid, bidding your true value is the best strategy.
 *
 * During the auction the bidder is told only that the bid was received: never
 * whether they lead. The public price trails the leading bid, so it shows that
 * there is competition without revealing the top amount.
 */
public final class VickreyStrategy implements BiddingStrategy {

    private final BidStore bids;
    private final Clock clock;

    public VickreyStrategy(BidStore bids, Clock clock) {
        this.bids = bids;
        this.clock = clock;
    }

    @Override
    public BidResult apply(BidRequest request, Auction auction, Bidder bidder) {
        Instant now = clock.instant();
        validate(request, auction, bidder, now);

        BigDecimal amount = request.amount();
        Optional<Bid> top = bids.leadingBid(auction.id());

        boolean leads;
        if (top.isEmpty()) {
            leads = true;
        } else if (amount.compareTo(top.get().amount()) > 0) {
            top.get().setWinning(false);
            auction.setCurrentPrice(top.get().amount()); // old top is now second-highest
            leads = true;
        } else {
            if (amount.compareTo(auction.currentPrice()) > 0) {
                auction.setCurrentPrice(amount);
            }
            leads = false;
        }

        Bid bid = bids.save(new Bid(auction.id(), bidder, amount, null, false, leads, now));
        auction.incrementBidCount();

        // Deliberately report winning=false: the bid is sealed until close.
        return new BidResult(true,
                "Your sealed bid has been recorded. The winner is revealed when the auction closes.",
                bid, auction.currentPrice(), false, null, false);
    }

    @Override
    public BigDecimal minimumNextBid(Auction auction) {
        return auction.startingPrice();
    }

    private void validate(BidRequest request, Auction auction, Bidder bidder, Instant now) {
        if (!auction.isOpenAt(now)) {
            throw new BidRejectedException("Auction is not active");
        }
        if (auction.seller().equals(bidder)) {
            throw new BidRejectedException("Seller cannot bid on own auction");
        }
        if (bids.hasBid(auction.id(), bidder)) {
            throw new BidRejectedException("You have already placed a sealed bid on this auction");
        }
        if (request.amount().compareTo(auction.startingPrice()) < 0) {
            throw new BidRejectedException("Bid must be at least the starting price of " + auction.startingPrice());
        }
    }
}
