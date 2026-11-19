# Auction formats

All four formats implement one interface and are chosen by the auction's type:

```java
public interface BiddingStrategy {
    BidResult apply(BidRequest request, Auction auction, Bidder bidder);
    BigDecimal minimumNextBid(Auction auction);
}
```

Proxy and Candle share an `AbstractProxyStrategy` base class and override only
the end-time rules. In production, each format's settings (extension window,
hidden end time, price-drop rate, floor) live in a JSONB column on the
`auctions` table, so adding a format needs no schema change.

Examples below use a starting price of 100 and an increment of 10.

---

## Proxy (ascending, automatic bidding)

Bidders submit the **most they are willing to pay**. The system bids on their
behalf, only as high as needed to stay ahead.

| Step | Bid | Result | Price |
|---|---|---|---|
| 1 | Alice, max 150 | Alice leads at her opening amount | 110 |
| 2 | Bob, max 300 | Bob's max beats 150 → Bob leads at 150 + 10 | 160 |
| 3 | Carol, max 200 | Bob's proxy answers at 200 + 10 | 210 |

Rules:

- **Tie on max:** the earlier bidder keeps the lead.
- **Auto-bids never exceed the leader's max:** (max 150 vs. 145 → price 150, not 155).
- **Reserve jump:** if the leader's max reaches the reserve, the price jumps
  straight to the reserve so the sale is secured.
- **Popcorn extension (anti-sniping):** a bid in the final *N* minutes pushes the
  end to now + *N*, up to a configured number of times.

## Dutch (descending)

The price starts high and a scheduler lowers it by a fixed amount every
interval. **The first bidder to accept the current price wins**, and the auction
closes immediately. If the price reaches the floor with no buyer, the auction
ends as `NO_SALE`.

The trap: the price drop and a purchase can happen at the same moment. Both
take the auction lock, so a buyer always pays exactly the price they accepted.
A client that accepts a price that has *already* dropped further pays the
lower, current price.

## Vickrey (sealed-bid, second-price)

Each bidder submits **one sealed bid**. At close:

- the highest bid wins
- the winner pays the **second-highest** bid (or the starting price if they were alone)
- the price is lifted to the reserve if needed. If the top bid is below the
  reserve, the item doesn't sell.

| Bids | Winner | Pays |
|---|---|---|
| Alice 300, Bob 500, Carol 350 | Bob | 350 |
| Alice 800 (alone) | Alice | 100 (starting price) |

Why it's interesting: because you never pay your own bid, **bidding your true
value is the dominant strategy**. Under-bidding can only lose you the item.

Implementation details:

- The bidder is never told whether they lead. The response always says
  "recorded", and the WebSocket broadcast strips the amount and the bidder.
- The public price trails the leader, so people can see there's competition
  without seeing the top bid.

## Candle (hidden end time)

Named after the medieval practice of ending an auction when a candle guttered
out. Bidding works like Proxy, but the real end time is chosen at random
within a window when the auction is created, and kept hidden.

- Clients show a countdown to the *latest possible* end.
- Bids after the hidden end are rejected, and the expiry job closes the auction.
- **There's no popcorn extension**, deliberately: extending after a late bid
  would reveal that the candle hadn't gone out yet.

Sniping in the final second stops working because nobody knows which second is final.

## Tests

[`AuctionFormatsTest`](../bidding-engine/src/test/java/com/bidou/engine/AuctionFormatsTest.java)
covers every rule above. It uses a controllable clock, so the timing rules
(extensions, the hidden end time, closing) are deterministic.
