package com.bidou.engine;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One row in the bid history. Only {@code winning} changes after creation. */
public final class Bid {

    private final UUID id = UUID.randomUUID();
    private final UUID auctionId;
    private final Bidder bidder;
    private final BigDecimal amount;
    private final BigDecimal maxBid;
    private final boolean autoBid;
    private final Instant placedAt;
    private boolean winning;

    public Bid(UUID auctionId, Bidder bidder, BigDecimal amount, BigDecimal maxBid,
               boolean autoBid, boolean winning, Instant placedAt) {
        this.auctionId = auctionId;
        this.bidder    = bidder;
        this.amount    = amount;
        this.maxBid    = maxBid;
        this.autoBid   = autoBid;
        this.winning   = winning;
        this.placedAt  = placedAt;
    }

    public UUID id()            { return id; }
    public UUID auctionId()     { return auctionId; }
    public Bidder bidder()      { return bidder; }
    public BigDecimal amount()  { return amount; }
    public BigDecimal maxBid()  { return maxBid; }
    public boolean isAutoBid()  { return autoBid; }
    public boolean isWinning()  { return winning; }
    public Instant placedAt()   { return placedAt; }

    public BigDecimal effectiveMax() {
        return maxBid != null ? maxBid : amount;
    }

    void setWinning(boolean winning) { this.winning = winning; }
}
