# notification-service

Sends the booking confirmation by email (Gmail SMTP + Thymeleaf template) and SMS (Twilio, optional). It is outside the saga: it only reacts to `booking.confirmed`.

Part of [Ben Airline](../../../README.md). How the services fit together: [architecture.md](../../../docs/architecture.md).

## Responsibilities

- Consumes `booking.confirmed` and sends one email and one SMS per booking
- Redis `SET NX` per booking and channel, so a redelivered event doesn't send twice; the key is released when sending fails, so the retry can send
- Send errors are rethrown, so Kafka retry with backoff and the DLT apply

## At a glance

| | |
|---|---|
| Port | 8094 |
| API | none (Kafka only) |
| Database | none (Redis for dedupe keys) |
| Tables | none |
| Kafka | consumes `booking.confirmed` |
| Calls | none |

## Configuration

- All settings in `src/main/resources/application.yaml` (no `config-repo` file; config-server is optional for this service).
- `MAIL_USERNAME`, `MAIL_APP_PASSWORD`, `MAIL_FROM`; `TWILIO_ENABLED` (default false) and the Twilio keys
- Email template: `src/main/resources/templates/email/`
- Every variable: [docs/environments.md](../../../docs/environments.md).

## Run and verify

```bash
# compile (from backend/)
mvn -q compile -pl services/notification-service -am

# run: start config-server and service-registry first (docs/runbooks/local-stack.md)
cd backend/services/notification-service && mvn spring-boot:run
```
