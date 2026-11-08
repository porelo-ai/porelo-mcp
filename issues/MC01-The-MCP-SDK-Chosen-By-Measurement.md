# MC01 — The MCP SDK Chosen By Measurement

**Status:** Review
**Size:** S - one spike and a measured comparison, no contract.

**What must be true.** The MCP layer of `porelo-mcp` is built on a Java MCP SDK or on its own small JSON-RPC layer,
chosen by measurement, and the choice is written down with the numbers it rests on.

## Plan

- Candidates: the MCP project's official Java SDK, whose streamable HTTP server runs in a servlet container (here Jetty
  with its Unix-socket connector), and a layer of its own over `porelo-wire`'s `Json` speaking JSON-RPC 2.0 with the MCP
  methods `initialize`, `tools/list`, `tools/call` and `ping`, on a small HTTP/1.1 reader over the JDK's
  `UnixDomainSocketAddress`.
- A throwaway spike in `target/` per candidate: one tool, served over stdio and over streamable HTTP on a Unix socket,
  built as a native image with the pinned GraalVM and `-march=x86-64`.
- Measured for each: whether it serves on a Unix socket with no TCP socket in the process at all; the executable's size;
  start to first answer; the dependencies it pulls in, with their licenses; the reachability metadata it needs; the MCP
  protocol versions it speaks.
- The gateway on the host forwards a task's HTTP to the destination unchanged and has no MCP version of its own, so the
  version is the one the agent's client and `porelo-mcp` agree on: the current specification's streamable HTTP, and what
  the MCP clients of Claude Code, Codex and agy speak today.
- Red first, against a server reachable from other users' processes: a spike check that lists the process's socket
  descriptors (`/proc/self/fd`, `socket:[<inode>]`) and fails when any inode is in `/proc/self/net/tcp` or `tcp6`, seen
  red once on a spike bound to loopback TCP. It moves into [MC03](README.md#mc03)'s tests.
- The result goes to `doc/decisions.md`, opened with its index; the spike is not committed.

## Acceptance

- `doc/decisions.md` names the choice, each measured number, and what would change the answer.
- A candidate that needs a TCP socket, even on loopback, is out: the check above shows whether it does.
