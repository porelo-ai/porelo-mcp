# Rules for working in this repository

## Shared across the Porelo repositories

> **BEGIN Shared Area** · sha256 `a10e8e42ec48a8d7` · changed 2026-11-09T07:16Z

Identical in every repository `project.yml` names but `porelo-parent`, which holds only the parent pom
and no `AGENTS.md`. The markers carry the SHA-256 of the lines between them (the first 16 hex digits)
and the UTC time that text last changed; change it in the channel first, never in one copy.

### Agents and the rules they follow

- **When several agents run in parallel, they may communicate through a shared channel.** How it
  works depends on the local setup and is defined in the optional `.AGENTS.md` or fed into every
  agent's context when it starts.
- **Every repository has exactly one responsible agent, and one agent may be responsible for
  several.** Which one is defined in the optional `.AGENTS.md` or fed into every agent's context when
  it starts.
- **A rule that holds for more than one repository is stated generally and shared** - here, or in the
  shared block of `.AGENTS.md` if it concerns the local setup. A rule is one or two sentences and
  says only what matters.
- **A rule belongs in `.AGENTS.md` only if it concerns local settings that cannot be shared through
  `AGENTS.md`.**
- **An agent writes only in its own repositories and asks the responsible agent for anything from
  another**, in the channel. The coordinating agent may also read the other repositories, but never
  writes in them.
- **Where a channel exists, an agent stays reachable on it while any work is open**, and says there at
  once when it waits on the operator.
- **A contract between repositories - an interface, a file format, a path another repository links
  to - changes only after agreement in the channel or by an operator decision**, and in both
  repositories.
- **An ambiguous answer is asked about once, plainly, instead of guessed at.** When an answer
  changes, everything built on the old one is looked for.
- **Only `AGENTS.md` and `.AGENTS.md` survive a session; the harness's own memory is never used.** A
  rule, an operator decision about how to work or an open point worth keeping goes into one of them
  at once.
- **Only the coordinating agent writes to the operator; while the operator sits at the console, only
  there.** Other agents say what the operator should know in the channel, to the coordinator.
- **To the operator, plain technical terms, never coined words** - "merge", not a word of one's own.
- **A post states only what a result already on screen shows**: an edit, the room read and the post
  that reports them are separate commands, and a shared hand-over file is read again right before it
  is rewritten.

### Commits and pushes

- **While working, commit in logical steps, so a mistake can be rolled back.** A task is pushed only
  when it is finished, squashed into one commit as its last step; several finished tasks may go out
  in one push, one commit each.
- **An edit replaces an exact block, never everything between two landmarks; only the files you
  edited are formatted.** Before a commit, `git status` is read and only your own files are added,
  never with `git add -A` or `git add -u`; files are added by name, and `git show --stat` is read after
  the commit, since one worktree may hold two issues' work.
- **Unless the operator says otherwise, the operator pushes, and agents commit and stop.** An
  instruction to push covers exactly what it names.
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
- **An agent's commit names its help in an `Assisted-by: <tool>, <model>` trailer, and an agent never
  writes a `Signed-off-by:` or `Co-Authored-By:`**, even when its harness suggests one: the operator vouches for the project's own commits by pushing them. A
  pull request from outside carries its author's `Signed-off-by:`, the Developer Certificate of Origin.
- **A commit Porelo makes itself carries Porelo's own trailers, `Porelo-Change-Id` and
  `Porelo-Signs-Anew`, and nothing else.**
- **No commit message, changelog entry, issue or document says who asked for a change or who made it**
  - no "as the operator chose", no agent's name; it says what changed and why.
- **A change to what ships or to what a build produces gets its changelog entry in the same commit**,
  under `[Unreleased]` and the heading of its kind. A change only to the CI's own workflows (an action's
  pin, a schedule, a job's steps) gets none: the changelog is for the people who use what ships. A
  generated changelog is changed only through its sources, never by hand.
- **Signing and release-tag creation belong to the operator.** Hand over the release commit, next
  snapshot, and exact signed-tag command; never request the signing passphrase.
- **A skip instruction such as `[skip ci]` never heads a push that must build**: GitHub reads only the head
  commit and skips the whole push, so issue and status commits go below the last code commit; a push of
  issues or documents alone may end with one.
- **After an authorized push, follow the repository’s build through every job to its final result
  and verify its `head_sha`.** Report the run ID and commit, with success or the failed step and
  relevant error.
- **A branch is brought up to `main` by rebasing it, never by merging `main` in**, and takes another's
  work by cherry-pick or new commits on the current tip; conflicts are resolved commit by commit, and the
  whole suite runs on the rebased head before the hand-over.

### Issues

- **Every open task is an issue in `issues/`, named `<prefix><nn>-Short-Title.md`** with one of its
  repository's prefixes; a repository may own several, for subgroups. An issue is one task and says
  what must be true and how it is judged done.
- **An issue is self-contained: it states the contract it builds against in words and names no issue,
  sprint, release or requirement of another repository.** Only `porelo-project`'s requirements and
  plans name them, with their order and their waits (`plan/after.yml`), so a change of plan changes
  `porelo-project` alone.
- **A new issue follows this skeleton**, and its index row is added in the same commit; *Why* and
  *The shape* may follow *What must be true* in a larger issue:

      # <PREFIX><nn> — <Short Title>

      **Status:** Idea | Accepted | Analyzed | Estimated | Backlog | Implementing | Review | Rework;
      blocked by <number> of this repository, if so.
      **Size:** XS | S | M | L | XL - one line why (from Analyzed on).

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
- **`issues/README.md` is the index, grouped by state** in this order: Review, Rework, Implementing,
  Backlog, Analyzed, Accepted, Idea, Estimated, then Finished and Rejected last; each group a table of
  number, size, blocked by (in this repository) and what it covers, and in *Analyzed* also the number
  of open questions (the group's heading says the state; a note on how far it is goes into what it covers), ordered by
  what to do next; it changes in the same commit as the issue. An open task is never a TODO in code or a note in a commit message.
- **A rejected issue's file is deleted**; its number, title and one sentence why stay as a row under
  *Rejected* in the index, so the same idea is not brought twice.
- **From Analyzed on, an issue has a size and a plan, read in the code, not guessed.** XS is under an hour (one
  file, no contract); S a few hours (one module and its tests, no contract other repositories read);
  M about a day (several modules, or one contract change, docs, an acceptance step); L several days
  (crosses repositories, or a design to decide first); XL is too big to build as one and says how it
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
  `AGENTS.md` if it is a rule, to `.AGENTS.md` if it is true only on one machine.
- **`doc/decisions.md` opens with an index** - subject, one line of what holds, link - and a row is
  written with its decision. An accepted risk states the exposure, why it stays, and what would
  change the answer.
- **A requirement not yet placed waits in `porelo-project`** until it is clear which repository builds
  it, then moves there as an issue.
- **Every row of an index opens with an anchor named after its number**, `<a id="<number>"></a>`,
  the number lower case; a *Finished* or *Rejected* row keeps it.
- **An issue number in `issues/` is a link to its row in the index, never to its file**, which is
  deleted when the issue is finished: `[<NUMBER>](README.md#<number>)` in the same repository, and in
  `porelo-project` the other repository's index on GitHub for another's. The row outlives the file, so
  the link does not break. The shared tools check that every number is such a link, that its anchor
  exists, and that no repository but `porelo-project` cites another's issue.
- **Code, comments, test names and anything that ships never cite an issue number**; they state the
  constraint itself, since the issue is deleted once it is finished.
- **A sprint-scoped instruction applies only to issues assigned to that sprint in the release
  plan.** Check each issue’s assignment before changing its state or starting work under that
  instruction.
- **A requirement handover includes the text the receiving repository needs; where it came from goes
  into the handover, never into the issue.** Identify a proposal as a proposal until the operator
  accepts it. Publish a new index row before `porelo-project` links to it.

### Testing and machines

- **Work is tested on local VMs before it is handed over, on rented machines reachable from here only
  when the operator says so, and in the GitHub build on every push.** Which machines a setup has is
  defined in the optional `.AGENTS.md` or fed into every agent's context; machines are rented only
  through the shared tooling, and nothing else names a provider.
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
  the fix, and keeps checks passed earlier apart from those rerun on it.
- **A small change is tried on a local VM only as far as the change needs, not by a full run of
  the whole flow.** The complete run is the workflow's on GitHub afterwards.
- **A workflow step replayed locally runs on a clean build (`clean`, or a fresh clone), as the
  runner's checkout does.** What an earlier build left in `target/` is read too and fails, or passes,
  what the runner would not.
- **How agents exchange files for a test on a local VM is defined in `.AGENTS.md`.** Where it says
  nothing, a test uses only the agent's own artifacts.
- **A local VM is restarted only when agreed in the channel or asked of the operator.** A rented
  machine reachable from here is restarted only by the operator.
- **A rented machine's name says which agent and which run made it.** Every run deletes exactly what
  it created, after a failure too, and never sweeps by age or touches a machine it cannot attribute.
- **The scheduled sweep of the CI's machines deletes one only past the `porelo-expires` time it was
  made with, or one that carries none**; it is the one clean-up that is not a run's own.
- **A script that changes a machine refuses to start over leftovers, removes only what it created -
  on interrupt too - and exits non-zero naming anything it left.**
- **Before a run on a shared machine, check what it does to that machine and say so.**
- **Before a long run, say how long it will take**, from a measured time, or say that it is a guess.
- **A test that waits on an agent, a model or any paid service fails fast on a loop**: it stops as
  soon as the same failure repeats, instead of waiting out its time, and names what repeated. A run
  seen looping is reported at once, so the operator can cancel it.
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
  is filed in Backlog as the first row of its index (Highest).** The agent sets that state itself, the one
  exception to the operator's states, and fixes the test right after. When two or three tries at the fix
  have failed, it says so in the channel and stops; the coordinator tells the operator. A real fault is
  fixed, never disabled.
- **A failing check prints what it asked and what it got, never a guessed cause**, and its failure
  path has been made to happen once and read.
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
- **A machine script is rehearsed with the same preparation as its target before an expensive run.**
  Compare its options with the build workflow and verify each prerequisite’s effect before starting
  dependent work.
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
- **Budget each lease using its provider’s billing units at the current price.** Include every new
  lease required for a rerun.
- **An assigned isolated test environment’s test-only vault may be unlocked, reset, or recreated
  without another approval once the test is agreed.** Consume its passphrase in that environment
  through a passphrase command, without reading or printing it into the session.
- **The operator's test is the acceptance of a story, its goal, never a blockade** - nothing lists it
  as something work waits on; built issues are ready for it.
- **Porelo's own host is rented for a few hours at a time**, so local work never needs a running host;
  a product decision still allows hosts that stay.
- **A deliberate break to see a test red is put back from a copy taken just before it**, never with
  `git checkout`, which drops uncommitted work in that file; `git diff --stat` afterward equals the one
  before.
- **While a build of one's own runs in a tree, nothing in that tree is edited, stashed or built a
  second time**; a fix that cannot wait stops the build first, and parallel work goes in another
  worktree.
- **A package removal on a shared machine is simulated first** (`dnf remove --assumeno`, `apt-get -s
  remove`), since dependents go with it; if it takes anything this run did not install, stop and ask in
  the channel.

### Claims and writing

- **Measure before you claim, and say what was measured and what inferred.** Agreement between agents
  counts only where each measured, and "I could not get X" only once a second method failed too.
- **A command that should have changed something is checked by observing the change** - connect,
  read the file, ask the daemon - never by its exit code alone.
- **Every date, time or age written is read from the system (`date -u`) first, never from memory.**
  An age is computed from two timestamps that were both read.
- **When a thing is right in two forms, keep one.** The second copy is the one that goes stale.
- **Documentation and rules state what is true now**: no dates, no history, no stories, and nobody
  named, only roles. The shared blocks' markers are the one exception.
- **Everything written is US English** - documentation, issues, comments, commit messages, the
  channel.
- **A finding worth keeping is committed** - in an issue, the documentation or a decision - never
  left only in a conversation or the channel.
- **A finding taken from a third-party source is written as the finding, never naming the source**,
  in anything committed. Source and path references stay in the local review discussion; required
  license or copyright attribution is not removed under this writing rule, and a conflict is decided
  separately.
- **Agents answer the operator in the language the operator writes in.** Porelo's own names stay as
  they are in every language: Porelo-Vault, its passphrase the Vault-Passphrase, gate, task.
- **A claim of absence requires a complete search with verified scope.** Read every match, and
  confirm that an empty search finds something known in the same files before treating it as
  evidence.
- **Before every handover, scan tracked files for real machine names, addresses, accounts and home
  paths, and take them out.** Examples use documentation addresses (`192.0.2.x`, `example.org`) and
  fictional accounts.
- **Replies to the operator after reading messages mention only what requires this role to act or
  answer.** State the action or need without retelling other entries; unrelated reads produce no
  summary, waiting update, or watcher acknowledgment.
- **A sub-agent's statement about code is checked before it is relayed**: the named file, method or
  line is read before it goes into the channel, an issue or a question for the operator, since an
  invented fact reads as confident as a true one.

### Security and tools

- **A secret never appears in a command line, a log line, a file name or an answer.** It reaches a
  process through its environment or standard input, is stored only encrypted and readable by its
  owner, never touches a filesystem in CI, and is never promised to be wiped from memory in Java or
  Dart - only kept in fewer copies for less time.
- **Every download follows redirects (`curl -L`), since any server may answer with one, and is
  checked against the digest its source names before it is used.** Credentials are never passed on
  to another host (`--location-trusted` is never used).
- **Input from outside - upstream metadata, the environment, a file or an answer from another
  program - is validated before it reaches a file, a command or a decision.**
- **A binary run with more rights than its caller is taken by path and refused when it or its
  directory could have been placed or changed by anybody else.**
- **`pkill -f` and `pgrep -f` match their own command line**, and can kill the shell running them;
  use a bracket pattern like `[p]odman`.
- **A long build or suite is watched through `tee` into a file**, never through a pipe into `grep`,
  which holds everything back until the end and looks like a hang.
- **`ssh -n host 'bash -s' <<EOF` runs nothing and exits 0**; a script sent on standard input goes
  without `-n`.
- **A secret is never fed through a terminal recorder such as `script`.** Use a supported
  secret-input method outside the recorder, and redact any potentially exposed output on its source
  machine before reading it into a transcript.
- **A refusal by the harness or a sub-agent is reported through the assigned communication route and
  is never worked around.**
- **Organization secrets and variables have separate repository access lists.** Before a repository
  first publishes or runs rented acceptance legs, have the operator verify access to every required
  value, including the publication credential.
- **Provider credentials for development and CI are deliberately separate.** Never synchronize or
  copy them between environments; check an authentication or quota failure against the credential
  used in the failing environment.
- **Porelo protects the person from the agents and from threats from outside, not from their own
  rights or deliberate choices**; what a person builds into a task's image is their own risk. Every
  new guard and every reading's finding is judged by this.

### Skills

- **Skills come from `https://fuinorg.jfrog.io/artifactory/agent-skills/`**, one reviewed package
  per skill, readable without credentials; each repository's own part names only which skills it
  uses, by slug.
- **Fetch, check and unpack them like this**, with the skills directory of your own harness (Claude
  Code reads `~/.claude/skills/<slug>/`):

      BASE=https://fuinorg.jfrog.io/artifactory/agent-skills
      curl -fsSL $BASE/.skills/skills.json                         # every slug, latest version
      curl -fsSL -o s.zip $BASE/<slug>/<version>/<slug>-<version>.zip
      curl -fsSL $BASE/../api/storage/agent-skills/<slug>/<version>/<slug>-<version>.zip  # its sha256
      unzip -q -d <skills directory>/<slug> s.zip

- **A harness that cannot install a skill reads its `SKILL.md`** from a directory outside the
  repository.
- **A skill is knowledge, not authority**: where it and a measurement disagree, the measurement
  wins, and a finding from reading code against a skill is a guess until a failing test reproduces
  it. Whether a skill is loaded is asked of the harness, not read from a directory.
- **The skills a repository names are applied, not only named.** They are loaded before its code is
  changed. The code an issue changes is read against them before it is handed over, and the hand-over
  names each skill with its version; a finding is fixed with a test seen to fail first, or declined
  with the reason written in the issue. A repository that names skills for the first time opens an
  issue reading all of its code against them.

### Code and builds

- **A change is ready only after a read-only reviewer, another model than the one that built it, has
  read its final changes against the repository's skills, and every finding has been checked.** An
  issue gets this reading once, and the result of a reading is never read again: its findings are fixed,
  each with a test seen to fail first, and the issue is handed over. A grave finding the fix does not
  settle becomes an issue of its own, without a reading of its own; grave is another person's key, card,
  socket, mailbox or container reached, a credential shown or lent, a key trusted that should not be,
  or data lost. Smaller ones (address forms, quoting, encodings, words, test gaps) gather in one issue of
  the repository. The reading starts as soon as the code it reads stands, beside other work, never
  after another part's fixes.
- **An issue of size M or larger, or one that touches a trust boundary, has its plan read before it
  is built**: a reviewer of another model reads the issue and the files it names, asking only what a
  caller could do that the plan does not forbid, and the build takes the answers. This reading of the
  plan is not the reading of the code, which still follows once.
- **What a repository's readings keep finding goes into its build briefs as a checklist**, so the
  builder checks it before handing over.
- **Fail closed and loud**: when a dependency is unreachable, a key is unknown or a check cannot run,
  stop with a non-zero exit code and say why; inside a program, an exception that says why does the
  same. Never silently do less.
- **A comment says why, never what, in one line where it can.** Reasoning that does not fit goes into
  documentation or a decision, and a small named method is preferred over a comment explaining a
  block.
- **Dot files are not committed.** `.gitignore` ignores `.*` and excepts only what a build or the
  shared code style needs - `.github`, `.gitignore`, `.gitkeep`, `.editorconfig` and
  `.git-blame-ignore-revs`, in a Maven repository also `.mvn` - and what is true of one machine goes
  into `.AGENTS.md`.
- **Maven is the build system: a Java repository builds, checks and tests with Java and Maven only.**
  Another build tool only where the platform a repository builds for supports no other - an IntelliJ
  plugin, since JetBrains supports only Gradle - named with the reason in its repository's own part. A
  file that cannot be Java - `mvnw`, or a script that runs where there is no Java yet - is named in its
  repository's own part with the reason, and no repository keeps a copy of a helper another one has.
  The one exception is `.github/actions/pinned-jdk/pinned.sh`, the rule for pinned downloads, which a
  repository's `pinned-jdk` action copies unchanged from porelo-buildtools, since that step runs before
  there is Java; `check-shared` refuses a copy that differs.
- **Maven modules that belong together - the layers of one program, its packages - are submodules of
  one module of packaging `pom` named for what they share**, so the root lists parts, not pieces.
  Grouping changes only `parent` and `relativePath`, never a `groupId` or `artifactId`.
- **Every directory with a `pom.xml` has a `README.md`**: two or three sentences on what the module is
  and is not, a link to each of its submodules, and only the few technical facts that matter most, such
  as what it must never depend on; details are linked in `doc/`, never repeated. `porelo-release
  check-readmes` holds it.
- **Only what another repository or an outsider builds against is published to Maven Central, each such
  module opting in; the root and every grouping module are never published.** Every published pom is
  flattened, so it names no parent and a consumer needs nothing but the module itself; `porelo-release
  check-releases` refuses a release whose published pom still names one. A BOM is imported, never a
  parent.
- **Every Porelo Maven repository takes `org.fuin.porelo:porelo-parent` as its parent** and declares only what
  is its own; one that publishes declares `flatten-maven-plugin` by name in its root, and the parent's
  management flattens every module. `porelo-parent` names no parent and uses nothing of another Porelo
  repository.
- **Every Java package with main code is `@NullMarked` and checked by NullAway as an error when it
  compiles**, with a test that fails on an unmarked package.
- **`Files.move` with `ATOMIC_MOVE` replaces a file that already has the target name**; where the
  first of two writers must win, publish with `Files.createLink` (`link(2)`), which fails on an
  existing name.
- **Java code carries brief Javadoc on every public type and method; a test method's name reads as a
  sentence (never `testXxx`) and an assertion states its reason (`.as(...)`).** Every build call in
  CI names its repositories in the repository itself (Maven: `-s settings.xml`).
- **Everything a build runs is pinned**: actions by commit with the version beside it, the JDK (from
  `porelo-machines jdk --github`), the build tool's wrapper (Maven, Gradle) and images by version and
  digest. `porelo-release check-actions` enforces it. Dependabot moves only the actions, weekly, in one
  group, after three days; the operator merges each, as code owner of `.github/`, once the pull
  request's light check (no rented machine) passes. Everything else is moved by the repository's agent.
- **A push that changes only `.github/` starts no Build**; the next push with content builds with it.
- **While it is developed, a repository may build against another Porelo repository's snapshot from
  Sonatype**, and its build may fail for a while when that snapshot moves; a release depends only on
  releases, which the release build checks. A release publishes dependencies before their consumers
  and pins released dependencies before releasing a consumer. Hand over each repository's release
  commit, operator-created tag, and next-snapshot commit for one push.
- **Packages are built online**: offline, the CycloneDX bill of materials skips itself with only a
  warning, and the package ships without it.
- **A publish is believed only once the published index shows the exact version**, probed with
  retries; a snapshot version sorts above the one before it. Retiring a package removes it from the
  index too.
- **Every native executable is built with `-march=x86-64`**, so it starts on any x86-64 CPU, and the
  build checks each executable for exactly that instruction set.
- **A shell function never changes `errexit` when its caller must handle a nonzero return.** Capture
  expected failures explicitly, preserve command status through pipelines, and report unexpected
  failures with their line and exit code.
- **Maven Central deployments are published manually by the operator in the Sonatype portal, with
  automatic publication disabled.** A green release run is reported as waiting for that publication.
- **Small changes stay on snapshots, and a release gathers changes worth publishing together.**
- **A new Java library uses `LGPL-3.0-or-later`; an API that others build their own extensions
  against (such as an adapter's or a plugin's interface library) uses `Apache-2.0`.** Keep existing
  product licenses unless a license change is explicitly agreed.
- **An ecosystem adapter or build-tool plugin only helps a project start**: it is judged by whether its
  answer matches the real build, secures nothing, and once `project.yml` exists Porelo never watches,
  compares or updates what stands in it.
- **A measurement of a library or an approach is a small throwaway project of about five minutes**,
  holding only the planned classes, never a slice of the real repository.

### Estimates and handovers

- **Before analysis or implementation, report `estimate=<min> velocity median=<min>`, and the
  estimate is the measured median of the issue's size.** A reason to expect more or less is named
  beside it and never changes the number; label a guess when no measurement exists, and report the
  cause at once when elapsed time reaches one and a half times the estimate.
- **Timing starts with the first plan reading or brief and ends when the work is ready for handover,
  including waits.** Keep the original estimate and starting size, record measured VM and sub-agent
  times, count returns after first readiness as rework, and explain long waits or size growth. Count
  shared time and token usage only once.
- **Every push handover includes one timing record**:
  `VELOCITY <repo> <issue> size=<XS|S|M|L> start=<UTC> ready=<UTC> est=<min> wall=<min> vm=<min> subagents=<model-effort:sec,...> rework=<n>`.
  Issues built together in one repository share one record, joining identifiers and starting sizes
  with `+`: estimates summed, the earliest start and the stack's readiness, VM time and tokens counted
  once.

> **END Shared Area** · sha256 `a10e8e42ec48a8d7`

## What this repository is

Porelo's daemon contract offered as MCP tools, by one program, `porelo-mcp`, shipped as a native executable in a DEB and
an RPM package of its own.

- **Every method of the daemon's `org.fuin.porelo.Tasks1` interface is a tool**, generated from the interface the daemon
  describes at run time, never written by hand: a method added to the contract arrives as a tool without a change here.
- **It holds no rule of its own.** Every answer and every refusal is the daemon's; a refusal reaches the agent as an MCP
  error with the refusal's name and parameters as they came. What a task may call is decided by `porelod`, never here.
  Over stdio it offers only the sets the daemon names, and refuses the daemon's never-set once more itself, so a fault
  on one side alone opens nothing.
- **It never parses the `porelo` command line's output and never reads Porelo's files**; it speaks only Varlink to the
  daemon.
- **No secret value ever leaves through it.** The contract gives none, and a tool that would carry one is a fault of the
  contract, raised with porelo.
- **It never listens on TCP.** For a task it serves MCP over HTTP on a Unix socket of mode `0600`, with a bearer token
  and the peer's credentials checked; for a person's own client it speaks stdio. How `porelod` starts and binds it is
  [doc/binding.md](doc/binding.md), the contract with porelo.
- **Under `GPL-3.0-or-later`, and nothing goes to Maven Central.** Nothing builds against an artifact here.

## Work in this repository

- **Issue prefix: `MC`.**
- **The tests run against a stand-in daemon** that serves a Varlink interface over a Unix socket, so nothing here needs
  a running Porelo. Wherever a test is about the contract, the stand-in serves the real `Tasks1` interface: as porelo
  publishes it, or until it does, taken from porelo's main at a commit named beside it.
- **A module holds no code until its issue is built**: it stays a `pom` with a `README.md` until then.

## Skills

`java-code-review`, `test-quality`, `security-audit`, `clean-code`, `solid-principles`, `graal`.
