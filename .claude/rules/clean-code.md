---
paths:
  - "backend/**/*.java"
  - "frontend/src/**"
---

# Clean code rules

**File size.** Services and controllers stay under ~500 lines, React pages and components under ~400. Past that, split by responsibility (e.g. `BookingServiceImpl` → `BookingCompensator` for the failure paths) instead of adding more private methods.

**Controllers are thin.** Parse the request, read identity from the trusted headers, call one service method, return a DTO and the right status. No business rules, no repository calls.

**One responsibility per service class.** If a class needs many unrelated dependencies or runs several workflows, split it. Name the new class after what it does (`OutboxRelay`, `BookingExpiryJob`), not `Helper` / `Utils`.

**Comments say why.** A short comment on each new class, method group or non-obvious rule stating its purpose or the failure it prevents (a race, a duplicate event). Don't narrate what the code obviously does.

**Errors are visible.** No empty `catch`, no catch-and-log that hides a failure the caller must know about. Throw the `common-lib` exception that matches (404, 403, 409).

**No dead code.** Delete unused methods, endpoints, thunks and imports in the same change that makes them unused (after an impact scan). Don't leave commented-out blocks.

**React.** Components render; data loading and API calls live in thunks. Extract a sub-component when JSX nests deeper than ~5 levels or a helper renderer passes ~100 lines.

**When splitting files:** update every import, keep public names the same unless the plan says otherwise, and compile / build right after.
