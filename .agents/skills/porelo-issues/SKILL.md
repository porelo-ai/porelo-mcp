---
name: porelo-issues
description: Porelo rules for issues: the skeleton, states, sizes, the index, links, finished and rejected rows, requirement hand-overs. Use when filing, analyzing or changing the state of an issue.
---

> **BEGIN Shared Skill porelo-issues** · sha256 `cc8442cd5b6d2e2c` · changed 2026-11-20T16:42Z

# porelo-issues

## Issues

- **An issue is self-contained: it states the contract it builds against in words and names no issue,
  sprint, release or requirement of another repository.** Only `porelo-project`'s requirements and
  stories name them, with their priority (`stories/`), so a change of plan changes `porelo-project` alone.
- **An issue's data stands in its head, the JSON block right below its heading (`issue-1.json`); the index is never
  edited by hand.** `porelo-release issues index issues` writes it from the heads in the same commit, and
  `porelo-release issues check issues` passes before every hand-over.
- **A new issue follows this skeleton**; *Why* and *The shape* may follow *What must be true* in a larger issue:

      # <PREFIX><nn> — <Short Title>

      ```json
      { "$schema": "issue-1.json", "id": "<PREFIX><nn>", "title": "<Short Title>", "state": "Idea",
        "size": "-", "priority": "Medium", "rank": 1, "blockedBy": [], "covers": "<the index row's text>" }
      ```

      **Why this size.** One line (from Analyzed on).

      **What must be true.** One or two sentences, from the point of view of whoever uses it.

      ## Plan

      - The base design, a page at most, read in today's code: what it touches, the steps, the test
        that goes red first (from Analyzed on).

      ## Acceptance

      - How it is judged done: what is measured, and what must be seen to fail.

      ## Open questions

      - What the operator must decide; deleted once answered.

      ## To be checked

      - What the agent must still measure, read or confirm itself; deleted once done.
- **An issue moves through these states, and only the operator, as product owner, sets six of them**:
  - **Idea** - anyone brings it: a title and *What must be true*.
  - **Accepted** or **Rejected** - the operator only; a rejection says why.
  - **Analyzed** - the agent owning the repository: the *Plan* (a base design of a page at most, read
    in the code), the *Size*, the operator's decisions under *Open questions*, and what it must still find out
    itself under *To be checked*.
  - **Estimated** - the operator only, after reading the design and settling its questions with the
    agent, who adjusts the plan and the size.
  - **Backlog** - the operator only: it is built in the next round; the order in the index is the
    priority.
  - **Implementing** - the agent: **Rework** before Backlog; built locally, its whole workflow green on
    the VM, then pushed by the coordinator. It is set as the first act of the work, before any code, in
    an issues-only commit of its own with `[skip ci]` on `main`.
  - **Review** - the agent, when all is done, in the same commit as the last code; the operator tests
    what is pushed and published as a snapshot.
  - **Rework** or **Finished** - the operator only, after the test; a rework says what was found.

  A state counts only on `main`, where the plan page reads it; a state set only in a worktree or branch
  does not.

  An agent never sets Accepted, Rejected, Estimated, Backlog, Rework or Finished; it proposes in the channel. A
  size or plan that grows clearly while building is said before building on.
- **`issues/README.md` is the index, grouped by state** in the board order of the workflow file, each group ordered by
  priority and then rank, as the heads set them; a note on how far an issue is goes into its `covers`. An open task is
  never a TODO in code or a note in a commit message.
- **A rejected issue's file is deleted**; its number, title and one sentence why stay as a row under
  *Rejected* in the index, so the same idea is not brought twice.
- **From Analyzed on, an issue has a size and a plan, read in the code, not guessed.** The times are
  the wall time measured in `velocity.csv` across the repositories: XS takes minutes (one file, no contract); S under
  an hour (one module and its tests, no contract other repositories read); M one to two hours (several modules, or
  one contract change, docs, an acceptance step); L two hours or more (crosses repositories, or a design to decide
  first); XL is too big to build as one and says how it
  splits into issues of L or smaller. *Plan* names the files and classes it touches, the steps and the
  test that goes red first; a size or a plan that turns out wrong while building is corrected in the
  issue in the same commit.
- **An open question is one only the operator can answer** - a choice of product, scope, account,
  money or risk - and sits under *Open questions*, which the index counts in *Analyzed*: that column
  is what waits on the operator. From Estimated on an issue has none; one that comes up while building
  is said in the channel, and the issue waits until it is answered. What the agent can find out itself, by reading, measuring or asking another agent,
  sits under *To be checked* and is not counted. An answered question is deleted once its answer is
  in the criteria, the design or a decision.
- **A finished issue's file is deleted when the operator sets Finished, with every mention of its
  number, in one commit**; its number, title and one sentence of what was built stay as a row under
  *Finished* in the index, so what was built can be seen. What outlives it
  moves first: to `doc/` if a user needs it, to `doc/decisions.md` if it is a decision, to
  `AGENTS.md` or a skill if it is a rule, to `.AGENTS.md` or a local skill if it is true only on one machine.
- **A requirement not yet placed waits in `porelo-project`** until it is clear which repository builds
  it, then moves there as an issue.
- **Every row of an index opens with an anchor named after its number**, `<a id="<number>"></a>`,
  the number lower case; a *Finished* or *Rejected* row keeps it.
- **An issue number in `issues/` is a link to its row in the index, never to its file**, which is
  deleted when the issue is finished: `[<NUMBER>](README.md#<number>)` in the same repository, and in
  `porelo-project` the other repository's index on GitHub for another's. The row outlives the file, so
  the link does not break. The shared tools check that every number is such a link, that its anchor
  exists, and that no repository but `porelo-project` cites another's issue.
- **A requirement handover includes the text the receiving repository needs; where it came from goes
  into the handover, never into the issue.** Identify a proposal as a proposal until the operator
  accepts it. Publish a new index row before `porelo-project` links to it.

## Where an issue is filed

- **Every repository keeps its own issues**: an issue about a repository is filed in that
  repository's `issues/`, never in another's. A repository without `issues/` gets one (README.md and the shared
  workflow.yml copy) by its owner with its first issue.

## Every task is an issue

- **Every open task is an issue in `issues/`, named `<prefix><nn>-Short-Title.md`** with one of its
  repository's prefixes; a repository may own several, for subgroups. An issue is one task and says
  what must be true and how it is judged done.

> **END Shared Skill porelo-issues** · sha256 `cc8442cd5b6d2e2c`
