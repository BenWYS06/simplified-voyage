---
name: session-handoff
description: Writes a session handoff file (docs/handoff/SESSION-HANDOFF-YYYY-MM-DD.md) so the next session, or another person, can continue without re-reading the repo - state, decisions, open work, gotchas. Use before /clear, at the end of a long session, or when the user says "handoff" or "wrap up".
---

# Session handoff

`PROGRESS.md` and the plan files record **what was built**. A handoff records **where we are right now** and what the next session must know. Write one at the end of a long or multi-topic session.

## 1. Collect
- `git status`, `git log --oneline -15`, the branch in use.
- `docs/booking-refactor/PROGRESS.md` "Current status", `docs/plans/README.md` statuses.
- Decisions made in this session (and whether they reached `docs/decisions.md`).

## 2. Write `docs/handoff/SESSION-HANDOFF-<today>.md`

```markdown
# Session handoff: YYYY-MM-DD

> Read this at the start of the next session, after CLAUDE.md.

## 1. Where things stand
- Current work and its status (step / plan / branch).
- What is merged, what is on a branch, what is only local.

## 2. Done this session
- Item: files, how it was verified.

## 3. Decisions
- Decision: why. (ADR-0xx if recorded.)

## 4. Open work, in order
1. Next concrete action.

## 5. Gotchas
- Things that cost time and will again (env, ports, build flags).

## 6. Read first next time
- The 2–4 files that matter for the next task.
```

## 3. Index and confirm
- Add the file to the table in `docs/handoff/README.md` (newest first).
- If a decision isn't in `docs/decisions.md` yet, add the ADR now.
- Show the user the "Open work" list and confirm it is right.
