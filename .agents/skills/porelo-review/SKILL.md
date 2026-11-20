---
name: porelo-review
description: Porelo rules for skills and for readings by another model: where skills come from, applying them, the code reading before a hand-over, the plan reading for M and larger. Use before a hand-over and before building an issue of size M or larger.
---

> **BEGIN Shared Skill porelo-review** · sha256 `ae89658e18a1182b` · changed 2026-11-20T07:25Z

# porelo-review

## Skills

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

## Code and builds

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

## Sub-agents' claims

- **A sub-agent's statement about code is checked before it is relayed**: the named file, method or
  line is read before it goes into the channel, an issue or a question for the operator, since an
  invented fact reads as confident as a true one.

## What Porelo protects

- **Porelo protects the person from the agents and from threats from outside, not from their own
  rights or deliberate choices**; what a person builds into a task's image is their own risk. Every
  new guard and every reading's finding is judged by this.

> **END Shared Skill porelo-review** · sha256 `ae89658e18a1182b`
