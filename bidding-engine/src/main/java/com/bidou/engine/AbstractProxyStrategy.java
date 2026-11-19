package com.bidou.engine;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Ascending auction with proxy (automatic) bidding, shared by Proxy and Candle.
 *
 * Each bidder submits the most they are willing to pay. The engine bids on
 * their behalf only as high as needed to stay ahead:
 *
 * <pre>
 *   new max  &gt; leader max  → new bidder leads at leader max + increment (capped at new max)
 *   new max  &lt; leader max  → leader auto-bids to new max + increment (capped at leader max)
 *   new max == leader max  → the earlier bidder keeps the lead at that amount
 * </pre>
 *
 * If there is a reserve and the leader's max reaches it, the price jumps
 * straight to the reserve.
 *
 * Subclasses change only end-time behaviour: {@link #checkTiming} and
 * {@link #afterAcceptedBid}.
 */
public abstract class AbstractProxyStrategy implements BiddingStrategy {

    protected final BidStore bids;
    protected final Clock clock;

    protected AbstractProxyStrategy(BidStore bids, Clock clock) {
        this.bids = bids;
        this.clock = clock;
    }

    /** Extra timing rules checked before the common ones. Throw to reject. */
    protected void checkTiming(Auction auction, Instant now) {}

    /** Runs after every accepted bid. Returns true if the end time was extended. */
    protected boolean afterAcceptedBid(Auction auction, Instant now) {
        return false;
    }

    @Override
    public BidResult apply(BidRequest request, Auction auction, Bidder bidder) {
        Instant now = clock.instant();
        validate(request, auction, bidder, now);

        BigDecimal newMax = request.effectiveMax();
        Optional<Bid> leading = bids.leadingBid(auction.id());

        BigDecimal price;
        Bid placed;
        Bidder outbid;
        boolean newBidderLeads;

        if (leading.isEmpty()) {
            // First bid wins at the amount placed
            price = withReserve(request.amount(), newMax, auction);
            placed = record(auction, bidder, price, newMax, false, true, now);
            outbid = null;
            newBidderLeads = true;
        } else {
            Bid leader = leading.get();
            BigDecimal leaderMax = leader.effectiveMax();
            leader.setWinning(false);

            if (newMax.compareTo(leaderMax) > 0) {
                price = withReserve(leaderMax.add(auction.bidIncrement()), newMax, auction);
                placed = record(auction, bidder, price, newMax, false, true, now);
                outbid = leader.bidder();
                newBidderLeads = true;
            } else {
                // Leader keeps the lead; their proxy answers automatically.
                BigDecimal proposed = newMax.compareTo(leaderMax) < 0
                        ? newMax.add(auction.bidIncrement())
                        : newMax;
                price = withReserve(proposed, leaderMax, auction);
                placed = record(auction, bidder, newMax, newMax, false, false, now);
                record(auction, leader.bidder(), price, leaderMax, true, true, now);
                outbid = bidder;
                newBidderLeads = false;
            }
        }

        auction.setCurrentPrice(price);
        auction.setCurrentWinner(newBidderLeads ? bidder : leading.get().bidder());
        boolean extended = afterAcceptedBid(auction, now);

        return new BidResult(true,
                newBidderLeads ? "You are winning!" : "You have been outbid",
                placed, price, newBidderLeads, outbid, extended);
    }

    @Override
    public BigDecimal minimumNextBid(Auction auction) {
        return auction.currentPrice().add(auction.bidIncrement());
    }

    private void validate(BidRequest request, Auction auction, Bidder bidder, Instant now) {
        checkTiming(auction, now);

        if (!auction.isOpenAt(now)) {
            throw new BidRejectedException("Auction is not active");
        }
        if (auction.seller().equals(bidder)) {
            throw new BidRejectedException("Seller cannot bid on own auction");
        }
        if (bidder.equals(auction.currentWinner())) {
            throw new BidRejectedException("You are already the highest bidder");
        }
        BigDecimal min = minimumNextBid(auction);
        if (request.effectiveMax().compareTo(min) < 0) {
            throw new BidRejectedException("Max bid must be at least " + min);
        }
    }

    private Bid record(Auction auction, Bidder bidder, BigDecimal amount, BigDecimal max,
                       boolean auto, boolean winning, Instant now) {
        auction.incrementBidCount();
        return bids.save(new Bid(auction.id(), bidder, amount, max, auto, winning, now));
    }

    /** Caps the proposed price at the leader's max, then lifts it to the reserve if the leader can afford it. */
    static BigDecimal withReserve(BigDecimal proposed, BigDecimal leaderMax, Auction auction) {
        BigDecimal amount = proposed.min(leaderMax);
        BigDecimal reserve = auction.reservePrice();
        if (reserve != null && leaderMax.compareTo(reserve) >= 0 && amount.compareTo(reserve) < 0) {
            return reserve;
        }
        return amount;
    }
}
