# MC14 — The Docs Say What The Code Does

```json
{
  "$schema": "issue-1.json",
  "id": "MC14",
  "title": "The Docs Say What The Code Does",
  "state": "Review",
  "size": "S",
  "priority": "Highest",
  "rank": 1,
  "blockedBy": [],
  "covers": "Every sentence of the user-facing documentation - the READMEs, `doc/` and the unreleased changelog - is borne out by the code on `main`; a doc that is wrong is fixed, a code that is wrong is filed as a bug. About 170 claims read, four findings, one confirmed and fixed, none filed as a bug."
}
```

**Why this size.** About 210 lines of documentation and ten changelog entries in one repository, read against one
module's code, with no behavior change: a few hours.

**What must be true.** A person who reads `porelo-mcp`'s documentation finds every command, flag, path, default, state,
message and behavior it names in the program as it is built on `main`.

## Plan

- A reader of another model than the one that checks reads `README.md`, `server/README.md`, `doc/binding.md` and the
  entries in `changelog/unreleased/` page by page against the code under `server/src/main`, and lists every sentence the
  code does not bear out, with the document's file and line, the claim, and the code's file and line that contradicts
  it. `doc/decisions.md` and `issues/` are history and are not read.
- Each finding is checked in the code by the repository's agent; only a confirmed one is acted on.
- Where the code is right, the document is changed. Where the document is right and the code wrong, a bug is filed as an
  issue of its own, in Backlog with its size, and the document stays as it is. No behavior changes here.
- Level: no test is written. A sentence that reads wrong is fixed, not guarded; the tests tagged `documents` and the
  document checks run on the change.

## Acceptance

- The finding list has every finding marked confirmed or rejected, with the code read for each.
- Every confirmed finding is fixed in the documentation or filed as its own bug.
- `check-citations`, `check-commits`, `check-readmes`, `check-shared`, the tests tagged `documents` and
  `issues check issues` pass on the commit.

## Findings

- `server/README.md`: "depends on `porelo-wire` alone" - confirmed: `server/pom.xml` also takes `org.jspecify:jspecify`,
  the annotations the code is marked with. Fixed in the README.
- `doc/binding.md`, `--check` "the methods it is offered" - rejected: `Main` prints every tool of the daemon's
  `Tasks1` description, which is what the daemon offers; the stdio sets are a separate answer and not named there.
- `doc/binding.md`, a notification refused with `-32022` for a version not spoken - rejected: the sentence before
  requires `MCP-Protocol-Version` and `Mcp-Method`; with them present, `Era.of` answers an unspoken version with
  `-32022`, and a missing or differing header with `-32020`, as the document says.
- `doc/binding.md` and the stdio changelog entry, reading, writing and destructive methods - rejected: `porelo-mcp`
  offers `reading` and `writing` and refuses `never` as `McpMethods` names them (`Main`, `McpMethods.offered`), and the
  document says the sets are the daemon's.
