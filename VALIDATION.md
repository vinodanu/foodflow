# FoodFlow validation

Validated on Windows with JDK 21, Node 24, Maven 3.9.11 and Playwright Chromium on 20 October 2026.

- `mvn -f backend/pom.xml test`: 14 Java tests pass. Coverage includes order pricing/transitions/ownership, concurrent idempotent retries, payment creation and callbacks, duplicate event handling, failed/interrupted attempts, reconciliation/refunds, restaurant menu/hours/acceptance timeout, delivery partner availability/offers/expiry/reassignment, and customer notifications/support access.
- `npm.cmd test`: 8 Playwright tests pass. Coverage includes checkout through delivery, API authorization/CSRF/idempotency/ownership, mobile catalog, order history/cancellation/refunds, invalid credentials, payment webhook signature and duplicate handling, restaurant operations, and the customer notification/support flow.
- `npm.cmd run build`: TypeScript check and Vite production build pass.
- Five Flyway migrations apply cleanly on the isolated H2 test databases. Playwright global setup starts/stops the packaged API and Vite using an in-memory database on ports 8094 and 5181. Build the backend jar with `mvn -f backend/pom.xml package -DskipTests` before `npm.cmd test` after a clean checkout.
- `scripts/reliability-check.ps1`: passed against a temporary file-backed H2 database. The check created an order, restarted the API and confirmed persistence, copied a database snapshot, created a later order, restored the snapshot and confirmed the later order was absent.

These are sandbox/local checks, not production readiness evidence. No real payment/refund provider, outbound notifications, production database restore drill, external monitoring/alert delivery, login rate limiting, hosting cost validation or public pilot deployment has been performed. The pilot remains gated on a funded operating budget and operational/provider setup.
