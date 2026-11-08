# MC10 — The Package Version In ServerInfo

**Status:** Idea

**What must be true.** A client of `porelo-mcp` learns which build it talks to: `serverInfo.version`, in `initialize`
and in every result of `2026-07-28`, names the version the packages carry, such as `0.1.0~snapshot.5`, not the
project's `0.1.0-SNAPSHOT`.

## Why

In the operator's test 5 of 2026-11-08 the task's agent saw `0.1.0-SNAPSHOT`, so nobody could tell from the client
which snapshot the machine runs. The build already knows the package version (`deb.version`), which
`check-package-version` holds both packages to.

## To be checked

- How the version reaches the executable: a resource the build writes, read once at start, as the native image keeps
  it.
- A local build's version, `0.1.0~snapshot.0+local.<time>`, is named as it is.
