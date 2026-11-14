# Observability

What you can see about a running system today, and what is planned. The work itself is in [plans/2026-10-31-observability-and-performance.md](plans/2026-10-31-observability-and-performance.md).

## Today

| Signal | Where | Notes |
|---|---|---|
| Health | `GET /actuator/health` on the gateway (public) and each service | Docker Compose uses it for start order where configured |
| Service registry | Eureka dashboard, http://localhost:8761 | Which instances are up |
| Logs | `docker logs <container>` or the IDE console | Plain text, one stream per service, no correlation id yet |
| Booking state | `bookings.status` (PostgreSQL), `payments.status`, `seat_instances.status` | The saga state is in the tables; read them to see where a booking stopped |
| Events | `outbox` tables in booking and payment (`published_at` empty = not yet sent) | A growing count of unpublished rows means Kafka is unreachable |
| Failed messages | `<topic>.DLT` topics | Read with the Kafka console tools until Kafka UI is added |

### Debugging one booking today

1. `SELECT id, status, payment_id, payment_expires_at FROM bookings WHERE id = ?` (booking DB).
2. `SELECT status, transaction_id FROM payments WHERE booking_id = ?` (payment DB).
3. `SELECT id, status, booking_id, held_until FROM seat_instances WHERE booking_id = ?` (seat DB).
4. Unsent events: `SELECT * FROM outbox WHERE aggregate_id = '<bookingId>' AND published_at IS NULL` in booking and payment.
5. Logs of booking, payment and seat around the booking's time.

## Planned (observability plan, Phase 1–2)

| Piece | Tool | What it adds |
|---|---|---|
| Tracing | OpenTelemetry Java agent → Jaeger (UI :16686) | One timeline per request: HTTP in/out, Feign, every SQL statement, Kafka produce/consume, Redis. No code change |
| Log correlation | `%X{trace_id}` in `logging.pattern.level` | `docker logs <svc> \| grep <traceId>` shows one request's logs across services |
| Kafka view | Kafka UI (:8085) | Topics, message bodies and keys, consumer lag, DLT contents |
| Load | k6 scenarios | p50 / p95 / p99, throughput, error rate per API; a 200-users-one-seat race |
| Results | `docs/performance/REPORT.md` | Baseline, then before / after for each fix |

Everything is opt-in through a separate compose file; `docker compose up` stays unchanged.

**Known behaviour:** the outbox splits one booking into two traces (the request commits; the relay sends later on its own thread). They are joined by the Kafka key, which is the bookingId.

## Not planned

CPU / RAM dashboards and alerting (no real traffic), a full Grafana + Loki stack, production monitoring. These come with the deployment milestone if it needs them.
