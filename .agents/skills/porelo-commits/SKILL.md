---
name: porelo-commits
description: Porelo rules for commits, squashing, commit messages, changelog entries, rebasing, skip instructions and push hand-overs. Use before any git commit, amend, rebase or push hand-over in a Porelo repository.
---

> **BEGIN Shared Skill porelo-commits** · sha256 `d0b79707f950b097` · changed 2026-11-21T06:05Z

# porelo-commits

## Commits and pushes

- **While working, commit in logical steps, so a mistake can be rolled back.** A task is pushed only
  when it is finished, squashed into one commit as its last step; several finished tasks may go out
  in one push, one commit each.
- **An edit replaces an exact block, never everything between two landmarks; only the files you
  edited are formatted.** Before a commit, `git status` is read and only your own files are added,
  never with `git add -A` or `git add -u`; files are added by name, and `git show --stat` is read after
  the commit, since one worktree may hold two issues' work.
- **A change that needs another repository's change names that dependency when it is handed over**,
  and is pushed only after the change it needs has built and published.
- **A change is ready only when every step its build workflow runs - not only Maven - has passed
  locally.**
- **Amend, squash or rebase only when every affected commit is unpublished**, checked with
  `git ls-remote origin refs/heads/main`, never with a cached `origin/main`; several local commits
  are allowed. A pushed commit is repaired by a new commit on the remote's tip, never by a force push;
  a task's own unmerged branch is the one exception, taking its amended commit with `--force`. A
  commit handed over for pushing counts as published: a correction goes into a new commit on top.
- **Issue and document changes are gathered for a push at the end of a work block.**
- **A push handover gives `git push origin <commit>:main` when local commits follow the tested
  commit**; a branch push only when its tip is the tested commit.
- **A commit message is one line of at most 72 characters saying in words what changed**, never by a
  requirement number; the reasoning goes into an issue or a decision. A squash of several changes names
  what they share, not each of them. Trailers may follow below a blank line, and nothing else.
- **Where the project's tracker is GitHub Issues, a commit made for an issue carries one `Porelo-Issue:
  <owner>/<repository>#<number>` trailer per issue it serves**, beside the other trailers, so the issue's
  timeline shows its commits; where issues are kept in `issues/`, a commit names none. The subject still names
  no number, and no closing keyword (`Fixes`, `Closes`, `Resolves`) stands anywhere in the message: Finished is
  the person's to set, never a push's.
- **A change to what ships or to what a build produces gets its changelog entry in the same commit**,
  under `[Unreleased]` and the heading of its kind. A change only to the CI's own workflows (an action's
  pin, a schedule, a job's steps) gets none: the changelog is for the people who use what ships. A
  generated changelog is changed only through its sources, never by hand.
- **A skip instruction such as `[skip ci]` never heads a push that must build**: GitHub reads only the head
  commit and skips the whole push, so issue and status commits go below the last code commit; a push of
  issues or documents alone may end with one.
- **After an authorized push, follow the repository’s build through every job to its final result
  and verify its `head_sha`.** Report the run ID and commit, with success or the failed step and
  relevant error. A failed run on main is read from the failed job's log the moment it ends, before any
  other work.
- **A branch is brought up to `main` by rebasing it, never by merging `main` in**, and takes another's
  work by cherry-pick or new commits on the current tip; conflicts are resolved commit by commit, and the
  whole suite runs on the rebased head before the hand-over.

## Code and builds

- **A push that changes only `.github/` starts no Build**; the next push with content builds with it.

## Trailers and signing

- **An agent's commit names its help in an `Assisted-by: <tool>, <model>` trailer, and an agent never
  writes a `Signed-off-by:` or `Co-Authored-By:`**, even when its harness suggests one: the operator vouches for the project's own commits by pushing them. A
  pull request from outside carries its author's `Signed-off-by:`, the Developer Certificate of Origin.
- **A commit Porelo makes itself carries Porelo's own trailers, `Porelo-Change-Id` and
  `Porelo-Signs-Anew`, and for a task given work its issue's `Porelo-Issue`, and nothing else.**
- **No commit message, changelog entry, issue or document says who asked for a change or who made it**
  - no "as the operator chose", no agent's name; it says what changed and why.
- **Signing and release-tag creation belong to the operator.** Hand over the release commit, next
  snapshot, and exact signed-tag command; never request the signing passphrase.

- **One `Assisted-by:` line per tool that helped, a reading by another tool included** (`Assisted-by: Antigravity
  CLI, <model>` when agy read it), all in one block below one blank line; the range is checked with
  `porelo-release check-commits` right after committing, and over the whole unpushed range before every Push ready;
  a `[skip ci]` in a subject counts toward its 72 characters.

> **END Shared Skill porelo-commits** · sha256 `d0b79707f950b097`
