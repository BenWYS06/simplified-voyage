# Design specs

A spec says **what** we build and **why**: intent, goals, non-goals, the options considered and the choice. It is written and agreed before the plan. The plan ([../plans/](../plans/README.md)) then says **how**, task by task.

- File name: `YYYY-MM-DD-<topic>-design.md`, dated the day the design was agreed.
- A spec is not rewritten when the design changes later. It gets a **Status** line (`Superseded by …`) and the new spec links back to it, so the history of the decision stays readable.
- Lasting decisions also go to [../decisions.md](../decisions.md) as ADRs.
- The specs dated 2026-10-11 and 2026-10-12 were written up on 2026-11-03 from the history in `PROGRESS.md` and the agreed design. Their date is when the design was agreed, not when the file was written. Specs from then on are written before the work.

| Date | Spec | Status |
|---|---|---|
| 2026-10-11 | [Booking refactor, first design](2026-10-11-booking-refactor-v1-design.md) | Partly superseded by the 2026-10-21 saga design |
| 2026-10-11 | [Flyway migrations in every service](2026-10-11-flyway-migrations-design.md) | Done |
| 2026-10-12 | [VNPay replaces Razorpay / Stripe](2026-10-12-vnpay-payment-design.md) | Done (extended in build step 7) |
| 2026-10-21 | [Booking saga design](../booking-refactor/DESIGN.md) (agreed 2026-10-17 → 2026-10-21) | Done, built in steps 1–10 |
| 2026-10-31 | Observability and performance: design inside the [plan](../plans/2026-10-31-observability-and-performance.md) (tool choice, phases, out of scope) | Approved, not started |
