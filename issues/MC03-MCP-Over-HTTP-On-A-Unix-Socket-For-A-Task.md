# MC03 — MCP Over HTTP On A Unix Socket For A Task

**Status:** Backlog; blocked by [MC02](README.md#mc02).
**Size:** M - the `serve` command against the binding in `doc/binding.md`, a contract porelo reads.

**What must be true.** `porelo-mcp serve`, started by the daemon for one task, serves the tools over MCP streamable
HTTP on its Unix socket, to the person's own user alone and only with the token it was given, as
[doc/binding.md](../doc/binding.md) says.

## Plan

- `serve --task --socket --daemon-socket`, in this order: the arguments; the token from standard input (32 to 1023
  printable ASCII bytes ended by `\n`, at most 1024 bytes read, then closed); the interface from the daemon socket;
  `--socket` checked (absent, its directory no symbolic link, owned by the process's uid, mode `0700`) and bound with mode
  `0600`; `ready` on standard output, flushed. Streamable HTTP on the path `/mcp`.
- On every accepted connection the peer's uid from `SO_PEERCRED` (`jdk.net.ExtendedSocketOptions`), read through an
  injectable reader as `porelo-wire`'s `VarlinkServer` does, is compared with the process's own; another uid is closed
  without an answer.
- The HTTP reader holds every bound `doc/decisions.md` names for it: one request per connection, a read deadline,
  connections served apart, the head at most 16 KiB and 64 lines, the body at most 1 MiB, strict `\r\n` lines, `400`
  and close on `Transfer-Encoding`, on a missing, repeated or non-decimal `Content-Length`, on a short body and on a
  repeated `Authorization`; `404` off `/mcp` and `405` for any method but `POST`, both only after the token.
- A client of both MCP eras first sends a request of `2026-07-28`, with `MCP-Protocol-Version: 2026-07-28` and
  `Mcp-Method`, and falls back to `initialize` on a `4xx` without a modern error body. Such a request, once past the
  token, gets `400` with a plain JSON-RPC error, so the fallback happens.
- Every HTTP request needs `Authorization: Bearer <token>`; the SHA-256 digests of the expected and the presented token
  are compared with `MessageDigest.isEqual`; otherwise `401` with no detail. The token is never logged.
- Exit codes: 0 when stopped on `SIGTERM` or `SIGINT`, which close the socket and remove the path it bound itself; 1 when
  it fails after `ready`; 2 when refused at start, for wrong arguments, a refused token, or a refused socket path or
  directory; 3 when the daemon socket is unreachable or its interface unreadable before `ready`.
- Red first, against a tool called by someone without the token: requests with no token and with a wrong token get
  `401`. Against another user's process: a connection whose peer reader answers another uid is closed without an HTTP
  answer, a valid token included. Against a socket other users can open: its mode is `0600`. Against clobbering
  someone's file: a start onto an existing path exits 2 and leaves that path as it was. Against a request smuggled
  past the token: a second request behind the first on one connection is never answered, and a request carrying
  `Transfer-Encoding` gets `400`. Against one peer stopping everyone: a connection that sends half a head and stalls is
  closed after the deadline while another connection is answered meanwhile.

## Acceptance

- Measured over a real Unix socket: no token, a wrong token, an empty bearer and a token one byte longer each get `401`;
  the right one lists the tools.
- A peer of another uid gets no answer, even with the right token.
- Each bound of the HTTP reader is seen to refuse, one test each: a head over 16 KiB or 64 lines, a body over 1 MiB, a
  bare `\n`, a folded line, `Transfer-Encoding`, two `Content-Length`s, `Content-Length: +10`, a short body, two
  `Authorization`s, a stalled peer; and without the token, `GET /other` gets `401`, not `404` or `405`.
- Token input that is empty, ends before `\n`, holds a space or a control byte, is shorter than 32 bytes or longer than
  1023 exits 2 before any socket is bound.
- No socket descriptor of the process (`/proc/self/fd`, `socket:[<inode>]`) is in `/proc/self/net/tcp` or `tcp6` while
  it serves.
- The token appears in no log line, no exception and no command line, checked by a test that searches the output.
- An unreachable daemon socket at start exits 3 before `ready`, with no socket bound.
