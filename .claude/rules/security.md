---
paths:
  - "backend/cloud/api-gateway/**"
  - "backend/**/controller/**"
  - "backend/common-lib/**/security/**"
  - "config-repo/**"
  - "docker/**"
---

# Security rules

**Who the caller is.** Keycloak issues the JWT; only the gateway validates it. The gateway strips any incoming `X-User-*` headers and sets trusted `X-User-Id` / `X-User-Roles` from the token. Services read the user **only** from these headers, never from a body or query parameter. Details: `docs/authentication.md`.

**Who may do what.** Every non-public endpoint has `@RequiredRoles` (`ROLE_CUSTOMER`, `ROLE_AIRLINE_OWNER`, `ROLE_SYSTEM_ADMIN`). Endpoints that return one user's data also check ownership (403 for someone else's booking), not only the role.

**Internal endpoints.** Service-to-service APIs live under `/internal/**`. The gateway never routes them; don't add a route for them.

**Public routes.** Only routes that must work without a login are public: today `/actuator/health`, `/actuator/info`, `/fallback` and the VNPay IPN (`SecurityConfig` in the gateway). Everything else needs a valid JWT. A public route has its `X-User-*` headers removed by the gateway. Adding one needs a reason in the PR.

**Payments.** The VNPay IPN is trusted only after the HMAC-SHA512 signature check (constant-time compare) and the amount check. The return URL never decides a payment, except the documented cancel case (code 24).

**Secrets.**
- Real values only in `docker/.env` (git-ignored) or the shell environment.
- `docker/.env.example`, `config-repo/*.yml` and `application.yaml` hold placeholders or env references (`${VNPAY_HASH_SECRET:}`), never real values.
- Don't print secrets in logs, test output or PR descriptions.

**Input.** Validate request DTOs with `@Valid` and constraint annotations. Reject unexpected enum values with 400 instead of trusting the client (e.g. admins may set a seat only to `AVAILABLE` / `BLOCKED`).
