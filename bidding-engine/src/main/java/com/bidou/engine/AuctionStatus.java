package com.bidou.engine;

public enum AuctionStatus {
    ACTIVE,
    /** Ended with a winner who met the reserve. */
    COMPLETED,
    /** Ended without a valid winner (no bids, or reserve not met). */
    NO_SALE
}
