# MC12 — Third-Party Notices In The Packages

```json
{
  "$schema": "issue-1.json",
  "id": "MC12",
  "title": "Third-Party Notices In The Packages",
  "state": "Review",
  "size": "M",
  "priority": "Medium",
  "rank": 2,
  "blockedBy": [],
  "covers": "The DEB and the RPM ship the full license and notice texts of everything compiled into the native executable, as one `THIRD-PARTY-NOTICES` file from the shared generator."
}
```

**Why this size.** a new build step, a Debian `copyright` file of its own, and the RPM moved to the plugin that can mark a
`%license` file, with its requirements declared by hand.

**What must be true.** Whoever installs `porelo-mcp` finds the license and notice texts of every third-party part
compiled into its native executable: in the DEB as `/usr/share/doc/porelo-mcp/THIRD-PARTY-NOTICES`, named in
`debian/copyright`, and in the RPM as a `%license` file.

## Why

Both packages ship only Porelo's own `LICENSE` (as `copyright` in the DEB, as documentation in the RPM) and the CycloneDX
bill of materials. The executable compiles in more than Porelo's code: today `org.jspecify:jspecify` 1.0.0 (Apache-2.0)
and `org.fuin.porelo:porelo-wire`, read with `dependency:list` at runtime scope, and the GraalVM native-image runtime
that every native executable carries. Their licenses ask for their texts to travel with the binary.

## How it stands

- Backlog (operator, 2026-11-10); built, in Review.

## Plan

- `server/pom.xml`, profile `native`, at `package` after the bill: `dependency:build-classpath` at runtime scope into
  `target/classpath.txt`, then `porelo-release third-party-notices target/classpath.txt target/sbom.cdx.json
  ${java.home} target/THIRD-PARTY-NOTICES`, so the texts come from the shared generator and nothing is collected here.
- The DEB ships `THIRD-PARTY-NOTICES` in `/usr/share/doc/porelo-mcp/`, and its `copyright` becomes a machine-readable
  Debian file of its own, `server/src/deb/copyright/copyright`, that names the program's license and the file.
- The RPM moves from `org.codehaus.mojo:rpm-maven-plugin`, whose mappings can mark `%doc` and `%config` but not
  `%license`, to `de.dentrassi.maven:rpm`, as porelo's packages are built: `LICENSE` and `THIRD-PARTY-NOTICES` under
  `/usr/share/licenses/porelo-mcp/` marked `%license`. That plugin reads no library from the executable, so the C
  library and zlib floors become explicit requirements, from the same properties the DEB declares.
- Test first, red: `PackagesIT` asks for `THIRD-PARTY-NOTICES` in the DEB, named in its `copyright`, for both license
  files in `rpm -qLp`, and for the RPM's requirements on the C library and zlib at the floors.

## Acceptance

- An unpacked DEB holds `/usr/share/doc/porelo-mcp/THIRD-PARTY-NOTICES`, and its `copyright` names it; the RPM lists it
  with `rpm -qL`, and still requires the C library and zlib at the floors the executable needs.
- The file holds the full texts for jspecify, porelo-wire and the GraalVM runtime; a dependency added to the server
  appears in it without a change here.

## Reading

A read-only reading by another model found eight points, each checked here:

- Taken, each seen red by a deliberate break first: the RPM's notices were only listed, not read (now compared with the
  DEB's, and its `LICENSE` with the program's); the bill's components were only asked to exist (now exactly porelo-wire
  and jspecify, read from the bill inside the DEB); the notices' sections were not shown to hold texts (now the full
  Apache-2.0 text and the runtime's GPLv2 with the Classpath exception); the RPM's requirements were not held to the
  libraries the executable links (now one per `NEEDED` entry, as the DEB's are).
- Taken, no test: two comments and the README still spoke of rpmbuild reading the requirements, and one test's name
  still promised a license it no longer checked.
- Declined: the RPM lost its `URL` (it did not: `rpm -qip` reads `https://github.com/porelo-ai/porelo-mcp/` from the
  project's url); `provided` jars missing from the classpath (none exists, and a jar the bill names without a section
  fails the notices test); SPDX names in the Debian `copyright` (the same as porelo's own packages, and the third
  parties' licenses stand in one place, the notices file, rather than twice); the index's group order (the shared
  tool's order holds until the shared rule follows it).
