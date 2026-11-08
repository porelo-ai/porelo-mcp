# Decisions

What holds and why: one row per decision, and its full text below.

| Covers | What holds |
|---|---|
| MCP layer | [MCP is spoken by a small layer of its own, not by an SDK](#mcp-is-spoken-by-a-small-layer-of-its-own-not-by-an-sdk) |
| MCP version | [It speaks both eras: `initialize` up to `2025-11-25`, and `2026-07-28`](#it-speaks-both-eras-initialize-up-to-2025-11-25-and-2026-07-28) |

## MCP is spoken by a small layer of its own, not by an SDK

`porelo-mcp` speaks MCP through its own JSON-RPC 2.0 layer over `porelo-wire`'s `Json`. For a task it reads HTTP/1.1
itself, on a Unix socket bound through the JDK's `UnixDomainSocketAddress`. It does not use the MCP project's Java SDK.

Measured on two spikes, each serving one tool over stdio and over streamable HTTP on a Unix socket, built as native
images with the pinned GraalVM and `-march=x86-64`:

| | Own layer | Java SDK 2.1.0 with Jetty 12.1.14's Unix-socket connector |
|---|---|---|
| Native executable | 13.6 MB | 35.5 MB |
| Native build | 31 s | 114 s |
| Start to the first `initialize` answer, HTTP | 5-6 ms | 35-52 ms |
| Start to the first `initialize` answer, stdio | 4-5 ms | 37 ms |
| Libraries pulled in | none beyond `porelo-wire`, already needed for Varlink | 23 jars, 9.4 MB: Jackson 3, Reactor, a JSON Schema validator, YAML, Jetty, the servlet API |
| Their licenses | `porelo-wire`: GPL-3.0-or-later in 0.5.0's pom | MIT (the SDK, SLF4J), Apache-2.0 (Jackson, Reactor, the validator, YAML, `itu`), MIT-0 (Reactive Streams), EPL-2.0 or Apache-2.0 (Jetty), EPL-2.0 or GPL-2.0 with the Classpath Exception (the servlet API) |
| Reachability metadata | none | 26 KB, traced by the agent; one library's metadata uses an experimental option |
| TCP sockets in the process while it serves | 0 | 0 |
| MCP versions | as built | `2024-11-05` to `2025-11-25`; `2026-07-28` only in the milestone 2.2.0-M2 |

- The own layer's spike read JSON with `Json.parse`; the product reads with `Json.parseDecimals`, which the size and
  start above did not include. Neither spike called the daemon, checked a peer or a token: both measured the MCP layer
  and its transport alone.
- The TCP check lists the process's socket descriptors under `/proc/<pid>/fd` and looks for each inode in that network
  namespace's `tcp` and `tcp6`. A process listening on loopback TCP was seen to fail it.
- The MCP clients of Claude Code, Codex and agy each offer the versions from `2024-11-05` to `2026-07-28`, read from their
  executables. The gateway on the host forwards HTTP unchanged and adds no version of its own.
- The SDK does no work here that `porelo-mcp` needs. The tools are generated from the daemon's interface; arguments go
  to the daemon unchecked; the session is one process per task. What it would bring - a schema validator, reactive
  sessions, a servlet container - is size, start time and attack surface without a use.
- The cost is an HTTP reader of its own, on a socket any process of the person's own user may connect to, with or
  without the token. It stays bounded to what streamable HTTP needs, each bound held by a test:
  - one request per connection: every answer says `Connection: close`, and the connection is closed after it, so no
    second request can hide behind the first;
  - a read deadline per connection, so a peer that stalls in the head or the body is closed, and connections are served
    apart, so one stalled peer holds up no other; at most 64 at once, the next waiting in the kernel's backlog;
  - after its answer a connection is let go at once: what has already arrived beyond the request, at most 64 KiB, is
    read away without waiting, so closing does not reset the answer;
  - the head at most 16 KiB and 64 header lines, counted while it is read, else `431`; the body at most 1 MiB, checked
    against `Content-Length` before anything is allocated, else `413`;
  - lines ended by `\r\n` only: a bare `\n`, a folded line, white space before a header's `:`, or a control byte in a
    value other than a tab is refused; only spaces and tabs around a value are dropped;
  - refused with `400` and closed: any `Transfer-Encoding`; a missing, repeated, or not purely decimal `Content-Length`;
    a body shorter than `Content-Length`; a repeated `Authorization` or `Host`;
  - the token checked once the head is read and before anything else, so a request without it gets `401` whatever its
    path or method; then a path other than `/mcp` gets `404`, and a method other than `POST` gets `405`;
  - an answer as `application/json`, never a stream.
- A number must travel exactly as it came: the JSON-RPC `id` back to the client, an `int` argument on to the daemon.
  `Json.parse` reads every number as a double and writes `2` back as `2.0`; `Json.parseDecimals`, from `porelo-wire`
  0.5.1 on, reads it as a `BigDecimal` and writes `2`. The layer reads with `parseDecimals`.
- `porelo-wire`'s Varlink client still reads the daemon's replies with `Json.parse`, so 67108864 would leave as
  `6.7108864E7`. Until it reads them exactly, a whole number up to 2^53 in a reply or a refusal is written
  as the whole number it is; a larger one may already have lost digits on the way in.

What would change the answer: a fault found in the HTTP reader that a bound does not settle; a need for request bodies
in chunked encoding, for many sessions at once, or for server-to-client streams beyond what one process per task needs.
Then Jetty's Unix-socket connector carries HTTP, still without the SDK.

## It speaks both eras: `initialize` up to `2025-11-25`, and `2026-07-28`

`porelo-mcp` answers `initialize` with the version the client asks when it is `2025-11-25`, `2025-06-18` or `2025-03-26`,
and with `2025-11-25` otherwise. Beside it, it speaks `2026-07-28`, built against that version's published text, whose
pages call it neither final nor draft. Each request says its era by itself, so the two need no state between them; how
each is answered is in [binding.md](binding.md#mcp-versions).

- `2026-07-28` removes `initialize`, `ping` and sessions, carries the version and the client's capabilities in every
  request's `_meta`, requires `server/discover`, a `resultType` in every result, and on HTTP the headers
  `MCP-Protocol-Version`, `Mcp-Method` and `Mcp-Name` checked against the body. That is a protocol of its own beside the
  first, not a field more, and it is answered beside the first era, not in its place.
- The MCP clients of Claude Code, Codex and agy each offer both eras. The specification has such a client fall back to
  `initialize` when a server does not answer `server/discover` as a modern server does; a client that offers only
  `2026-07-28` has nothing to fall back to, and reaches the tools only because the second era is spoken.
- Claude Code's client checks `structuredContent` against a tool's `outputSchema` only when `isError` is false, read in
  its executable. So a refusal can carry `{"error", "parameters"}` as its structured content, although that is not the
  method's reply.

What would change the answer: no client the agents use offering the first era any more, when it is dropped; or a later
version of the second era, which is added beside `2026-07-28`.

