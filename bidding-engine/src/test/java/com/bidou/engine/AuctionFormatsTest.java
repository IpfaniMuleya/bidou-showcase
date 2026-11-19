package com.bidou.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules of each auction format, driven through {@link BiddingEngine}.
 * Unless stated otherwise: starting price 100, increment 10.
 */
class AuctionFormatsTest {

    private final TestClock clock = new TestClock();
    private final Bidder seller = Bidder.named("seller");
    private final Bidder alice  = Bidder.named("alice");
    private final Bidder bob    = Bidder.named("bob");
    private final Bidder carol  = Bidder.named("carol");

    private BiddingEngine engine;
    private AuctionSettlement settlement;

    @BeforeEach
    void setUp() {
        engine = new BiddingEngine(new InMemoryBidStore(), new InMemoryAuctionLock(), BidEvents.NONE, clock);
        settlement = new AuctionSettlement(engine, clock);
    }

    private Auction.Builder auction(AuctionType type, Duration remaining) {
        Instant now = clock.instant();
        return Auction.builder(type, seller).window(now.minusSeconds(60), now.plus(remaining));
    }

    private Auction open(Auction.Builder builder) {
        return engine.register(builder.build());
    }

    @Nested
    class Proxy {

        private Auction a;

        @BeforeEach
        void setUp() {
            a = open(auction(AuctionType.PROXY, Duration.ofHours(1)));
        }

        @Test
        void firstBidLeadsAtTheAmountPlaced() {
            BidResult r = engine.placeBid(BidRequest.withMax(a, "110", "200"), alice);

            assertThat(r.accepted()).isTrue();
            assertThat(r.winning()).isTrue();
            assertThat(a.currentPrice()).isEqualByComparingTo("110");
            assertThat(a.currentWinner()).isEqualTo(alice);
        }

        @Test
        void higherMaxLeadsAtOneIncrementAboveThePreviousMax() {
            engine.placeBid(BidRequest.withMax(a, "110", "150"), alice);

            BidResult r = engine.placeBid(BidRequest.withMax(a, "160", "300"), bob);

            assertThat(r.winning()).isTrue();
            assertThat(r.outbidBidder()).isEqualTo(alice);
            assertThat(a.currentPrice()).isEqualByComparingTo("160");
            assertThat(a.currentWinner()).isEqualTo(bob);
        }

        @Test
        void lowerMaxIsAnsweredAutomaticallyByTheLeadersProxy() {
            engine.placeBid(BidRequest.withMax(a, "110", "300"), alice);

            BidResult r = engine.placeBid(BidRequest.withMax(a, "150", "150"), bob);

            assertThat(r.winning()).isFalse();
            assertThat(a.currentPrice()).isEqualByComparingTo("160");
            assertThat(a.currentWinner()).isEqualTo(alice);
            assertThat(a.totalBids()).isEqualTo(3); // alice, bob, alice's auto-bid
            assertThat(engine.bids().all(a.id()).get(2).isAutoBid()).isTrue();
        }

        @Test
        void autoBidNeverExceedsTheLeadersMax() {
            engine.placeBid(BidRequest.withMax(a, "110", "150"), alice);
            engine.placeBid(BidRequest.withMax(a, "145", "145"), bob);

            assertThat(a.currentPrice()).isEqualByComparingTo("150");
        }

        @Test
        void equalMaxKeepsTheEarlierBidderInTheLead() {
            engine.placeBid(BidRequest.withMax(a, "110", "200"), alice);
            engine.placeBid(BidRequest.withMax(a, "200", "200"), bob);

            assertThat(a.currentWinner()).isEqualTo(alice);
            assertThat(a.currentPrice()).isEqualByComparingTo("200");
        }

        @Test
        void priceJumpsToTheReserveWhenTheLeaderCanAffordIt() {
            Auction withReserve = open(auction(AuctionType.PROXY, Duration.ofHours(1)).reservePrice("250"));

            engine.placeBid(BidRequest.withMax(withReserve, "110", "300"), alice);

            assertThat(withReserve.currentPrice()).isEqualByComparingTo("250");
            assertThat(withReserve.isReserveMet()).isTrue();
        }

        @Test
        void rejectsMaxBelowTheMinimumNextBid() {
            engine.placeBid(BidRequest.of(a, "150"), alice);

            BidResult r = engine.placeBid(BidRequest.of(a, "155"), bob);

            assertThat(r.accepted()).isFalse();
            assertThat(r.message()).isEqualTo("Max bid must be at least 160");
        }

        @Test
        void sellerAndCurrentLeaderCannotBid() {
            assertThat(engine.placeBid(BidRequest.of(a, "110"), seller).message()).contains("Seller");

            engine.placeBid(BidRequest.of(a, "110"), alice);
            assertThat(engine.placeBid(BidRequest.of(a, "200"), alice).message()).contains("already the highest");
        }

        @Test
        void lateBidExtendsTheEndUntilTheExtensionLimit() {
            Auction late = open(auction(AuctionType.PROXY, Duration.ofMinutes(1)).popcorn(5, 1));

            BidResult first = engine.placeBid(BidRequest.of(late, "110"), alice);
            assertThat(first.extended()).isTrue();
            assertThat(late.endTime()).isEqualTo(clock.instant().plus(Duration.ofMinutes(5)));

            clock.advance(Duration.ofMinutes(4));
            BidResult second = engine.placeBid(BidRequest.of(late, "120"), bob);
            assertThat(second.extended()).isFalse(); // the one allowed extension is used
        }

        @Test
        void closingAwardsTheLeaderOnlyIfTheReserveIsMet() {
            Auction reserved = open(auction(AuctionType.PROXY, Duration.ofMinutes(10)).reservePrice("500"));
            engine.placeBid(BidRequest.withMax(reserved, "110", "200"), alice);

            clock.advance(Duration.ofMinutes(11));
            assertThat(settlement.closeIfDue(reserved.id())).isTrue();

            assertThat(reserved.status()).isEqualTo(AuctionStatus.NO_SALE);
            assertThat(reserved.currentWinner()).isNull();
        }
    }

    @Nested
    class Candle {

        @Test
        void usesProxyRulesButIgnoresPopcornSettings() {
            Auction a = open(auction(AuctionType.CANDLE, Duration.ofMinutes(1))
                    .popcorn(5, 3)
                    .hiddenEndTime(clock.instant().plusSeconds(30)));
            Instant publicEnd = a.endTime();

            engine.placeBid(BidRequest.withMax(a, "110", "300"), alice);
            BidResult r = engine.placeBid(BidRequest.withMax(a, "150", "150"), bob);

            assertThat(a.currentPrice()).isEqualByComparingTo("160");
            assertThat(r.extended()).isFalse();
            assertThat(a.endTime()).isEqualTo(publicEnd);
        }

        @Test
        void bidsAreRejectedAndTheAuctionClosesOnceTheCandleGoesOut() {
            Auction a = open(auction(AuctionType.CANDLE, Duration.ofHours(1))
                    .hiddenEndTime(clock.instant().plus(Duration.ofMinutes(20))));
            engine.placeBid(BidRequest.of(a, "110"), alice);

            clock.advance(Duration.ofMinutes(21)); // public end is still 39 minutes away

            assertThat(engine.placeBid(BidRequest.of(a, "200"), bob).message()).contains("candle has gone out");
            assertThat(settlement.closeIfDue(a.id())).isTrue();
            assertThat(a.status()).isEqualTo(AuctionStatus.COMPLETED);
            assertThat(a.currentWinner()).isEqualTo(alice);
        }
    }

    @Nested
    class Dutch {

        private Auction a;

        @BeforeEach
        void setUp() {
            a = open(auction(AuctionType.DUTCH, Duration.ofHours(1)).startingPrice("1000").floorPrice("400"));
        }

        @Test
        void firstAcceptanceWinsAtTheCurrentPriceAndClosesTheAuction() {
            settlement.dropDutchPrice(a.id(), new BigDecimal("150"));

            BidResult r = engine.placeBid(BidRequest.of(a, "850"), alice);

            assertThat(r.winning()).isTrue();
            assertThat(a.status()).isEqualTo(AuctionStatus.COMPLETED);
            assertThat(a.currentPrice()).isEqualByComparingTo("850");
            assertThat(engine.placeBid(BidRequest.of(a, "850"), bob).accepted()).isFalse();
        }

        @Test
        void acceptingAStalePriceIsRejected() {
            BidResult r = engine.placeBid(BidRequest.of(a, "900"), alice);

            assertThat(r.accepted()).isFalse();
            assertThat(r.message()).contains("current asking price of 1000");
        }

        @Test
        void priceStopsAtTheFloorAndTheAuctionEndsUnsold() {
            settlement.dropDutchPrice(a.id(), new BigDecimal("700"));

            assertThat(a.currentPrice()).isEqualByComparingTo("400");
            assertThat(a.status()).isEqualTo(AuctionStatus.NO_SALE);
        }
    }

    @Nested
    class Vickrey {

        private Auction a;

        @BeforeEach
        void setUp() {
            a = open(auction(AuctionType.VICKREY, Duration.ofMinutes(10)));
        }

        @Test
        void highestBidderWinsAndPaysTheSecondHighestBid() {
            engine.placeBid(BidRequest.of(a, "300"), alice);
            engine.placeBid(BidRequest.of(a, "500"), bob);
            engine.placeBid(BidRequest.of(a, "350"), carol);

            clock.advance(Duration.ofMinutes(11));
            settlement.closeIfDue(a.id());

            assertThat(a.currentWinner()).isEqualTo(bob);
            assertThat(a.currentPrice()).isEqualByComparingTo("350");
        }

        @Test
        void aLoneBidderPaysTheStartingPrice() {
            engine.placeBid(BidRequest.of(a, "800"), alice);

            clock.advance(Duration.ofMinutes(11));
            settlement.closeIfDue(a.id());

            assertThat(a.currentWinner()).isEqualTo(alice);
            assertThat(a.currentPrice()).isEqualByComparingTo("100");
        }

        @Test
        void bidsAreSealedAndOnePerBidder() {
            BidResult r = engine.placeBid(BidRequest.of(a, "800"), alice);

            assertThat(r.winning()).isFalse(); // never revealed before close
            assertThat(engine.placeBid(BidRequest.of(a, "900"), alice).message()).contains("already placed");
        }

        @Test
        void winnerPaysAtLeastTheReserve() {
            Auction reserved = open(auction(AuctionType.VICKREY, Duration.ofMinutes(10)).reservePrice("400"));
            engine.placeBid(BidRequest.of(reserved, "450"), alice);
            engine.placeBid(BidRequest.of(reserved, "200"), bob);

            clock.advance(Duration.ofMinutes(11));
            settlement.closeIfDue(reserved.id());

            assertThat(reserved.currentWinner()).isEqualTo(alice);
            assertThat(reserved.currentPrice()).isEqualByComparingTo("400"); // second price 200, lifted to reserve
        }
    }
}
