---
name: porelo-testing
description: Porelo test rules: the pyramid, red first, what a test guards, independence, flaky tests, reruns after a failure, document checks before a hand-over. Use when writing, running or judging tests.
---

> **BEGIN Shared Skill porelo-testing** · sha256 `95cffea0cd584f87` · changed 2026-11-21T16:39Z

# porelo-testing

## Testing and machines

- **The documents' checks run locally before every handover, and on GitHub once a day.** Every
  change is handed over with `check-shared`, `check-citations`, `check-commits`, `check-readmes`,
  `check-doc-site`, `check-actions` and the tests that read documents green on the host, on the
  exact commit, and the code it changed read against the repository's skills. The named *Shared rules check* workflow runs
  on a daily schedule, on a pull request and by hand, never on a push; the required document checks
  on push and pull request run in a suitable light workflow.
- **The issues of one repository in one sprint share one full workflow run on the stack's top commit,
  started once the last of them is ready**, with the unit suite and quick checks passing on each commit;
  never one run per issue. Split only for an issue another agent waits on, a failed central Build or a
  required individual red run. The handover names every issue, the tested top commit, and which checks
  ran where with what result.
- **After a failed run, rerun the failed step and the tests of the area the fix changes; the unit
  suite still runs whole once before the handover.** The handover names the final commit, what was rerun and why that covers
  the fix, and keeps checks passed earlier apart from those rerun on it. A failure in program logic is
  reproduced and fixed in the JVM through the same entry point; a native build or a VM is used only for a
  failure that depends on the image or the machine, and then only the affected executable and the failing
  scenario.
- **A change to what a command answers searches the acceptance features for the old answer**; a line it
  alters checks the answer's meaning, not its text, in the same commit.
- **A workflow step replayed locally runs on a clean build (`clean`, or a fresh clone), as the
  runner's checkout does.** What an earlier build left in `target/` is read too and fails, or passes,
  what the runner would not.
- **Before a long run, say how long it will take**, from a measured time, or say that it is a guess.
- **A test that waits on an agent, a model or any paid service fails fast on a loop**: it stops as
  soon as the same failure repeats, instead of waiting out its time, and names what repeated. A run
  seen looping is reported at once, so the operator can cancel it.
- **Tests follow the pyramid: many unit tests, fewer integration tests, the fewest end-to-end runs.** A flow - its
  states, choices, limits and failure paths - is built as plain logic behind small interfaces and proven by unit tests
  over fakes that run in seconds; the real pieces are then tested narrowly on that core, and only what nothing lower
  can show goes to an integration test, a VM or a rented machine.
- **A plan names each test's level before the build; one that needs a VM to see a flow work is planned wrong.** A
  defect found high up is reproduced at the lowest level that can show it, and that test stays.
- **A test guards what can do harm, not everything that can be wrong.** Tests go where a fault costs
  something: a secret or a credential that leaks, a check that can be passed around, data lost or
  written twice, work that leaves without review, a command that does the wrong thing, a contract
  another repository reads, a crash or a hang. There they are thorough: the failure paths too, red
  first.
- **What only reads wrong is fixed, not guarded:** a wording, a label, a colour, a layout, the order
  of an output, a translation, formatting the formatter already checks. Nobody writes a test for it.
  A test that holds a text verbatim is deleted when the text changes, not changed with it.
- **Before a test is written, it names the harm it prevents.** If it cannot, it is not written. A
  finding of a reading that names none is fixed without a test.
- **A defect that can do harm is fixed only after a unit or integration test reproduces it.** The
  test fails first, then the fix makes it pass; the rules on tests below hold within the three
  above.
- **A test is trusted only once it has been seen to fail**: break the code on purpose, watch the
  test go red, and restore it. A break that does not compile proves nothing.
- **A guard asserts what its reference set is, not only that it has entries**: an empty set, one
  that cannot change, and one nobody reads all pass as green as one that works.
- **A test asserts the observable effect, never what the system reports about itself or its
  internals.** A fixture states what the real system produces, never what the code assumes, and is
  never edited so that a feature has something to show.
- **A test depends on nothing outside itself**: not on the machine's configuration (a suite calling
  git runs with `GIT_CONFIG_GLOBAL=/dev/null`), not on wall-clock time, and not on a pinned version
  written into it - it reads the version from the build. A test that calls git runs from a clone of
  the tested commit, never an archive; transfer a git bundle when needed.
- **During the work, a commit runs the tests it changed and those of the modules built on what it
  changed; the full suite runs once before a hand-over, as its own step, and its result is read.**
  Both are gated on the exit code, never on grepped output, and never chained onto the test run.
- **A change handed over says which test levels actually ran** - unit tests, local VM, rented
  machine - never which ought to have. A change to documents or issues only needs the tests tagged
  `documents`, which run alone; every test that reads a document carries that tag, and a test fails
  when one does not.
- **A repository with a workflow runs `porelo-release`'s `check-shared`, `check-citations` and, where
  there is a documentation chapter, `check-doc-site`, with the tests tagged `documents`, on every
  push and pull request**, on a GitHub runner; the build skips a change to documents only.
- **A test result names every skipped test**, never just a count.
- **An obviously flaky test that blocks a delivery is disabled for the time being, and an issue for it
  is filed in Backlog with priority Highest.** That state is set for it without the operator, the one
  exception to the operator's states, and the agent fixes the test right after. When two or three tries at the fix
  have failed, it says so in the channel and stops; the coordinator tells the operator. A real fault is
  fixed, never disabled.
- **A failing check prints what it asked and what it got, never a guessed cause**, and its failure
  path has been made to happen once and read.
- **A deliberate break to see a test red is put back from a copy taken just before it**, never with
  `git checkout`, which drops uncommitted work in that file; `git diff --stat` afterward equals the one
  before.
- **While a build of one's own runs in a tree, nothing in that tree is edited, stashed or built a
  second time**; a fix that cannot wait stops the build first, and parallel work goes in another
  worktree.

## Test scripts

- **A test script is built with little effort: no test that goes red first**. The red-first
  proof is for production code. A rehearsal, a driver, a probe or any other script that only tests (e.g.
  `build/rehearse-move.sh`, `build/rehearse-busy-room.sh`, `tool/rehearse_window.sh`) gets no tests at all; its proof is that it runs against a local VM and then
  the rental. Its one reading by another model fixes reds only; yellows are noted, not looped on.

## What a Push ready names

- **Every Push ready names the test levels that ran, the build-box run with its log and result where one ran, every
  skipped test by name and each rerun after a failure**; new tests follow the pyramid and name the harm they guard.
  Old tests are not rewritten for the pyramid alone.

> **END Shared Skill porelo-testing** · sha256 `95cffea0cd584f87`
