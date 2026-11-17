---
name: add-feature
description: Takes a new feature (outside the booking refactor) from request to merged PR - spec and plan first, wait for approval, then implement task by task with verify and review. Use when the user asks to add or change functionality, e.g. "add booking cancellation emails", "new admin report page".
---

# Add a feature

The booking refactor has its own plan (`docs/booking-refactor/`) and uses `/implement-step`. Everything else goes through this skill.

## 1. Understand
- Read `CLAUDE.md`, the rules for the folders involved, and the code the feature touches.
- If the request can be read two very different ways, ask before planning.

## 2. Write the plan
Larger features need a design first: write `docs/specs/YYYY-MM-DD-<feature-name>-design.md` (intent, goals, non-goals, the options and the choice) and add it to `docs/specs/README.md`. Then the plan. The `feature-planner` agent can draft the plan from the code.

Create `docs/plans/YYYY-MM-DD-<feature-name>.md`:

```markdown
# <Feature name>

**Goal:** what the user can do after this, in one or two sentences.
**Scope:** services and FE areas touched. **Out of scope:** what this plan deliberately leaves out.
**Contract changes:** endpoints / DTOs / events / migrations, and who is affected (from `impact-scanner`).

## Tasks
- [ ] 1. <small, verifiable task>: files, what changes
- [ ] 2. ...

## Verification
How each task is checked (compile, build, scan; manual check if needed).
```

Add a row to `docs/plans/README.md` with status **PENDING**.

## 3. STOP for approval
Summarise the plan in a few lines and **wait for the user's OK**. Change the plan if asked. No code before approval.

## 4. Implement task by task
- Branch `feat/<feature-name>` from an up-to-date `main`.
- For each task: write the code (short purpose comments on new pieces), run `/verify-build`, tick the task in the plan.
- Bigger features can be split: `backend-dev` agent for the backend tasks, then `web-dev` for the frontend tasks, both working from the plan's API contract.
- Contract change → fix every caller in the same task (`.claude/rules/api-contract.md`).
- Touches seats, booking status, payments, consumers or jobs → run the `concurrency-reviewer` agent.

## 5. Finish
- Run the `qa-agent` (build + contract + config check) and the `code-reviewer` agent on the whole diff; fix what they find (or note why not).
- A real design decision made along the way → add an ADR to `docs/decisions.md`.
- `/progress-update` → plan status **DONE** in `docs/plans/README.md`.
- `/ship-pr`.
