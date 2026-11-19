# Architecture

## Components

```mermaid
flowchart TB
    subgraph Web["Spring Boot application"]
        direction TB
        C["Controllers<br/>Auth · Auctions · Bidding · Users · Media · Disputes · Admin"]
        S["Services<br/>AuctionService · BiddingService · EmailService · TotpService · AdminAuditService"]
        STRAT["Bidding strategies<br/>Proxy · Dutch · Vickrey · Candle"]
        JOBS["Scheduled jobs<br/>AuctionExpiryScheduler · DutchPriceDropScheduler"]
        LOCK["AuctionLockService"]
        WSE["WebSocketEventService"]
        REPO["Spring Data repositories"]
        SEC["Security<br/>JWT filter · STOMP auth interceptor"]
    end

    C --> S
    S --> STRAT
    S --> LOCK
    JOBS --> LOCK
    S --> WSE
    JOBS --> WSE
    STRAT --> REPO
    S --> REPO
    SEC -.guards.-> C
    SEC -.guards.-> WSE

    REPO --> PG[(PostgreSQL)]
    LOCK --> RD[(Redis)]
```

Packages are layered: `controller → service → strategy → repository → domain`.
Controllers handle HTTP and validation only, and services own transactions.

## Placing a bid

```mermaid
sequenceDiagram
    autonumber
    actor U as Bidder
    participant C as BiddingController
    participant BS as BiddingService
    participant L as Redis lock
    participant ST as Strategy
    participant DB as Postgres
    participant WS as STOMP broker
    actor O as Other clients

    U->>C: POST /api/v1/auctions/{id}/bids (JWT)
    C->>BS: validateBidEligibility (cheap, no lock)
    C->>BS: processBid
    BS->>L: acquire
    BS->>DB: BEGIN
    BS->>ST: apply(request, auction, bidder)
    ST->>DB: insert bid(s), update auction
    BS->>DB: COMMIT
    BS->>WS: /topic/auction/{id}
    WS-->>O: new price
    BS->>WS: /user/{outbid}/queue/outbid
    BS->>L: release
    BS-->>C: BidResponse
    C-->>U: 200 OK
```

The cheap eligibility check (is it active, are you the seller, are you already
leading?) runs *before* the lock, so obviously invalid bids never queue on
Redis. The strategy checks again under the lock, because state can change in
between.

## Real-time updates

STOMP over WebSocket, authenticated with the same JWT on `CONNECT`:

| Destination | Payload | Who |
|---|---|---|
| `/topic/auction/{id}` | bid accepted / price update | everyone watching |
| `/topic/auction/{id}/end` | winner and final price | everyone watching |
| `/user/{name}/queue/outbid` | you've been outbid | the previous leader only |

For Vickrey auctions the topic message is stripped of the amount and the bidder.

## Data model

```mermaid
erDiagram
    USER ||--o{ AUCTION : sells
    USER ||--o{ BID : places
    AUCTION ||--o{ BID : receives
    USER |o--o{ AUCTION : "currently winning"
    AUCTION ||--o{ DISPUTE : "may have"
    USER ||--o{ ADMIN_AUDIT_LOG : "admin actions"

    AUCTION {
        uuid id
        enum type "PROXY | DUTCH | VICKREY | CANDLE"
        enum status
        decimal current_price
        decimal reserve_price
        timestamptz end_time
        jsonb meta_data "per-format settings"
        bigint version "optimistic lock"
    }
    BID {
        uuid id
        decimal bid_amount
        decimal max_bid "proxy ceiling"
        bool is_auto_bid
        bool is_winning
        timestamptz created_at
    }
```

Design notes:

- **JSONB `meta_data`** holds per-format settings (extension window, hidden end
  time, drop rate, floor). New formats need no migration, at the cost of weaker
  typing in the database.
- **`@Version` on auctions and bids** stays as a safety net under the
  distributed lock.
- **Money is `NUMERIC(19,2)` / `BigDecimal` throughout:** no floating point.

## Beyond bidding

The private codebase also includes:

- **Auction lifecycle:** draft → admin review → active → completed / no sale / cancelled (with reason codes)
- **Fulfilment:** shipment status, carrier and tracking, evidence uploads
- **Disputes:** opened by buyers, resolved by support admins
- **KYC:** document reference, review notes, status workflow
- **Media:** image uploads with type and size limits (max 6 per auction)
- **Email:** async verification and notification emails on a bounded thread pool

**TODO (you):** add a short section on the frontend: structure, how it consumes
the WebSocket topics, and any state-management decisions.
