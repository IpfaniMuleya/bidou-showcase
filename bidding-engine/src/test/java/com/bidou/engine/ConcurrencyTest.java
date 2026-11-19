package com.bidou.engine;

import org.junit.jupiter.api.RepeatedTest;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Many threads hitting one auction at the same instant. Repeated because
 * thread interleavings differ per run.
 *
 * The production version of the first test runs against real Postgres and
 * Redis via Testcontainers; see docs/concurrency.md.
 */
class ConcurrencyTest {

    private static final int THREADS = 40;

    private final Bidder seller = Bidder.named("seller");
    private final BiddingEngine engine = new BiddingEngine(
            new InMemoryBidStore(), new InMemoryAuctionLock(), BidEvents.NONE, Clock.systemUTC());

    @RepeatedTest(10)
    void simultaneousProxyBidsLeaveTheAuctionConsistent() throws Exception {
        Auction a = engine.register(Auction.builder(AuctionType.PROXY, seller).build());
        List<Bidder> bidders = bidders(THREADS);

        // Bidder i is willing to pay up to 200 + 10*i, so the last one has the top max.
        List<BidResult> results = all(i -> engine.placeBid(
                new BidRequest(a.id(), new BigDecimal("110"), maxFor(i)), bidders.get(i)));

        // Every bid either succeeded or broke a rule; nothing failed because of contention.
        assertThat(results).allSatisfy(r -> {
            if (!r.accepted()) assertThat(r.message()).startsWith("Max bid must be at least");
        });

        List<Bid> rows = engine.bids().all(a.id());
        List<Bid> winning = rows.stream().filter(Bid::isWinning).toList();

        assertThat(winning).hasSize(1);
        assertThat(a.currentWinner()).isEqualTo(winning.get(0).bidder());
        assertThat(a.currentPrice()).isEqualByComparingTo(winning.get(0).amount());
        assertThat(a.totalBids()).isEqualTo(rows.size());

        // Highest max wins, at the runner-up's max or one increment above it.
        assertThat(a.currentWinner()).isEqualTo(bidders.get(THREADS - 1));
        BigDecimal runnerUp = maxFor(THREADS - 2);
        assertThat(a.currentPrice()).isBetween(runnerUp, runnerUp.add(a.bidIncrement()));
    }

    @RepeatedTest(10)
    void dutchPriceDropsRacingBuyersSellExactlyOnceAtAnAcceptedPrice() throws Exception {
        Auction a = engine.register(Auction.builder(AuctionType.DUTCH, seller)
                .startingPrice("1000").floorPrice("100").build());
        AuctionSettlement settlement = new AuctionSettlement(engine, Clock.systemUTC());
        List<Bidder> buyers = bidders(THREADS);

        // Half the threads drop the price; the other half try to buy at 1000.
        List<BidResult> results = all(i -> {
            if (i % 2 == 0) {
                settlement.dropDutchPrice(a.id(), new BigDecimal("10"));
                return null;
            }
            return engine.placeBid(BidRequest.of(a, "1000"), buyers.get(i));
        });

        List<BidResult> sales = results.stream().filter(r -> r != null && r.accepted()).toList();
        assertThat(sales).hasSize(1);
        assertThat(a.status()).isEqualTo(AuctionStatus.COMPLETED);
        // The recorded price is the one the buyer was told, not one changed underneath them.
        assertThat(a.currentPrice()).isEqualByComparingTo(sales.get(0).currentPrice());
        assertThat(engine.bids().all(a.id())).hasSize(1);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static BigDecimal maxFor(int i) {
        return new BigDecimal(200 + 10 * i);
    }

    private static List<Bidder> bidders(int n) {
        List<Bidder> list = new ArrayList<>();
        for (int i = 0; i < n; i++) list.add(Bidder.named("bidder" + i));
        return list;
    }

    /** Runs {@code task(0..THREADS-1)} on separate threads released from one start line. */
    private static <T> List<T> all(IntFunction<T> task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                final int index = i;
                Callable<T> c = () -> {
                    start.await();
                    return task.apply(index);
                };
                futures.add(pool.submit(c));
            }
            start.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) out.add(f.get());
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
