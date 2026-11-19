package com.bidou.engine;

import java.time.Clock;
import java.time.Instant;

/**
 * Candle auction: proxy bidding, but the auction really ends at a hidden random
 * moment ({@link Auction#hiddenEndTime()}) before the public end time. Nobody
 * knows when the candle goes out, so last-second sniping stops paying off.
 *
 * No popcorn extension: moving the end time after a bid would leak when the
 * candle is due to go out.
 */
public final class CandleStrategy extends AbstractProxyStrategy {

    public CandleStrategy(BidStore bids, Clock clock) {
        super(bids, clock);
    }

    @Override
    protected void checkTiming(Auction auction, Instant now) {
        Instant hidden = auction.hiddenEndTime();
        if (hidden != null && now.isAfter(hidden)) {
            throw new BidRejectedException("The candle has gone out — this auction has ended");
        }
    }
}
