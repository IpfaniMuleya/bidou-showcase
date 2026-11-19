package com.bidou.engine;

import java.math.BigDecimal;

/**
 * What the bidder is told about their bid.
 *
 * @param outbidBidder who should get an "outbid" notification, if anyone
 * @param extended     whether this bid pushed the end time out (anti-sniping)
 */
public record BidResult(
        boolean accepted,
        String message,
        Bid bid,
        BigDecimal currentPrice,
        boolean winning,
        Bidder outbidBidder,
        boolean extended
) {
    public static BidResult rejected(String message) {
        return new BidResult(false, message, null, null, false, null, false);
    }
}
