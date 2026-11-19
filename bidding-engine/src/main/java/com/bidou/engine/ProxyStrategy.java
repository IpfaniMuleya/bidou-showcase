package com.bidou.engine;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Proxy (eBay-style) auction with "popcorn" anti-sniping: a bid in the final
 * {@code autoExtendMinutes} pushes the end out to now + {@code autoExtendMinutes},
 * at most {@code maxExtensions} times.
 */
public final class ProxyStrategy extends AbstractProxyStrategy {

    public ProxyStrategy(BidStore bids, Clock clock) {
        super(bids, clock);
    }

    @Override
    protected boolean afterAcceptedBid(Auction auction, Instant now) {
        Integer window = auction.autoExtendMinutes();
        Integer max    = auction.maxExtensions();
        if (window == null || max == null || auction.extensionsUsed() >= max) {
            return false;
        }
        if (Duration.between(now, auction.endTime()).toMinutes() > window) {
            return false;
        }
        auction.setEndTime(now.plus(Duration.ofMinutes(window)));
        auction.recordExtension();
        return true;
    }
}
