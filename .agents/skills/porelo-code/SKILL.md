---
name: porelo-code
description: Porelo rules for code and builds: downloads, input, comments, dot files, Maven, modules, READMEs, publishing, parent pom, NullAway, Javadoc, pins, snapshots, native images, licenses. Use when changing code, a pom, a workflow or a release.
---

> **BEGIN Shared Skill porelo-code** · sha256 `4570b2f6f99bfe18` · changed 2026-11-20T07:25Z

# porelo-code

## Security and tools

- **Every download follows redirects (`curl -L`), since any server may answer with one, and is
  checked against the digest its source names before it is used.** Credentials are never passed on
  to another host (`--location-trusted` is never used).
- **Input from outside - upstream metadata, the environment, a file or an answer from another
  program - is validated before it reaches a file, a command or a decision.**
- **A binary run with more rights than its caller is taken by path and refused when it or its
  directory could have been placed or changed by anybody else.**
- **A long build or suite is watched through `tee` into a file**, never through a pipe into `grep`,
  which holds everything back until the end and looks like a hang.

## Code and builds

- **A comment says why, never what, in one line where it can.** Reasoning that does not fit goes into
  documentation or a decision, and a small named method is preferred over a comment explaining a
  block.
- **Dot files are not committed.** `.gitignore` ignores `.*` and excepts only what a build, the
  shared code style or the agents' rules need - `.github`, `.gitignore`, `.gitkeep`, `.editorconfig`,
  `.git-blame-ignore-revs`, `.agents`, and `.claude/skills` alone of `.claude` (`!.claude`, `.claude/*`,
  `!.claude/skills`), in a Maven repository also `.mvn` - and what is true of one machine goes into
  `.AGENTS.md` or a local skill.
- **Maven is the build system: a Java repository builds, checks and tests with Java and Maven only.**
  Another build tool only where the platform a repository builds for supports no other - an IntelliJ
  plugin, since JetBrains supports only Gradle - named with the reason in its repository's own part. A
  file that cannot be Java - `mvnw`, or a script that runs where there is no Java yet - is named in its
  repository's own part with the reason, and no repository keeps a copy of a helper another one has.
  There are two exceptions. One is `.github/actions/pinned-jdk/pinned.sh`, the rule for pinned downloads,
  which a repository's `pinned-jdk` action copies unchanged from porelo-buildtools, since that step runs
  before there is Java; `check-shared` refuses a copy that differs. The other is porelo-planner's native
  executable, which a CI step downloads from Artifactory, pinned by version and sha256, and runs to check
  the repository's issues, stories, commits and pushes: a pinned download, never a copy kept in it.
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

## Snapshots

- **A dependency between Porelo repositories may be pinned to a snapshot, and whatever a snapshot needs of another
  Porelo repository is a snapshot too.** A pin to a release only, where the dependency moves on main, is a bug -
  Highest when it blocks a live test; publishing a snapshot is preferred over cutting a release.

## Secrets and issue numbers in code

- **A secret never appears in a command line, a log line, a file name or an answer.** It reaches a
  process through its environment or standard input, is stored only encrypted and readable by its
  owner, never touches a filesystem in CI, and is never promised to be wiped from memory in Java or
  Dart - only kept in fewer copies for less time.
- **Code, comments, test names and anything that ships never cite an issue number**; they state the
  constraint itself, since the issue is deleted once it is finished.

## Failing

- **Fail closed and loud**: when a dependency is unreachable, a key is unknown or a check cannot run,
  stop with a non-zero exit code and say why; inside a program, an exception that says why does the
  same. Never silently do less.

## Shell scripts

- **A change to a shell script runs `./mvnw spotless:check` before Push ready** in every repository whose build
  checks shell scripts with shfmt through spotless, as `porelo-parent` does; CI fails on it.

> **END Shared Skill porelo-code** · sha256 `4570b2f6f99bfe18`
