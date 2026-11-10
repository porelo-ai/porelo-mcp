# MC12 — Third-Party Notices In The Packages

```json
{
  "$schema": "issue-1.json",
  "id": "MC12",
  "title": "Third-Party Notices In The Packages",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 2,
  "blockedBy": [],
  "covers": "The DEB and the RPM ship the full license and notice texts of everything compiled into the native executable, as one `THIRD-PARTY-NOTICES` file from the shared generator."
}
```

**What must be true.** Whoever installs `porelo-mcp` finds the license and notice texts of every third-party part
compiled into its native executable: in the DEB as `/usr/share/doc/porelo-mcp/THIRD-PARTY-NOTICES`, named in
`debian/copyright`, and in the RPM as a `%license` file.

## Why

Both packages ship only Porelo's own `LICENSE` (as `copyright` in the DEB, as documentation in the RPM) and the CycloneDX
bill of materials. The executable compiles in more than Porelo's code: today `org.jspecify:jspecify` 1.0.0 (Apache-2.0)
and `org.fuin.porelo:porelo-wire`, read with `dependency:list` at runtime scope, and the GraalVM native-image runtime
that every native executable carries. Their licenses ask for their texts to travel with the binary.

## The shape

- The texts come from porelo-buildtools' shared generator, which collects each compiled-in jar's `LICENSE` and `NOTICE`
  and the licenses they embed into one `THIRD-PARTY-NOTICES` file; nothing here collects them by hand.
- `server/pom.xml` runs the generator at `package` and maps its file into both packages beside `LICENSE` and
  `sbom.cdx.json`.

## Acceptance

- An unpacked DEB holds `/usr/share/doc/porelo-mcp/THIRD-PARTY-NOTICES`, and its `copyright` names it; the RPM lists it
  with `rpm -qL`.
- The file holds the full texts for jspecify, porelo-wire and the GraalVM runtime; a dependency added to the server
  appears in it without a change here.

## To be checked

- Whether the shared generator also covers the GraalVM runtime, or whether that text is added here.
- Which license porelo-wire's published pom names, and whether the generator treats Porelo's own artifacts as third
  party.
