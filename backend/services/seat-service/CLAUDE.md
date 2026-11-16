# seat-service

> Loaded when working inside `backend/services/seat-service/`. Booking flow design: [DESIGN.md](../../../docs/booking-refactor/DESIGN.md) §3 (hold) and §8 (confirm / release).

## Role

Owns aircraft seat maps, cabin classes and **seat instances** (one row per seat per flight instance). In the booking saga it is a participant: booking-service tells it to hold, confirm and release seats; it never calls booking.

```
flight-instance-created (Kafka) → generate cabins + seat instances for the new flight
POST /internal/seat-instances/hold                        AVAILABLE / expired HELD → HELD (booking_id, held_until)
POST /internal/seat-instances/bookings/{id}/confirm       HELD by this booking → BOOKED (all or nothing, else 409)
POST /internal/seat-instances/bookings/{id}/release       HELD by this booking → AVAILABLE
booking.cancelled (Kafka)                                  same release, guaranteed path (outbox in booking)
```

## Seat statuses

`AVAILABLE`, `HELD` (with `booking_id` + `held_until`), `BOOKED`, `BLOCKED` (admin), `OCCUPIED`.
A `HELD` seat whose `held_until` has passed **counts as free everywhere** (hold, availability lists, counts, `isAvailable` in responses). No cleanup job is needed for correctness.

## Rules for this service

- **MySQL**, Flyway migrations in `db/migration/` (`V2__seat_hold.sql` added the hold columns).
- **Hold = one conditional UPDATE** over all requested ids (`SeatInstanceRepository.holdAll`). The "is it free?" check is in the WHERE clause, so concurrent holds can't both win, and one statement locks rows in primary-key order, so overlapping holds can't deadlock. Don't replace it with read-then-write.
- Hold, confirm and release are **idempotent** for the same booking: a retry or a redelivered event changes nothing the second time.
- Confirm is **all or nothing**: fewer rows than seats → roll back and return 409 ("seats lost").
- Hold time `seat.hold-minutes: 15` must stay longer than the VNPay link (10 min) + IPN grace (2 min).
- Admins may set a seat only to `AVAILABLE` or `BLOCKED` (`PATCH /api/seat-instances/{id}/status`).
- `/internal/**` endpoints are not routed by the gateway.

## Key classes

| Class | Purpose |
|---|---|
| `repository/SeatInstanceRepository` | `holdAll`, `confirmForBooking`, `releaseHeld`, availability queries that treat expired holds as free |
| `service/impl/SeatInstanceServiceImpl` | Hold / confirm / release transactions, flight-instance check |
| `controller/InternalSeatHoldController` | The three internal endpoints booking-service calls |
| `event/BookingCancelledListener` | `booking.cancelled` → release (retry + DLT) |
| `event/FlightInstanceEventConsumer` | Builds seat instances for a new flight instance |

## Verify

```bash
cd backend && mvn -q compile -pl services/seat-service -am
mvn test -am -pl services/seat-service -Dsurefire.failIfNoSpecifiedTests=false   # Docker on: SeatHoldConcurrencyTest
```
