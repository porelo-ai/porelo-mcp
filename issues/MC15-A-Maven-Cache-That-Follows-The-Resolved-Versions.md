# MC15 — A Maven Cache That Follows The Resolved Versions

```json
{
  "$schema": "issue-1.json",
  "id": "MC15",
  "title": "A Maven Cache That Follows The Resolved Versions",
  "state": "Backlog",
  "size": "S",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "The Build's Maven cache is saved again whenever the resolved dependencies change, so a Build after a moved parent, BOM or snapshot downloads nothing from Maven Central that an earlier Build already fetched."
}
```

**Why this size.** One composite action and its workflows, no contract another repository reads: under an hour.

**What must be true.** A Build whose poms did not change downloads from Maven Central only what no earlier Build
fetched. A Build therefore does not fail with `429 Too Many Requests` from Central because it fetches the same poms
and jars again on every run.

**Why.** `.github/actions/pinned-jdk/action.yml` restores and saves the local Maven repository under the key
`maven-<os>-<hashFiles('**/pom.xml')>`. The versions actually resolved come from outside these poms: the parent
`porelo-parent` `0.2.2-SNAPSHOT`, the two BOMs imported in `pom.xml`, `porelo.buildtools.version` and
`porelo-wire` `0.5.1-SNAPSHOT`, all of them snapshots that move without a pom here changing. On an exact key hit,
`actions/cache` never saves, so whatever moved is downloaded again by every Build until a pom here changes.

## Plan

- Split the cache in `.github/actions/pinned-jdk/action.yml` into `actions/cache/restore` and `actions/cache/save`.
  Restore by the prefix `maven-<os>-` and save at the job's end under a key that changes with what was resolved:
  the hash of the poms plus a hash of the resolved artifacts' list (`find` over the local Maven repository for '*.pom' -o -name
  '*.jar'` after the build). Skip the save when that key was already hit.
- Delete the old snapshot versions of `org.fuin.porelo` before saving, so the cache does not grow with every
  snapshot.
- The test that goes red first: a Build log after an exact hit holds no `Downloading from central` line for a
  release artifact. A second Build on the same commit shows it.

## Acceptance

- A Build log measured right after a Build on the same poms has no `Downloading from central` for a release
  artifact, and only snapshot metadata is checked online.
- The cache size reported by the save step stays in the same range over three Builds.

## To be checked

- Measure in a recent Build log whether `Downloading from central` follows a cache hit. The job logs need an
  authenticated API call; the unauthenticated rate limit was exhausted when this issue was filed.
- Check whether the parent or a BOM resolves a version range online on every Build.
