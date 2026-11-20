# MC15 — A Maven Cache That Follows The Resolved Versions

```json
{
  "$schema": "issue-1.json",
  "id": "MC15",
  "title": "A Maven Cache That Follows The Resolved Versions",
  "state": "Review",
  "size": "XS",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "The Build's Maven cache is saved again whenever the resolved dependencies change, so a Build after a moved parent, BOM or snapshot downloads nothing from Maven Central that an earlier Build already fetched."
}
```

**Why this size.** The shared form taken over as a copy into one composite action, one new action and three workflows: minutes.

**What must be true.** A Build whose poms did not change downloads from Maven Central only what no earlier Build
fetched. A Build therefore does not fail with `429 Too Many Requests` from Central because it fetches the same poms
and jars again on every run.

**Why.** The versions resolved come from outside this repository's poms: the parent `porelo-parent`
`0.2.2-SNAPSHOT`, the two BOMs imported in `pom.xml`, `porelo.buildtools.version` and `porelo-wire`
`0.5.1-SNAPSHOT`. All of them are snapshots that move without a pom here changing. A cache keyed by the poms alone
is hit exactly, and `actions/cache` never saves over an exact hit, so what moved would be downloaded again by every
Build.

## Plan

- `.github/actions/pinned-jdk/action.yml` restores the local Maven repository by a key holding the poms' hash, the run id and
  its attempt, falls back to the poms' hash and then to any Maven cache of the runner's system, touches a marker
  after the restore, and gives the key as its output `maven-cache-key`.
- The new `.github/actions/maven-cache-save/action.yml` saves under that key only when a jar or pom changed status
  after the marker (`find -cnewer`, since Maven dates a downloaded file by the server's Last-Modified).
- The one job of each workflow that uses the pinned GraalVM (`build.yml`, `shared-rules.yml`, `workflows.yml`)
  names that step `pinned-jdk` and ends with the save, under `if: always()`.

## Acceptance

- The first Build after the push logs `fetched after the restore, among others: ...` and saves.
- The next Build logs `nothing fetched after the restore: nothing saved`, and its Maven output shows no
  `Downloading from central` for a release artifact.
