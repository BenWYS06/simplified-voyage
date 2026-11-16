# Security policy

## Supported versions

| Version | Supported |
|---|---|
| `main` (latest) | ✅ |
| Older tags and branches | ❌ |

This is a learning and portfolio project with no production deployment. Fixes land on `main` only.

## Reporting a vulnerability

Please **don't open a public issue** for a security problem. Use GitHub's private vulnerability reporting (**Security → Report a vulnerability** on the repository page).

Include what is affected (service or page), the impact, and steps to reproduce. You can expect an answer within a week.

## How the system is protected

- **Authentication at one place.** Keycloak (realm `ben-airline`) issues RS256 JWTs. Only the API gateway validates them, against Keycloak's public keys. See [docs/authentication.md](docs/authentication.md).
- **Trusted identity headers.** The gateway removes any incoming `X-User-*` headers and sets `X-User-Id` / `X-User-Roles` from the validated token. Services read the user only from these headers.
- **Roles and ownership.** `@RequiredRoles` on service endpoints (`ROLE_CUSTOMER`, `ROLE_AIRLINE_OWNER`, `ROLE_SYSTEM_ADMIN`), plus owner checks where a user reads their own data (403 for someone else's booking).
- **Internal APIs.** Service-to-service endpoints live under `/internal/**` and are never routed by the gateway. The gateway → user-service call is also checked with `X-Internal-Api-Key`.
- **Payments.** The VNPay IPN is accepted only after the HMAC-SHA512 signature check (constant-time compare) and an amount check, under a row lock, and only for a `PENDING` payment. The browser return URL is display-only (except the user-cancel code 24).
- **Data integrity under concurrency.** Seat holds, booking creation and payment results are protected by conditional updates, unique indexes and row locks, and proven with concurrency tests (see [ADR-005 to ADR-008](docs/decisions.md)).
- **Secrets.** Real values only in `docker/.env` (git-ignored) or the environment. `docker/.env.example` and `config-repo/` hold placeholders and `${ENV}` references only.

## Known limitations

- Local development runs over plain HTTP. Put a TLS-terminating proxy in front before exposing anything.
- The frontend stores the access token in `localStorage`, and its login screens still target the original project's endpoints; moving the frontend to a Keycloak OIDC login (PKCE) is on the [TODO list](TODO.md).
- No rate limiting at the gateway yet.
- The `docker-compose.yml` databases use simple default passwords and bind to `127.0.0.1` only; they are for local use.
