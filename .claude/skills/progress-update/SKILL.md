---
name: progress-update
description: Records finished work in the right place - booking refactor steps go to PLAN.md / PROGRESS.md, other features to their plan file and docs/plans/README.md - and adds a CHANGELOG line when work is merged. Use at the end of every build step, feature or work session.
---

# Record progress

First decide which kind of work this was.

## A. Booking refactor step (`/implement-step`)

1. **`docs/booking-refactor/PLAN.md`:** tick the checkboxes of the work that is done. New decision → add it to "Decisions" (§2).
2. **`docs/booking-refactor/PROGRESS.md`:** add an entry **directly under "Current status"** (newest first):

   ```markdown
   ## YYYY-MM-DD: Build step N, <concept>

   - `path/to/File.java`: what changed and why (one line)
   - Decisions: anything decided along the way
   - Verified: what was checked (compile, build, scan), and what was NOT verified
   - Not touched on purpose: anything deferred to a later step
   ```

   Then update "Current status": step just finished, next step, what is waiting on the user.

## B. Feature (`/add-feature`)

1. **`docs/plans/YYYY-MM-DD-<feature>.md`:** tick the finished tasks; add a short "Result" section at the end (files changed, verification, anything deferred).
2. **`docs/plans/README.md`:** update the plan's status (IN PROGRESS / DONE).

Never write feature progress into the refactor's PROGRESS.md.

## C. Indexes and decisions (both kinds)

- A design was agreed or replaced → `docs/specs/README.md` (new row, or the old spec's status set to "Superseded by …").
- A real decision was made → ADR in `docs/decisions.md`.
- An open item was finished or found → tick or add it in `TODO.md`; a milestone finished → `docs/roadmap.md`.
- An endpoint, env variable or port changed → `docs/api-spec.md` / `docs/environments.md` and the service `README.md`.

## D. CHANGELOG (both kinds)

Only when the work is merged into `main`: add a short user-facing line under `## Unreleased` in `CHANGELOG.md` (what now works differently, not how).

## Report
Show the user what was written.
