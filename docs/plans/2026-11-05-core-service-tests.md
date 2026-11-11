# Tests for the core services

**Goal:** each core service gets unit tests for its business rules, written and merged once that service's code is complete on `main`, and CI runs them on every PR.

**Core services:** api-gateway, user-service, airline-core-service, flight-ops-service, seat-service, booking-service, payment-service, notification-service.
**Scope:** `src/test/` of those services, test dependencies in their `pom.xml`, the service's workflow in `.github/workflows/` (one per core service), `.claude/rules/testing.md`.
**Out of scope:** tests for location, pricing and ancillary (small catalog services: CI compiles them, nothing more; see ADR-013). Frontend tests. Plain getters, mappers without logic, CRUD that only calls a repository.
**Contract changes:** none. Tests only; a bug a test finds gets its own `fix(...)` commit.

## Kinds of test

| Kind | Tools | For |
|---|---|---|
| Unit | JUnit 5, Mockito, AssertJ (same style as `UserServiceImplTest`) | Rules in one class: validation, branches, status changes, errors. No Spring, no database, no Docker |
| Slice | `@WebFluxTest` style on the gateway, like `SecurityConfigTest` | Routes and security rules |
| Context | `IntegrationTest` + Testcontainers | The service starts: Flyway migrations run, Hibernate validates the schema |
| Concurrency | `IntegrationTest` + `Concurrently` (already written) | Races and duplicates, see `.claude/rules/testing.md` |

A unit test covers the branches a concurrency test doesn't reach. It doesn't repeat a race: mocks can't prove a lock works.

## Tasks, in push order

A service's tests go up once its code is complete on `main`, in a `test/<service>` PR or in the PR that completes it. Exact cases are chosen from the code when the tests are written.

- [x] 1. **api-gateway**: `UserContextGatewayFilter` (trusted headers set, client-sent `X-User-*` stripped), `LocalUserResolver`.
- [x] 2. **user-service**: extend `UserServiceImplTest`; `InternalApiKeyVerifier`.
- [x] 3. **airline-core-service**: `AirlineServiceImpl`, `AircraftServiceImpl`; context test on Testcontainers.
- [x] 4. **flight-ops-service**: `FlightSearchServiceImpl`, `FlightInstanceServiceImpl`, `FlightScheduleServiceImpl`, `FlightServiceImpl`; context test on Testcontainers.
- [x] 5. **seat-service** (after build step 6): `SeatInstanceServiceImpl`, `SeatMapServiceImpl`, `SeatServiceImpl`; with the seat concurrency tests.
- [x] 6. **booking-service** (after build step 6): `BookingServiceImpl`, `BookingCompensator`, `PaymentEventListener`, `BookingExpiryJob`; with the saga concurrency tests.
- [x] 7. **payment-service** (after build step 6): `VnPayService` (signature, amount, RspCode mapping), `PaymentServiceImpl`; with the IPN concurrency tests.
- [x] 8. **notification-service** (after build step 8): `BookingNotificationListener` (dedupe, event to email).

Each service's workflow runs its tests on every PR that touches it, so a new test needs no CI change.

## Verification

- Unit tests: `mvn -B -ntp test -pl <service> -am` passes on a clean copy of `main` plus the new tests, then green on the PR.
- Tests that use Testcontainers need Docker: run them locally when Docker is on; CI is the check otherwise, and that is stated in the PR.
- At least one test per class is broken on purpose once (change the rule, watch it fail) so a test can't pass by accident.

## Result

Plan written 2026-11-05. Tasks 1 and 2 done the same day: 21 new tests, run on a clean copy of `main`; one rule per class broken on purpose and caught by its test.

Tasks 3 and 4 done 2026-11-06: 59 new unit tests, run on a clean copy of `main`; one rule per class broken on purpose and caught. The two context tests move to Testcontainers; Docker was off, so CI is their first run.

Tasks 5 to 8 done 2026-11-08 to 2026-11-12: notification 17, seat 27, payment 24 and booking 35 unit tests, each run locally before its PR and checked again by its service workflow. With the concurrency tests from build step 10, every core service now has its rules covered. Plan complete.
