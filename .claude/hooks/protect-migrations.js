// PreToolUse hook: blocks edits to Flyway migrations that already exist.
// A migration that has run must never change (Flyway checksum, other databases);
// a schema change is a new V{n}__*.sql file. Creating a new migration is allowed.
const fs = require("fs");

let input = "";
process.stdin.on("data", (chunk) => (input += chunk));
process.stdin.on("end", () => {
  let filePath = "";
  try {
    filePath = JSON.parse(input).tool_input?.file_path ?? "";
  } catch {
    process.exit(0); // not our business if the payload can't be read
  }

  const isMigration = /[\\/]db[\\/]migration[\\/]V\d+__[^\\/]+\.sql$/i.test(filePath);
  if (isMigration && fs.existsSync(filePath)) {
    console.error(
      `Blocked: ${filePath} is an existing Flyway migration. ` +
        "Add a new V{n}__<change>.sql file instead (.claude/rules/db-migrations.md). " +
        "If this migration has never run anywhere, the developer can edit it by hand."
    );
    process.exit(2); // exit 2 = deny the tool call and show the reason to Claude
  }
  process.exit(0);
});
