# MC13 — The Build's System Packages From A Cache

**Status:** Idea
**Size:** S - one step of one workflow and the cache that feeds it; nothing ships differently.

**What must be true.** The build workflow takes every download from outside that is not a Maven artifact from a cache,
so a mirror that answers 5xx for a while does not turn a build red, and the cache never grows past the newest state.

## Plan

- Today `.github/workflows/build.yml`, step *The packaging tools*, runs `apt-get update` and `apt-get install rpm
  zlib1g-dev` against the Ubuntu mirrors on every run, uncached. Everything else is cached already: the GraalVM, shfmt
  and the Maven wrapper by `.github/actions/pinned-jdk` (by digest or pin), the local Maven repository by the poms' hash. The
  `curl` calls of the publish steps read the published index on purpose and stay uncached.
- First see whether the step is needed at all: if the pinned runner image already carries `rpm` and zlib's headers, the
  step goes and nothing needs caching.
- Otherwise the `.deb` files are kept in `actions/cache`, keyed by the runner image and the exact versions `apt-get`
  would install (`apt-cache policy` after `update`, or a list pinned in the workflow), restored before the install and
  installed with `dpkg -i` from the cache on a hit; a miss fetches as today and saves. A ready-made action for apt
  caching is weighed against these few lines; the choice goes into this plan.
- Only the newest entry is kept: after a save, the job deletes this cache's older keys by prefix through the cache API
  with the job's token (`actions: write`), unless GitHub's repository settings can expire cache entries after a set
  time, which is read and written here. The Maven cache's own restore-key pattern leaves one entry per pom change and is
  held to the same rule in this issue, as it lives in the shared `pinned-jdk` action: that change goes to its owner.
- No red-first test: a workflow change, proven by a run with a cold and a warm cache, both green, and by the cache list
  showing one entry per key prefix afterwards.

## Acceptance

- A run on a warm cache makes no request to an Ubuntu mirror (read in the step's log), and the build is green.
- After a run with a changed key, the repository's cache list holds only the newest entry of that prefix.

## To be checked

- Whether `ubuntu-24.04`'s runner image carries `rpm` and `zlib1g-dev` already.
- Whether GitHub's repository settings can delete cache entries after a set time, or only by age of last use.
- Whether a change to the shared `pinned-jdk` action's Maven cache key is wanted by its owner, or stays as it is.
