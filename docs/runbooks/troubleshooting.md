# Runbook: troubleshooting

Problems that already happened in this project, with the fix that worked.

| Symptom | Cause | Fix |
|---|---|---|
| MySQL container won't start, `port is already allocated` on 3306 | Another MySQL runs on the machine | Stop it, or change the host port in `docker-compose.dev.yml` (and the service's `DB_*` URL) |
| `NoClassDefFoundError` for a common-lib class (e.g. `BookingCancelledEvent`) in tests | Maven used an old `common-lib` jar from `~/.m2` | Build with `-am`: `mvn test -am -pl services/<service>` |
| `Unresolved compilation problem` at runtime | A class compiled by the VS Code Java extension mid-edit | `mvn clean compile` |
| Service fails at start: `Schema-validation: missing column` / `wrong column type` | Entity changed without a migration (Hibernate runs `validate`) | Add a new `V{n}__*.sql` that matches the entity; never edit an old migration |
| Flyway: `Migration checksum mismatch` | A migration that already ran was edited | Revert the edit and add a new migration. Local only: drop the DB and let Flyway rebuild it |
| Service starts with defaults, config changes ignored | config-server not running or reading GitHub `main`, not your local files | Start config-server first; use `docker-compose.local-config.yml` for local config |
| `503` from the gateway for one route | Service not registered in Eureka yet, or down | Check http://localhost:8761; wait ~30 s after start |
| `401` on every call | Token issuer doesn't match `KEYCLOAK_ISSUER_URI` (e.g. `127.0.0.1` vs `localhost`) | Use the same host in the browser, `KEYCLOAK_HOSTNAME` and the gateway config |
| `403` on an endpoint you expect to reach | Wrong role, or not the owner of the resource | Check `@RequiredRoles` on the endpoint ([api-spec.md](../api-spec.md)) and `X-User-Roles` |
| VNPay IPN never arrives locally | VNPay can't reach `localhost` | Use ngrok, or simulate the IPN as in [VNPAY.md](../booking-refactor/VNPAY.md) |
| IPN answers `97` | Wrong `VNPAY_HASH_SECRET`, or parameters changed in transit | Check the env value; sign over the exact received parameters |
| Testcontainers: `Could not find a valid Docker environment` | Docker Desktop not running | Start Docker, rerun |
| Kafka consumer keeps retrying, then a message appears in `<topic>.DLT` | The handler throws for that message (bad data, dependency down for ~5 min) | Read the DLT message, fix the cause, re-send it; consumers are idempotent |
| `vite build` fails after pulling | Dependencies changed | `npm install` (or `npm ci`) in `frontend/` |

Something new cost you more than 15 minutes? Add a row here in the same PR as the fix.
