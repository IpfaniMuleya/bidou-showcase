package com.bidou.engine;

/** The four auction formats the platform supports. */
public enum AuctionType {
    /** Ascending, proxy bidding, fixed end with anti-sniping extension. */
    PROXY,
    /** Descending price; the first bidder to accept wins immediately. */
    DUTCH,
    /** Sealed bids; the highest bidder wins and pays the second-highest price. */
    VICKREY,
    /** Ascending proxy bidding with a hidden, random end time. */
    CANDLE
}
