package com.bidou.engine;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * A bid as the client sends it.
 *
 * @param amount the bid itself (for Dutch: the price being accepted)
 * @param maxBid optional ceiling for proxy-style auctions; the engine bids up to it automatically
 */
public record BidRequest(UUID auctionId, BigDecimal amount, BigDecimal maxBid) {

    public BidRequest {
        Objects.requireNonNull(auctionId, "auctionId");
        Objects.requireNonNull(amount, "amount");
        if (maxBid != null && maxBid.compareTo(amount) < 0) {
            throw new IllegalArgumentException("Max bid must be greater than or equal to bid amount");
        }
    }

    public static BidRequest of(Auction auction, String amount) {
        return new BidRequest(auction.id(), new BigDecimal(amount), null);
    }

    public static BidRequest withMax(Auction auction, String amount, String max) {
        return new BidRequest(auction.id(), new BigDecimal(amount), new BigDecimal(max));
    }

    public BigDecimal effectiveMax() {
        return maxBid != null ? maxBid : amount;
    }
}
