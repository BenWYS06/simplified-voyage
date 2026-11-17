# Remove the subscription service

**Goal:** drop `subscription-service`, an empty placeholder, so the build, config and docs list only services the platform uses.

**Scope:** `backend/services/subscription-service/`, `backend/services/pom.xml`, `config-repo/subscription-service.yml`, `CLAUDE.md`, `README.md`, `docs/architecture.md`, `CHANGELOG.md`, `docs/decisions.md`.
**Out of scope:** merging pricing / ancillary into seat-service or location into airline-core (considered, kept separate; see ADR-011).
**Contract changes:** none. Impact scan: no Feign client, gateway route, Kafka topic, compose service or frontend page refers to subscription-service.

## Tasks

- [x] 1. Impact scan for `subscription` across `backend/`, `config-repo/`, `docker/`, `frontend/src/`: only the module itself and its config file.
- [x] 2. Delete the module and its `<module>` entry.
- [x] 3. Delete `config-repo/subscription-service.yml`; remove it from the `CLAUDE.md` services table and the README tree and services table.
- [x] 4. Remove it from the architecture diagram; CHANGELOG says 13 services.
- [x] 5. ADR-011: why it was removed and why the other small services stay separate.

## Verification

- `grep -ri subscription backend config-repo docker frontend/src` returns nothing.
- `mvn -q compile` for all modules.

## Result

Merged in PR #16 (2026-9-31).
