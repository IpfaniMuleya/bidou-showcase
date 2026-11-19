package com.bidou.engine;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Outbound notifications. Production pushes these over STOMP/WebSocket:
 * <pre>
 *   /topic/auction/{id}        bid and price updates
 *   /topic/auction/{id}/end    auction result
 *   /user/{name}/queue/outbid  private "outbid" message
 * </pre>
 * Called once state is final and while the auction lock is still held, so
 * subscribers receive events in the order bids were applied.
 */
public interface BidEvents {

    void bidAccepted(Auction auction, BidResult result);

    void outbid(Bidder bidder, Auction auction);

    void auctionEnded(UUID auctionId, Bidder winner, BigDecimal finalPrice);

    BidEvents NONE = new BidEvents() {
        @Override public void bidAccepted(Auction auction, BidResult result) {}
        @Override public void outbid(Bidder bidder, Auction auction) {}
        @Override public void auctionEnded(UUID auctionId, Bidder winner, BigDecimal finalPrice) {}
    };
}
