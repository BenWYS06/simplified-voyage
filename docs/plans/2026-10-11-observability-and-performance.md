# Observability and performance

**Goal:** see every hop of a request (HTTP, Feign, SQL, Kafka, Redis) for one booking, then use that visibility plus load tests to find the slowest and buggiest APIs, fix them with standard techniques, and prove each fix with before/after numbers.

**Scope:** `docker/` (compose override, agent, Jaeger, Kafka UI, k6), `config-repo/application.yml` + notification `application.yaml` (log pattern), `scripts/lab/` (test-data and fake-VNPay scripts), then targeted fixes in flight-ops, seat, pricing and booking services. A `docs/performance/` report.

**Out of scope:** CPU/RAM dashboards and alerts (no real traffic), Loki and a full Grafana stack, production deployment, autoscaling, a search engine (Elasticsearch), rewriting services, caching anything that must be exact right now (seat status, booking status, payments).

**Contract changes:**
- Phase 1–2: none. Everything is opt-in through a separate compose file; the normal `docker compose up` is unchanged.
- Phase 3: additive only. New batch endpoints in seat-service and pricing-service (old endpoints stay). One new Flyway migration in flight-ops (`V2__search_index.sql`). Flight search pagination is corrected (see F1): same response shape, but `totalElements` becomes accurate. The FE search page is checked in that task.

**How to approve:** phase by phase. Phase 3 fixes are only built if the Phase 2 baseline confirms them.

---

## Phase 1: See the flow (learning and debugging)

Tool choice: **OpenTelemetry Java agent + Jaeger + Kafka UI.** No Java code. The agent records HTTP in/out, Feign calls, every SQL statement, Kafka produce/consume (trace continues through Kafka headers) and Redis commands. Jaeger shows one request as a timeline. Kafka UI shows topics, message bodies, keys and consumer lag.

- [ ] 1. **Kafka UI**: `docker/docker-compose.observability.yml` with a `kafbat/kafka-ui` container on `localhost:8085`, pointed at `kafka:9092`.
- [ ] 2. **Tracing**: same override file adds `jaegertracing/jaeger` (UI `localhost:16686`, OTLP 4318). The five flow services (gateway, booking, seat, payment, notification) get the agent jar as a read-only volume and `JAVA_TOOL_OPTIONS=-javaagent:...`, `OTEL_SERVICE_NAME`, `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_METRICS_EXPORTER=none`, `OTEL_LOGS_EXPORTER=none`. A small script downloads the agent jar (pinned version) into `docker/otel/`, which is git-ignored.
- [ ] 3. **trace_id in every log line**: `logging.pattern.level` with `%X{trace_id}` in `config-repo/application.yml` and notification's `application.yaml` (notification only reads config-server optionally). `docker logs <svc> | Select-String <traceId>` then shows one request's logs.
- [ ] 4. **Lab scripts** in `scripts/lab/` (PowerShell):
  - `seed.ps1`: creates one bookable flight through the real APIs (city → airport → airline → aircraft/seat map → flight → instance → fare).
  - `fake-vnpay.ps1`: reads the payment row, builds and signs IPN / return params with `VNPAY_HASH_SECRET` from the environment (never in a file), calls the gateway. Modes: `paid`, `cancel`, `fail`, `replay`.
- [ ] 5. **Walk the 13 lab cases** (Booking Flow Lab page) with Jaeger + Kafka UI open. Save one trace screenshot per key case into `docs/performance/traces/`.

**Known behaviour to observe, not fix:** the transactional outbox splits a flow into two traces (the request commits, the relay sends later on its own thread). They are joined by the Kafka key, which is the bookingId.

## Phase 2: Measure (baseline)

Performance numbers on 5 rows of data mean nothing, so this phase first builds realistic volume, then measures.

- [ ] 6. **Bulk data generator**: `scripts/lab/perf-data/*.sql` run inside the DB containers. Target volume: ~50 airports, ~20 airlines, ~2,000 flights, ~60,000 flight instances (90 days), fares for every flight and cabin class, seat instances for ~300 instances on the tested routes, ~5,000 past bookings for one test traveler and one airline.
- [ ] 7. **k6 load tests** in `scripts/lab/k6/` (run with the `grafana/k6` container, no install). One script per scenario, each with a warm-up and fixed virtual users:
  - S1 flight search (with cabin class, the most-used read)
  - S2 seat map for one flight instance
  - S3 traveler booking history
  - S4 create booking (each VU different seats)
  - S5 seat race: 200 VUs book the **same** seat at once. Expected exactly 1 success, 199 × 409, 0 × 5xx.
- [ ] 8. **Baseline report** `docs/performance/REPORT.md`: per scenario p50 / p95 / p99, requests per second, error rate, environment (local Docker, CPU, RAM, data volume). For the slowest requests, the Jaeger trace showing where the time goes. Same agent setting in every run; median of 3 runs.

Optional, only if a result can't be explained by traces: switch the exporter to `grafana/otel-lgtm` (one container) and turn on agent metrics to see the JDBC pool wait time and GC. Same agent, one env var.

## Phase 3: Fix what the baseline confirms

Suspects found by reading the code. Each one becomes a fix only if Phase 2 shows it matters. Each fix gets its own before/after row in the report.

- [ ] **F1. Flight search: remote N+1, JPA N+1, missing index, wrong paging** (`FlightSearchServiceImpl`)
  - For **every** result, the search makes 2 sequential Feign calls: `seatClient.getCabinClassByAircraftIdAndName` and `pricingClient.getLowestFareForFlightAndCabinClass`. 20 results = 40 network calls.
  - `fi.getFlight()` is LAZY and read in a loop → one extra SELECT per row.
  - `flight_instances` has no index on `(departure_airport_id, arrival_airport_id, departure_date_time)`, the columns every search filters on.
  - Bug: results are filtered by cabin class and price **after** the DB page is cut, so a page can come back short or empty while `totalElements` says otherwise.
  - Fix: one batch call to seat-service (cabin class ids for all aircraft ids), one batch call to pricing (`POST /api/fares/search` already exists, extended to per-flight cabin class), `JOIN FETCH` the flight, `V2__search_index.sql`, and the cabin/price filter applied consistently with the page. Run `EXPLAIN` before and after.
- [ ] **F2. Seat map: JPA N+1** (`SeatInstanceServiceImpl.getSeatInstancesByFlightInstanceId`)
  - The query loads seat instances, then the mapper reads the LAZY `seat` of each one → 1 + 180 SELECTs for one aircraft. Every traveler choosing seats hits this.
  - Fix: one query with `JOIN FETCH si.seat`. Check the SQL count in the trace before and after.
- [ ] **F3. Booking history: 7 Feign calls per booking** (`BookingServiceImpl.getBookingsByUser`)
  - Each booking calls ancillary ×2, payment, pricing, flight ×2 and seat. 20 bookings = 140 calls.
  - The airline list already uses a batch method, `enrichBatch` (one call per service). Fix: use it for the traveler history too. Check that the response fields are identical.
- [ ] **F4. Booking create: independent reads run one after another** (only if S4 shows it)
  - Fare, seat price, ancillary price and meal price don't depend on each other. Run them in parallel on a small bounded executor. The saga order stays the same: all reads finish before the seat hold.
- [ ] **F5. Hot reference data cache** (only if pricing is still the top span after F1)
  - Fares change rarely; cache lowest-fare lookups in Redis with a TTL and evict on fare update. Seat and booking state are never cached.

Each fix: code → `/verify-build` → re-run the same k6 scenario → add the row to the report. Fixes that touch seats or bookings also get the `concurrency-reviewer`, and S5 must still give exactly one winner.

## Phase 4: Write-up

- [ ] 9. Finish `docs/performance/REPORT.md`: before/after table, one trace screenshot per fix, what was tried and didn't help.
- [ ] 10. README section "Observability and performance" with the result table and how to run the lab. Draft CV bullets in the form "used A to solve B, result C", using only measured numbers.

## Verification

- Phase 1: `docker compose -f docker/docker-compose.yml -f docker/docker-compose.local-config.yml -f docker/docker-compose.observability.yml up -d`; one booking shows as connected spans across gateway → booking → seat → payment in Jaeger, Kafka spans continue into the consumer, logs carry the same trace_id. Without the third file everything starts as before.
- Agent compatibility with Spring Boot 4 is checked on the first run (HTTP server, JDBC, Kafka and Feign spans present). If a part is missing, it is noted, not patched.
- Phase 3: `mvn -q compile -pl <service> -am`, existing tests pass (booking, seat, payment concurrency tests), impact scan for each new endpoint, FE `npm run build` if the search page is touched, k6 re-run with the same data and settings.
