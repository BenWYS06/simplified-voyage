# AI-Assisted Development Workflow

> How this repository is planned, built and reviewed with **Claude Code** (Anthropic's CLI agent).
> Claude does most of the typing; the developer owns the design decisions, approves every step and reviews every PR.

## 1. Roles

| Who | Does |
|---|---|
| **Developer** | Sets goals, agrees the design section by section, approves each step before code is written, reviews and merges PRs |
| **Claude Code** | Reads the project state, proposes designs, writes code and docs, verifies with scan + compile, commits and drafts PRs |
| **Rules** (`CLAUDE.md`, per-folder `CLAUDE.md`, `.claude/rules/`) | Project conventions Claude loads automatically, so they never need repeating |
| **Agents** (`.claude/agents/`) | Focused helpers Claude can call: planning, backend and frontend implementation, QA, code / concurrency / performance review, impact scan |
| **Skills** (`.claude/skills/`) | Packaged procedures for recurring work: features, refactor steps, bugs, new services, QC, recording progress, handoffs, shipping a PR |

## 2. The loop

```text
① DESIGN    discuss the problem → spec in docs/specs/ (refactor: DESIGN.md, agreed section by section)
② PLAN      docs/plans/YYYY-MM-DD-<name>.md: tasks, contract, verification   (/add-feature, feature-planner)
            (refactor: PLAN.md + LEARNING-PATH.md, one concept per step)
③ APPROVE   Claude summarises the step or plan → developer says OK. No code before this.
④ BUILD     feature branch; backend-dev / web-dev per task, a purpose comment at each new piece
⑤ VERIFY    /verify-build (impact scan + compile) · CI check on the PR · qa-agent · code-reviewer, concurrency-reviewer, perf-reviewer when relevant
⑥ RECORD    /progress-update → plan or PROGRESS ticked · ADR in decisions.md for real decisions · CHANGELOG when merged
⑦ SHIP      /ship-pr → diff reviewed by the developer → small commits, push, PR → developer merges, branch deleted
⑧ HANDOFF   /session-handoff at the end of a long session → docs/handoff/SESSION-HANDOFF-<date>.md
```

Nothing is coded before ③. Nothing is called done before ⑤ has actually run. Plans change: when a design turns out wrong, the old spec gets a "Superseded by" status and the new one explains why (see [specs/2026-10-11-booking-refactor-v1-design.md](specs/2026-10-11-booking-refactor-v1-design.md)).

## 3. Where state lives

| What | Where |
|---|---|
| Rules Claude must follow | `CLAUDE.md`, `frontend/CLAUDE.md`, `backend/services/{booking,seat,payment}-service/CLAUDE.md`, `.claude/rules/*.md` |
| Guardrails the harness enforces | `.claude/settings.json`: build, read-only git/docker commands and normal push allowed, checkout / merge / rebase ask first, secrets and destructive commands (force push, `reset --hard`, `down -v`) denied; hooks: `protect-migrations.js` blocks edits to existing Flyway migrations, `show-open-plans.js` lists unfinished plans at session start |
| Designs (what and why) | `docs/specs/` (index), `docs/booking-refactor/DESIGN.md` |
| Plans (how, task by task) and their status | `docs/plans/YYYY-MM-DD-<name>.md`, index in `docs/plans/README.md` |
| Refactor goals, build order, progress | `docs/booking-refactor/PLAN.md`, `LEARNING-PATH.md`, `PROGRESS.md` |
| Where the work stands right now | newest `docs/handoff/SESSION-HANDOFF-<date>.md` |
| Open work, milestones | `TODO.md`, `docs/roadmap.md` |
| Architecture decisions | `docs/decisions.md` |
| Contracts | `docs/api-spec.md` |
| Release notes | `CHANGELOG.md` |

A new session needs only `CLAUDE.md` + the newest handoff (or PROGRESS "Current status") + the section of the design it works on.

## 4. Skills and agents

| Name | Type | When |
|---|---|---|
| `add-feature` | skill | Any feature: spec (if large) → plan → approval → task by task → QA + review → ship |
| `implement-step` | skill | "Next step" of the booking refactor: overview → approval → code → self-check |
| `fix-issue` | skill | A bug: find the root cause, fix it, verify, record |
| `new-service` | skill | A new microservice wired like the others (module, config, gateway, Docker, docs) |
| `verify-build` | skill | After any change: grep impact across BE/FE/config, `mvn compile`, `npm run build` |
| `qc-check` | skill | Before a release or a big feature: build, tests, drift scan, report |
| `progress-update` | skill | End of a step or feature: tick the plan, PROGRESS, CHANGELOG |
| `session-handoff` | skill | End of a long session: write the handoff file |
| `ship-pr` | skill | Work verified: commit in small steps, push, draft the PR |
| `feature-planner` | agent | Turns a request into a plan from the real code |
| `backend-dev` / `web-dev` | agent | Implement the backend / frontend tasks of an approved plan |
| `qa-agent` | agent | Build + contract + config check of a finished feature |
| `code-reviewer` | agent | Before committing: correctness, security, project patterns |
| `concurrency-reviewer` | agent | Seat holds, booking status, payment results, consumers, jobs |
| `perf-reviewer` | agent | N+1 queries, Feign calls in loops, missing indexes, long transactions |
| `impact-scanner` | agent | Before renaming or removing an endpoint, DTO field, enum, event or config key |

## 5. Principles

- **Design before code.** Every pattern (idempotency, row locks, saga, outbox) is agreed first, with the failure cases it handles.
- **One concept per step.** The app compiles after every step, and each step is reviewable on its own.
- **Verify, don't assert.** A step is done when scan + compile (and tests where they exist) actually passed. Anything unverified is written down as unverified.
- **Keep contracts.** API shapes the frontend and other services use don't change silently (`.claude/rules/api-contract.md`).
- **Write it down.** Designs to specs, decisions to ADRs, progress to plans and PROGRESS, the current state to a handoff. A session can be cleared at any time without losing context.
