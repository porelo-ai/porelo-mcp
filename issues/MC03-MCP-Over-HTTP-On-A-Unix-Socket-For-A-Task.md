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
- Every HTTP request needs `Authorization: Bearer <token>`; the SHA-256 digests of the expected and the presented token
  are compared with `MessageDigest.isEqual`; otherwise `401` with no detail. The token is never logged.
- Exit codes: 0 when stopped on `SIGTERM` or `SIGINT`, which close the socket and remove the path it bound itself; 1 when
  it fails after `ready`; 2 when refused at start, for wrong arguments, a refused token, or a refused socket path or
  directory; 3 when the daemon socket is unreachable or its interface unreadable before `ready`.
- Red first, against a tool called by someone without the token: requests with no token and with a wrong token get
  `401`. Against another user's process: a connection whose peer reader answers another uid is closed without an HTTP
  answer, a valid token included. Against a socket other users can open: its mode is `0600`. Against clobbering
  someone's file: a start onto an existing path exits 2 and leaves that path as it was.

## Acceptance

- Measured over a real Unix socket: no token, a wrong token, an empty bearer and a token one byte longer each get `401`;
  the right one lists the tools.
- A peer of another uid gets no answer, even with the right token.
- Token input that is empty, ends before `\n`, holds a space or a control byte, is shorter than 32 bytes or longer than
  1023 exits 2 before any socket is bound.
- No socket descriptor of the process (`/proc/self/fd`, `socket:[<inode>]`) is in `/proc/self/net/tcp` or `tcp6` while
  it serves.
- The token appears in no log line, no exception and no command line, checked by a test that searches the output.
- An unreachable daemon socket at start exits 3 before `ready`, with no socket bound.
