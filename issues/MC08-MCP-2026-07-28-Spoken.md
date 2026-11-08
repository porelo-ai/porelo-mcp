# MC08 — MCP 2026-07-28 Spoken

**Status:** Finished
**Size:** M - a second era beside the first in the session, `server/discover`, and the HTTP headers checked against the body.

**What must be true.** An MCP client that offers only the version `2026-07-28` reaches the daemon's tools through
`porelo-mcp`, over stdio and over HTTP, as a client of the version before it does today.

## Plan

- `McpSession` reads a request whose `params._meta` names `io.modelcontextprotocol/protocolVersion` as one of
  `2026-07-28`: the version spoken or `-32022` naming the supported ones, the client's capabilities present or
  `-32602`, then only `server/discover`, `tools/list` and `tools/call`. Every result carries `resultType` and the
  server's info; `tools/list` and `server/discover` also `ttlMs` and `cacheScope`. The first era is untouched.
- `Era` reads an HTTP request's era from its headers and body. A modern one's `MCP-Protocol-Version`, `Mcp-Method` and
  `Mcp-Name` must say what the body says, else `400` with `-32020`; a refusal of the request as such gets `400`.
- The specification's status: its pages call it neither final nor draft; built against the published text.
- Red first: discovery, `resultType` on list and call, an unknown version, missing capabilities, the first era's
  methods gone, over stdio end to end, and each header against the body.

## Acceptance

- Over stdio: a client sending only `2026-07-28` requests discovers and calls `List` without `initialize`, and gets
  the daemon's reply as a complete result. Seen red once.
- In the session: `server/discover` names both eras; an unknown version gets `-32022` with what is supported; missing
  capabilities get `-32602`; `ping` and `initialize` are no methods of this era. Each seen red once.
- Over HTTP: a modern request with matching headers is served; a method, version or tool name that differs or is
  missing, a tool name on another method, a body that is no object, or a modern body without its headers, gets `400`
  and `-32020`; an unknown version gets `400` and `-32022`; a modern notification without its version in `_meta` is
  taken; the first era is served as before. Each seen red once.
- A refused and a streamed call of `2026-07-28` are complete results; a cancel of a version not spoken cancels nothing;
  `-32022` names the versions of both eras. Each seen red once.
