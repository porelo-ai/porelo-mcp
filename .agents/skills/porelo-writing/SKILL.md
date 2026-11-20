---
name: porelo-writing
description: Porelo rules for decisions and published text: decisions.md, findings from third-party sources, the scan for machine names before a hand-over. Use when writing documentation or a decision and before a hand-over.
---

> **BEGIN Shared Skill porelo-writing** · sha256 `80c0c526727cc36d` · changed 2026-11-20T07:25Z

# porelo-writing

## Issues

- **`doc/decisions.md` opens with an index** - subject, one line of what holds, link - and a row is
  written with its decision. An accepted risk states the exposure, why it stays, and what would
  change the answer.

## Claims and writing

- **A finding taken from a third-party source is written as the finding, never naming the source**,
  in anything committed. Source and path references stay in the local review discussion; required
  license or copyright attribution is not removed under this writing rule, and a conflict is decided
  separately.
- **Before every handover, scan tracked files for real machine names, addresses, accounts and home
  paths, and take them out.** Examples use documentation addresses (`192.0.2.x`, `example.org`) and
  fictional accounts.

## Claims, dates and where rules live

- **Measure before you claim, and say what was measured and what inferred.** Agreement between agents
  counts only where each measured, and "I could not get X" only once a second method failed too.
- **A command that should have changed something is checked by observing the change** - connect,
  read the file, ask the daemon - never by its exit code alone.
- **A claim of absence requires a complete search with verified scope.** Read every match, and
  confirm that an empty search finds something known in the same files before treating it as
  evidence.
- **Every date, time or age written is read from the system (`date -u`) first, never from memory.**
  An age is computed from two timestamps that were both read.
- **Documentation and rules state what is true now**: no dates, no history, no stories, and nobody
  named, only roles. The shared blocks' markers are the one exception.
- **A rule that holds for more than one repository is stated generally and shared**: in the shared block or a
  shared skill `porelo-*`, or, if it concerns the local setup, in the local shared block or a local skill `local-*`.
  A rule is one or two sentences and says only what matters.
- **A core block holds only what binds every step; a rule that matters for one kind of work goes into a skill**,
  and the core's table names the skill and when to load it. A skill stays under 500 lines, its `name` is its
  directory's name, and its `description` says what it holds and when to use it.
- **A rule belongs in `.AGENTS.md` or a local skill only if it concerns local settings that cannot be shared
  through `AGENTS.md` and the repository's skills.**

> **END Shared Skill porelo-writing** · sha256 `80c0c526727cc36d`
