package com.bidou.engine;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Time-driven changes to auctions: closing them and dropping Dutch prices.
 * In production these run on @Scheduled jobs every 10–30 seconds.
 *
 * They take the same lock as bids. Without it, a close could interleave with a
 * last-moment bid, or a Dutch price drop could overwrite the price a buyer was
 * in the middle of accepting.
 */
public final class AuctionSettlement {

    /** Skip a busy auction rather than stall the job; the next run catches it. */
    private static final Duration LOCK_WAIT = Duration.ofMillis(500);

    private final BiddingEngine engine;
    private final Clock clock;

    public AuctionSettlement(BiddingEngine engine, Clock clock) {
        this.engine = engine;
        this.clock = clock;
    }

    /**
     * Closes the auction if its time is up.
     *
     * @return true if it was closed by this call
     */
    public boolean closeIfDue(UUID auctionId) {
        return engine.lock().runLocked(auctionId, LOCK_WAIT, () -> {
            Auction auction = engine.auction(auctionId);
            Instant now = clock.instant();
            boolean due = auction.status() == AuctionStatus.ACTIVE
                    && (!now.isBefore(auction.endTime())
                        || (auction.hiddenEndTime() != null && now.isAfter(auction.hiddenEndTime())));
            if (!due) {
                return false; // already closed by a bid, or extended since the job looked
            }
            close(auction);
            engine.events().auctionEnded(auction.id(), auction.currentWinner(), auction.currentPrice());
            return true;
        }).orElse(false);
    }

    /** Lowers a Dutch auction's price by {@code drop}, never below the floor. */
    public void dropDutchPrice(UUID auctionId, BigDecimal drop) {
        engine.lock().runLocked(auctionId, LOCK_WAIT, () -> {
            Auction auction = engine.auction(auctionId);
            if (auction.type() != AuctionType.DUTCH || auction.status() != AuctionStatus.ACTIVE) {
                return false; // sold since the job looked
            }
            BigDecimal floor = auction.floorPrice() != null ? auction.floorPrice() : BigDecimal.ZERO;
            BigDecimal price = auction.currentPrice().subtract(drop).max(floor);
            auction.setCurrentPrice(price);

            if (price.compareTo(floor) <= 0 && auction.totalBids() == 0) {
                auction.setStatus(AuctionStatus.NO_SALE);
                engine.events().auctionEnded(auction.id(), null, price);
            }
            return true;
        });
    }

    private void close(Auction auction) {
        if (auction.type() == AuctionType.VICKREY) {
            closeSecondPrice(auction);
            return;
        }
        boolean sold = auction.currentWinner() != null && auction.isReserveMet();
        if (!sold) {
            clearWinner(auction);
        }
        auction.setStatus(sold ? AuctionStatus.COMPLETED : AuctionStatus.NO_SALE);
    }

    /** Highest sealed bid wins and pays the second-highest (or the starting price if alone). */
    private void closeSecondPrice(Auction auction) {
        List<Bid> ranked = engine.bids().byAmountDescending(auction.id());
        if (ranked.isEmpty()) {
            clearWinner(auction);
            auction.setStatus(AuctionStatus.NO_SALE);
            return;
        }

        Bid top = ranked.get(0);
        BigDecimal price = ranked.size() > 1 ? ranked.get(1).amount() : auction.startingPrice();

        BigDecimal reserve = auction.reservePrice();
        if (reserve != null) {
            if (top.amount().compareTo(reserve) < 0) {
                clearWinner(auction);
                auction.setStatus(AuctionStatus.NO_SALE);
                return;
            }
            price = price.max(reserve);
        }

        ranked.forEach(b -> b.setWinning(b == top));
        auction.setCurrentWinner(top.bidder());
        auction.setCurrentPrice(price);
        auction.setStatus(AuctionStatus.COMPLETED);
    }

    private void clearWinner(Auction auction) {
        engine.bids().all(auction.id()).forEach(b -> b.setWinning(false));
        auction.setCurrentWinner(null);
    }
}
