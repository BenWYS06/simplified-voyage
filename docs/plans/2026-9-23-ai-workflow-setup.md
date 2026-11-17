# AI-assisted workflow setup

**Goal:** make the way this repo is built with Claude Code explicit and repeatable: project rules Claude loads automatically, review agents, skills for the recurring steps, and contributor docs, so every change follows design → approval → code → verify → record → PR.

**Scope:** `CLAUDE.md` (root + booking-service), `.claude/` (rules, agents, skills, settings), `docs/AI_WORKFLOW.md`, `CONTRIBUTING.md`, `.github/` templates, README section, `.gitignore`.
**Out of scope:** CI, hooks, changes to application code.
**Contract changes:** none.

## Tasks

- [x] 1. Root `CLAUDE.md`: project summary, working mode (overview → OK → code → self-check → record), critical rules, services and ports, build and verify commands. Service context in `backend/services/booking-service/CLAUDE.md`.
- [x] 2. Folder rules in `.claude/rules/`: `spring-boot`, `db-migrations`, `api-contract`, `kafka-events`, `frontend` (each scoped with `paths:`).
- [x] 3. Agents: `code-reviewer`, `concurrency-reviewer`, `impact-scanner`.
- [x] 4. Skills: `implement-step`, `verify-build`, `progress-update`, `ship-pr`, `add-feature`.
- [x] 5. `.claude/settings.json`: allow compile / build / read-only git; deny reading `.env`, force push, `reset --hard`, `git clean`. `.claude/settings.local.json` git-ignored.
- [x] 6. `docs/AI_WORKFLOW.md` (roles, the loop, where state lives), `docs/plans/README.md` (feature plan index).
- [x] 7. `CONTRIBUTING.md` (branches, commit format with body, PR flow, delete branch after merge), PR template, bug / feature issue templates.
- [x] 8. README: "Built with Claude Code" paragraph.

## Verification

- A fresh session given only `CLAUDE.md` + PROGRESS "Current status" picks the right next step.
- Rules load only for their folders (checked by editing a migration and a React page).
- No secret is readable through the allowed commands.

## Result

Merged in PRs #7 and #8 (2026-9-23 / 2026-9-24). Extended on 2026-10-03 with the full feature loop agents and skills ([plan](2026-10-03-project-docs.md)).
