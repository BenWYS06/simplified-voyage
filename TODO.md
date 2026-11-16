# TODO

Open work, grouped by area. Bigger items get a plan in [docs/plans/](docs/plans/README.md) before any code; the order of the big blocks is in [docs/roadmap.md](docs/roadmap.md).
When an item is done, tick it and link the PR.

## Next up

- [ ] **Observability and performance** ([plan](docs/plans/2026-10-31-observability-and-performance.md)): OpenTelemetry agent + Jaeger + Kafka UI, `trace_id` in logs, lab scripts, k6 baseline, then fix the measured bottlenecks (flight search N+1, seat map N+1, booking history fan-out).
- [ ] **CI** ([plan](docs/plans/2026-11-04-ci-pipeline.md)): backend compile + 10 tests written (stage 1). Next: all modules after build step 6, concurrency tests, frontend build, lint once the ESLint errors are fixed, unit tests for the core services ([plan](docs/plans/2026-11-05-core-service-tests.md)).
- [ ] **Deployment infrastructure**: where and how the stack runs outside a laptop (images, config, secrets). Needs a spec first.

## Booking flow (after the refactor)

- [ ] Run the full flow once end to end with docker-compose + VNPay sandbox + ngrok (needs `VNPAY_TMN_CODE` / `VNPAY_HASH_SECRET`).
- [ ] Break each concurrency guard on purpose and watch its test fail (proves the tests test the guard).
- [ ] Test "Kafka down → outbox catches up" with a Kafka container.
- [ ] Per-state tests for duplicate create requests (PENDING, AWAITING_PAYMENT, CONFIRMED, past the payment grace).
- [ ] WireMock tests for the resilience layer (timeouts, retry only on transient errors, 409 passes through).
- [ ] Seats stay `BOOKED` when a booking ends `REFUND_REQUIRED` after a failed confirm + user cancel; an admin frees them today. Decide whether release should cover this case.
- [ ] Messages in a `.DLT` topic are stored base64-encoded; add a readable view (Kafka UI is part of the observability plan).
- [ ] `outbox.payload` is limited to 20000 characters; check the largest `booking.confirmed` (many passengers) or switch the column to `TEXT`.
- [ ] Airline admin "cancel booking" row action has no API call (the backend lets only the owner cancel). Decide the rule, then wire it.

## Bugs found while writing tests

- [ ] Aircraft and flight update, delete and status calls never check ownership: `AircraftServiceImpl.updateAircraft` and `deleteAircraft`, `FlightServiceImpl.updateFlight`, `changeStatus` and `deleteFlight`. Any airline owner can change another airline's records, and `updateAircraft` also moves the aircraft to the caller's airline.
- [ ] `FlightScheduleServiceImpl.createFlightSchedule` puts arrival on the departure date, so an overnight flight arrives before it departs.

## Frontend

- [ ] Log in through Keycloak (OIDC authorization code + PKCE) instead of the original project's `/auth/*` endpoints; keep the token out of `localStorage` if possible.
- [ ] Move the API base URL to `VITE_API_URL`.
- [ ] Clear the pre-existing ESLint errors: 200 errors on 2026-11-04 (128 `no-unused-vars`, 56 `no-undef`, 9 `react-refresh/only-export-components`, 7 `no-useless-escape`) plus 27 `exhaustive-deps` warnings. Blocks CI stage 3.
- [ ] Prices show `$` but VNPay charges VND with the same number; add a real currency conversion or show VND.

## Platform

- [ ] Gateway rate limiting for the booking and payment endpoints.
- [ ] Remove the outdated root `database-schema.sql` / `database-schema-core.sql` (documentation only, no code runs them; Flyway `V1__init.sql` files are the real schema).
- [ ] Health checks and `depends_on: condition: service_healthy` for every service in `docker-compose.yml`.

## Done recently

- [x] Booking saga refactor, build steps 1–10 (2026-10-22 → 2026-10-28), see [PROGRESS](docs/booking-refactor/PROGRESS.md).
- [x] Remove the unused subscription service (2026-10-30, [ADR-011](docs/decisions.md)).
- [x] Project docs and Claude Code setup completed (2026-11-03, [plan](docs/plans/2026-11-03-project-docs.md)).
