# Security

## Authentication

- **Stateless JWT:** 24-hour access tokens. No server sessions
  (`SessionCreationPolicy.STATELESS`), so any instance can serve any request.
- **Startup checks on the signing key:** the app refuses to start if the secret
  is missing, shorter than 64 bytes, or still a placeholder value.
- **Passwords:** BCrypt.
- **Email verification** before a new account can sign in (configurable per environment).
- **WebSocket:** a STOMP `ChannelInterceptor` validates the JWT on `CONNECT` and
  rejects the session if it's invalid. Private queues (`/user/.../queue/outbid`)
  are bound to the authenticated principal.

## Authorisation

Roles: `BIDDER`, `SELLER`, `SUPPORT_ADMIN`, `SUPER_ADMIN`.

- `/api/v1/admin/**` needs an admin role. Only a `SUPER_ADMIN` can change
  another admin's account.
- Public read-only endpoints (browse auctions, health) are explicitly allow-listed;
  everything else needs authentication.
- **Business rules are enforced in the domain too, not only in the UI:** sellers
  can't bid on their own auctions, admins can't bid at all, and the current
  leader can't bid against themselves.

## Admin two-factor authentication

TOTP (RFC 6238: HMAC-SHA1, 30-second step, 6 digits), compatible with Google
Authenticator, Authy and similar apps.

- Setup returns an `otpauth://` URI for a QR code, and the user confirms with a
  first code before MFA turns on.
- Codes are accepted one 30-second step either side, to allow for clock drift,
  and compared in constant time.
- MFA can be required for all admin accounts by configuration.

## Audit trail

Admin actions are written to an `admin_audit_log` table with who did it, what
they did, the target, and a JSON details payload. Logged actions include
approving, rejecting and cancelling auctions, account-status, role and KYC
changes, dispute and shipment updates, and every MFA event (setup, enable,
disable, failed challenge, reset).

## Other hardening

- CORS and WebSocket origins come from an allow-list in configuration, not `*`
- Upload limits: 20 MB per file, 6 images per auction, image MIME types only
- Secrets live only in environment variables. `.env` is git-ignored, and Docker
  Compose fails fast if `JWT_SECRET` is unset
- Actuator exposes `health` and `info` publicly, and details only to authorised users

## Known gaps / next steps

- No rate limiting on login or bidding yet (planned: a Redis token bucket per user and IP)
- No refresh tokens or token revocation yet (planned: short-lived access tokens + rotating refresh tokens)
- Schema is managed by Hibernate `ddl-auto=update`, so it should move to Flyway
