---
name: fix-issue
description: Fixes a bug from its root cause - reproduce or locate the symptom, find why it happens, fix it without hiding errors, verify, and record it. Use when the user reports an error, a stack trace, a failing test or wrong behaviour.
---

# Fix an issue

Issue: $ARGUMENTS

## 1. Understand the symptom
- Read the full error: stack trace, HTTP status, which service logged it, which request caused it.
- Find the entry point (controller, consumer, job, FE thunk) and follow the call path.

## 2. Find the root cause
- Explain **why** it happens before changing code. Typical causes in this project:
  - entity and migration out of sync (`ddl-auto: validate` fails at startup);
  - a Feign fallback hiding the real error;
  - a status update without a status condition (lost update);
  - a consumer that isn't idempotent;
  - an FE field name that doesn't match the DTO.
- Look for the same pattern elsewhere; if other places have the same bug, list them.

## 3. Fix
- Fix the cause, not the symptom. No empty `catch`, no `try/catch` that only logs, no returning fake data from a fallback.
- Keep the fix inside the bug's scope. If the API contract must change, stop and ask (`.claude/rules/api-contract.md`).
- A schema fix is a new `V{n}__*.sql`, never an edit of a migration that already ran.

## 4. Verify
- `/verify-build` (impact scan + compile, `npm run build` for the frontend).
- If a test covers the area, run it. A race or duplicate bug gets a regression test where the module has Testcontainers support.

## 5. Record
- Commit as `fix(<scope>): <what was wrong>`, with the root cause in the body.
- Booking-flow bug → add a line to `docs/booking-refactor/PROGRESS.md`. A bug that changes a decision → ADR in `docs/decisions.md`.
- Report to the user: root cause, the fix, how it was verified, and other places with the same pattern.
