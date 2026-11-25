---
name: porelo-issues
description: Porelo rules for issues: where they are kept, the skeleton, states, sizes, open questions, finished and rejected issues, requirement hand-overs. Use when filing, analyzing or changing the state of an issue.
---

> **BEGIN Shared Skill porelo-issues** · sha256 `495dffb7734e7385` · changed 2026-11-25T07:23Z

# porelo-issues

## Issues

- **Every open task is an issue in GitHub Issues of the repository that builds it**, on the project's board that
  the planning repository's `planner.yml` names as its tracker; never a TODO in code or a note in a commit message.
  An issue is named by its reference, `<repository>#<number>`; an old id from the Markdown issues is found in the
  issue's own text and never given again.
- **Issues and stories are written through the planner, never by hand on GitHub's fields**: state, priority, size
  and worker are the board's fields, what blocks an issue its issue dependencies, a story's issues its sub-issues.
- **An issue is self-contained: it states the contract it builds against in words and names no issue, sprint,
  release or requirement of another repository.** Only `porelo-project`'s stories name them, with their priority, so
  a change of plan changes `porelo-project` alone.
- **A new issue's body follows this skeleton**; *Why* and *The shape* may follow *What must be true* in a larger
  issue:

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
  - **Accepted** or **Rejected** - the operator only; a rejection says why, in the issue.
  - **Analyzed** - the agent owning the repository: the *Plan* (a base design of a page at most, read
    in the code), the *Size*, the operator's decisions under *Open questions*, and what it must still find out
    itself under *To be checked*.
  - **Estimated** - the operator only, after reading the design and settling its questions with the
    agent, who adjusts the plan and the size.
  - **Backlog** - the operator only: it is built in the next round; the board's order is the priority.
  - **Implementing** - entered by the work itself when it starts: **Rework** before Backlog; built locally, its
    whole workflow green on the VM, then pushed by the coordinator.
  - **Review** - the agent, when all is done and pushed; the operator tests what is pushed and published as a
    snapshot.
  - **Rework** or **Finished** - the operator only, after the test; a rework says what was found.

  An agent never sets Accepted, Rejected, Estimated, Backlog, Rework or Finished; it proposes in the channel. A
  size or plan that grows clearly while building is said before building on.
- **From Analyzed on, an issue has a size and a plan, read in the code, not guessed.** The times are
  the wall time measured in `velocity.csv` across the repositories: XS takes minutes (one file, no contract); S under
  an hour (one module and its tests, no contract other repositories read); M one to two hours (several modules, or
  one contract change, docs, an acceptance step); L two hours or more (crosses repositories, or a design to decide
  first); XL is too big to build as one and says how it splits into issues of L or smaller. *Plan* names the files
  and classes it touches, the steps and the test that goes red first; a size or a plan that turns out wrong while
  building is corrected in the issue.
- **An open question is one only the operator can answer** - a choice of product, scope, account,
  money or risk - and sits under *Open questions*: that is what waits on the operator. From Estimated on an issue
  has none; one that comes up while building is said in the channel, and the issue waits until it is answered. What
  the agent can find out itself, by reading, measuring or asking another agent, sits under *To be checked*. An
  answered question is deleted once its answer is in the criteria, the design or a decision.
- **A finished or rejected issue stays in the tracker, closed**, so what was built or refused can be seen and the
  same idea is not brought twice. What outlives it moves first: to `doc/` if a user needs it, to
  `doc/decisions.md` if it is a decision, to `AGENTS.md` or a skill if it is a rule, to `.AGENTS.md` or a local
  skill if it is true only on one machine.
- **A requirement not yet placed waits in `porelo-project`** as its issue until it is clear which repository
  builds it, then moves there as an issue of that repository.
- **A requirement handover includes the text the receiving repository needs; where it came from goes
  into the handover, never into the issue.** Identify a proposal as a proposal until the operator accepts it.

> **END Shared Skill porelo-issues** · sha256 `495dffb7734e7385`
