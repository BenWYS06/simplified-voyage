---
paths:
  - "backend/**/src/test/**"
---

# Testing rules

**What gets a test.** Every race or duplicate the design handles gets a concurrency test (seat hold, idempotent create, duplicate IPN, duplicate `payment.completed`, expiry job vs payment). Plain CRUD doesn't need one.

**Unit tests.** Each core service ([list](../../docs/plans/2026-11-05-core-service-tests.md)) gets JUnit 5 + Mockito + AssertJ tests for its rules: validation, branches, status changes, errors. Same style as `UserServiceImplTest`: no Spring, no database. Don't test a race with mocks; that belongs in a concurrency test.

**Real databases.** Concurrency tests run on Testcontainers (MySQL 8.0 / PostgreSQL 16) through the shared `support/` classes in each service:
- `TestcontainersConfig`: containers with `@ServiceConnection`, so Flyway runs the real migrations and Hibernate validates the schema.
- `IntegrationTest`: base class; no config-server, no Eureka, scheduled jobs off (`app.scheduling.enabled=false`), so the test calls jobs itself.
- `Concurrently`: releases all threads at the same moment and returns each result or exception.

**Assertions that prove the rule.** Assert the final DB state and the counts (exactly one winner, N-1 × 409, one outbox row), not only "no exception". Repeat racy tests (`@RepeatedTest`) so a lucky interleaving can't pass by chance.

**Other services are mocked** with `@MockitoBean` on the Feign client. Don't start other services in a test.

**Running.**
```bash
cd backend
mvn test -am -pl services/seat-service,services/booking-service,services/payment-service -Dsurefire.failIfNoSpecifiedTests=false
```
Docker must be running. Keep `-am`, or Maven picks an old `common-lib` jar from `~/.m2`. "Unresolved compilation problem" means a class compiled by the IDE mid-edit: run `mvn clean`.

**Data.** Each test creates its own rows with random ids or unique values (e.g. a new `vnp_TransactionNo` per run), so tests don't depend on order.
