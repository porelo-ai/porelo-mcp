---
name: porelo-operator-guide
description: Porelo rules for operator test guides and walks: one guide, observed results, numbered steps, commands for the operator, findings, fixes during a walk. Use when writing a guide or preparing an operator test.
---

> **BEGIN Shared Skill porelo-operator-guide** · sha256 `02204e0c187477a6` · changed 2026-11-20T07:25Z

# porelo-operator-guide

## Testing and machines

- **An operator test starts with one complete guide assembled from each repository’s checked
  steps.** Include prerequisites, versions, commands, clicks, keys, accounts, tunnels, expected
  results, failure signs, duration, and absolute paths.
- **Every command or step in an operator test guide has an observed result or is labeled “not run”
  in the guide itself.** Rehearse executable steps in the required installed environment or a clean
  checkout, checking options and creating the state the test needs.
- **An interface step of an operator test guide has run once against a real host before the guide is
  ready**; a run against fakes alone leaves it "not run". A step that needs a state no earlier step
  makes (a host prepared by the interface, a followed project) names the step that makes it.
- **An operator test guide gives each action as its own numbered step**, one action per step, its
  command in its own block and the expected result right under it; explanations stand before or
  after the steps, never between them.
- **A command for the operator is one short line; several commands in a row become a script run as
  `bash <script>`**, which stops at the first failure and names the guide's step that failed.
- **Every command for the operator says where to run it**: in the agent's session prefixed with `!`
  when it asks for nothing, or in a separate terminal when it asks for anything (a password, a
  passphrase, an unlock, a browser sign-in, any prompt).
- **Detailed instructions are delivered when the operator should start, with later actions
  introduced as their prerequisites are met.** Before then, report that the guide is ready.
- **During an operator test, collect findings and the responsible repositories’ answers for
  discussion together at the end.** Immediate replies stay brief and give a workaround when needed
  to continue.
- **Before a walk starts, have the responsible repositories prepare the operator’s test environment
  with the newest intended snapshots and verify installed versions and adapter commits.** Keep an
  ongoing walk stable.
- **A bug fix made during a running walk gets only its short unit tests, not the full suite, so the
  walk is held up as little as possible.** The walk’s live rerun is the fix’s test; the reading by
  another model follows after the walk.
- **An assigned isolated test environment’s test-only vault may be unlocked, reset, or recreated
  without another approval once the test is agreed.** Consume its passphrase in that environment
  through a passphrase command, without reading or printing it into the session.
- **The operator's test is the acceptance of a story, its goal, never a blockade** - nothing lists it
  as something work waits on; built issues are ready for it.

## Steps on another machine

- **A walk's steps on another machine are scripts the operator only calls, never command lists to type**: one script per step, named in the walk by its one call line, with
  or without sudo; each checks its preconditions first, says what it will do, refuses loudly on anything
  unexpected and can be run twice; a password is asked by the tool that needs it (passwd, sudo), never
  passed in or logged. They go into the hand-over tarball with its SHA256SUMS.

> **END Shared Skill porelo-operator-guide** · sha256 `02204e0c187477a6`
