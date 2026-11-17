// SessionStart hook: prints every plan that isn't finished, so a new session always sees open work.
// docs/plans/README.md is the status of record; this only reads it. A problem here must never block a session.
const fs = require("fs");
const path = require("path");

const CLOSED = new Set(["DONE", "DROPPED"]);

try {
  const root = process.env.CLAUDE_PROJECT_DIR || process.cwd();
  const index = fs.readFileSync(path.join(root, "docs", "plans", "README.md"), "utf8");

  const open = index
    .split(/\r?\n/)
    .filter((line) => line.startsWith("| ["))
    .map((line) => line.split("|").map((cell) => cell.trim()))
    .map(([, plan, , status]) => ({ name: plan.match(/\[(.+?)\]/)?.[1] ?? plan, status }))
    .filter(({ status }) => !CLOSED.has(status));

  if (open.length > 0) {
    console.log("Open plans (docs/plans/README.md):");
    open.forEach(({ name, status }) => console.log(`- ${status}: ${name}`));
  }
} catch {
  // No index or an unexpected format: start the session without the list
}
process.exit(0);
