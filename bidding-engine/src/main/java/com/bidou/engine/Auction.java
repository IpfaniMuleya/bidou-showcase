package com.bidou.engine;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Mutable auction state. In production this is a JPA entity with the
 * type-specific settings in a JSONB column; here they are typed fields.
 *
 * Not thread-safe on its own: every mutation goes through {@link BiddingEngine}
 * or {@link AuctionSettlement}, which hold the auction's lock.
 */
public final class Auction {

    private final UUID id = UUID.randomUUID();
    private final AuctionType type;
    private final Bidder seller;
    private final BigDecimal startingPrice;
    private final BigDecimal bidIncrement;
    private final BigDecimal reservePrice;
    private final Instant startTime;

    private AuctionStatus status = AuctionStatus.ACTIVE;
    private BigDecimal currentPrice;
    private Bidder currentWinner;
    private Instant endTime;
    private long totalBids;

    // Proxy: popcorn anti-sniping
    private Integer autoExtendMinutes;
    private Integer maxExtensions;
    private int extensionsUsed;

    // Candle: the real, hidden end time
    private Instant hiddenEndTime;

    // Dutch: descending price
    private BigDecimal floorPrice;

    private Auction(Builder b) {
        this.type          = b.type;
        this.seller        = b.seller;
        this.startingPrice = b.startingPrice;
        this.currentPrice  = b.startingPrice;
        this.bidIncrement  = b.bidIncrement;
        this.reservePrice  = b.reservePrice;
        this.startTime     = b.startTime;
        this.endTime       = b.endTime;
        this.autoExtendMinutes = b.autoExtendMinutes;
        this.maxExtensions     = b.maxExtensions;
        this.hiddenEndTime     = b.hiddenEndTime;
        this.floorPrice        = b.floorPrice;
    }

    public static Builder builder(AuctionType type, Bidder seller) {
        return new Builder(type, seller);
    }

    /** Accepting bids right now: ACTIVE and inside the start/end window. */
    public boolean isOpenAt(Instant now) {
        return status == AuctionStatus.ACTIVE && now.isAfter(startTime) && now.isBefore(endTime);
    }

    public boolean isReserveMet() {
        return reservePrice == null || currentPrice.compareTo(reservePrice) >= 0;
    }

    void incrementBidCount() { totalBids++; }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public UUID id()                    { return id; }
    public AuctionType type()           { return type; }
    public Bidder seller()              { return seller; }
    public BigDecimal startingPrice()   { return startingPrice; }
    public BigDecimal bidIncrement()    { return bidIncrement; }
    public BigDecimal reservePrice()    { return reservePrice; }
    public Instant startTime()          { return startTime; }
    public AuctionStatus status()       { return status; }
    public BigDecimal currentPrice()    { return currentPrice; }
    public Bidder currentWinner()       { return currentWinner; }
    public Instant endTime()            { return endTime; }
    public long totalBids()             { return totalBids; }
    public Integer autoExtendMinutes()  { return autoExtendMinutes; }
    public Integer maxExtensions()      { return maxExtensions; }
    public int extensionsUsed()         { return extensionsUsed; }
    public Instant hiddenEndTime()      { return hiddenEndTime; }
    public BigDecimal floorPrice()      { return floorPrice; }

    void setStatus(AuctionStatus status)         { this.status = status; }
    void setCurrentPrice(BigDecimal price)       { this.currentPrice = price; }
    void setCurrentWinner(Bidder winner)         { this.currentWinner = winner; }
    void setEndTime(Instant endTime)             { this.endTime = endTime; }
    void recordExtension()                       { this.extensionsUsed++; }

    public static final class Builder {
        private final AuctionType type;
        private final Bidder seller;
        private BigDecimal startingPrice = new BigDecimal("100");
        private BigDecimal bidIncrement  = new BigDecimal("10");
        private BigDecimal reservePrice;
        private Instant startTime = Instant.now();
        private Instant endTime   = Instant.now().plusSeconds(3600);
        private Integer autoExtendMinutes;
        private Integer maxExtensions;
        private Instant hiddenEndTime;
        private BigDecimal floorPrice;

        private Builder(AuctionType type, Bidder seller) {
            this.type = type;
            this.seller = seller;
        }

        public Builder startingPrice(String v)           { startingPrice = new BigDecimal(v); return this; }
        public Builder bidIncrement(String v)            { bidIncrement = new BigDecimal(v); return this; }
        public Builder reservePrice(String v)            { reservePrice = new BigDecimal(v); return this; }
        public Builder window(Instant start, Instant end) { startTime = start; endTime = end; return this; }
        public Builder popcorn(int minutes, int max)     { autoExtendMinutes = minutes; maxExtensions = max; return this; }
        public Builder hiddenEndTime(Instant t)          { hiddenEndTime = t; return this; }
        public Builder floorPrice(String v)              { floorPrice = new BigDecimal(v); return this; }

        public Auction build() {
            return new Auction(this);
        }
    }
}
