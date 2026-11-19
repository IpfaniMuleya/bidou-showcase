package com.bidou.engine;

/** A bid broke an auction rule. The message is safe to show to the bidder. */
public class BidRejectedException extends RuntimeException {
    public BidRejectedException(String message) {
        super(message);
    }
}
