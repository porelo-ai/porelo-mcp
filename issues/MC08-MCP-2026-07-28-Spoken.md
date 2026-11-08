# MC08 — MCP 2026-07-28 Spoken

**Status:** Implementing
**Size:** M - a second era beside the first in the session, `server/discover`, and the HTTP headers checked against the body.

**What must be true.** An MCP client that offers only the version `2026-07-28` reaches the daemon's tools through
`porelo-mcp`, over stdio and over HTTP, as a client of the version before it does today.

## To be checked

- `2026-07-28` beside the first era, as [doc/decisions.md](../doc/decisions.md) lists it: `server/discover`, the version
  and capabilities in each request's `_meta`, a `resultType` in every result, no `initialize`, `ping` or session, and
  on HTTP the headers `MCP-Protocol-Version`, `Mcp-Method` and `Mcp-Name` checked against the body.
- Whether the specification is final, or only a milestone's; the version is built against its published text.
