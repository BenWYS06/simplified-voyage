# Contributing

## Branches

- `main` is always the latest reviewed state. No direct commits.
- One branch per piece of work, created from an up-to-date `main`:

| Prefix | For | Example |
|---|---|---|
| `feat/` | New behaviour | `feat/booking-step-2-seat-hold` |
| `fix/` | Bug fix | `fix/payment-duplicate-ipn` |
| `docs/` | Documentation only | `docs/architecture` |
| `chore/` | Build, config, tooling | `chore/docker-stack` |
| `refactor/` | Restructure without behaviour change | `refactor/seat-repository` |

## Commits

Format: `type(scope): summary`

- **type:** `feat`, `fix`, `docs`, `chore`, `refactor`, `test`
- **scope:** the service or area: `booking`, `seat`, `payment`, `notification`, `common-lib`, `gateway`, `frontend`, `docker`, …
- **summary:** imperative, lower case, no trailing period, under 72 characters

Below the subject, a **body** (wrapped at 72 characters) says **why** the change was needed and **what** it does. Skip the body only for trivial changes.

```text
feat(seat): hold seats with a single conditional update

Two users could hold the same seat because the old loop checked and
wrote each seat separately. One UPDATE ... WHERE id IN (...) AND free
locks the rows in primary-key order, so the loser gets 0 rows (409)
and overlapping requests can't deadlock.
```

Keep commits small and focused: one logical change, usually 1–3 files. Commits written with Claude Code carry a `Co-Authored-By: Claude` trailer.

After a PR is merged, delete its branch on GitHub.

## Pull requests

1. Push the branch and open a PR into `main` using the template.
2. Describe what changed, why, and **exactly how it was verified**.
3. The author reviews the diff on GitHub, then merges with a merge commit.
4. After merging: `git checkout main && git pull`, then start the next branch.

## Before opening a PR

- [ ] Backend compiles: `mvn -q compile` from `backend/` (or `-pl <service> -am`)
- [ ] Frontend builds if touched: `npm run build` from `frontend/`
- [ ] Every caller of a changed endpoint / DTO / event updated (see `.claude/rules/api-contract.md`)
- [ ] DB changes are new `V{n}__*.sql` files
- [ ] No secrets; new env vars added to `docker/.env.example` as placeholders
- [ ] Docs updated (PROGRESS, README, CHANGELOG) where relevant

## New here?

[ONBOARDING.md](ONBOARDING.md) gets the stack running. Bigger changes start with a design in `docs/specs/` and a plan in `docs/plans/`; bugs follow the root-cause steps in `.claude/skills/fix-issue/SKILL.md`. Security problems: see [SECURITY.md](SECURITY.md), not a public issue.

## How this repo uses AI

See [docs/AI_WORKFLOW.md](docs/AI_WORKFLOW.md).
