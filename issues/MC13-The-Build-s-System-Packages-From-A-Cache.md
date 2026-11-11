# MC13 — The Build's System Packages From A Cache

```json
{
  "$schema": "issue-1.json",
  "id": "MC13",
  "title": "The Build's System Packages From A Cache",
  "state": "Review",
  "size": "S",
  "priority": "Medium",
  "rank": 7,
  "blockedBy": [],
  "covers": "The build checks that the runner image carries `rpm` and zlib's headers instead of fetching them from the Ubuntu mirrors on every run."
}
```

**Why this size.** One step of one workflow; nothing ships differently.

**What must be true.** The build workflow takes every download from outside that is not a Maven artifact from a cache,
so a mirror that answers 5xx for a while does not turn a build red, and the cache never grows past the newest state.

## Plan

- Today `.github/workflows/build.yml`, step *The packaging tools*, runs `apt-get update` and `apt-get install rpm
  zlib1g-dev` against the Ubuntu mirrors on every run, uncached. Everything else is cached already: the GraalVM, shfmt
  and the Maven wrapper by `.github/actions/pinned-jdk` (by digest or pin), the local Maven repository by the poms' hash. The
  `curl` calls of the publish steps read the published index on purpose and stay uncached.
- Measured: the `ubuntu-24.04` runner image (20261101.360.1) lists `rpm` among its apt packages, and in the last green
  Build the step's `apt-get install` unpacked nothing, so `zlib1g-dev` is there too. The step therefore downloads only
  the mirrors' package lists. It is replaced by a check that both packages are installed (`dpkg-query`), which fails
  loud naming the one missing; no download, so no cache and nothing to expire.
- Should a later image drop one of them, that check turns the Build red and says which; the cache of `.deb` files
  keyed by version, older keys deleted after a save, is then built in this issue.
- The shared `pinned-jdk` action's Maven cache, one entry per pom change, is its owner's to bring under the same rule.
- No red-first test: a change to a workflow only, proven by its own Build.

## Acceptance

- The Build makes no request to an Ubuntu mirror (read in its log) and is green.
- With one of the two package names misspelt in the check, the step fails and names it (seen once, then restored).
