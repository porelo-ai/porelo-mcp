# MC05 — Whether It Reaches The Daemon

**Status:** Review
**Size:** S - one command reading two Varlink methods.

**What must be true.** `porelo-mcp --check` tells a person or the daemon whether `porelo-mcp` reaches the daemon, with
the daemon's version and the methods it is offered.

## Plan

- `--check [--daemon-socket <path>]`, the socket by default `$XDG_RUNTIME_DIR/porelo/porelod.sock`:
  `org.varlink.service.GetInfo` for the version, `GetInterfaceDescription` for the methods, printed one per line.
- Exit 0 when both answered. Exit 3, naming what it asked and what it got, when the socket is unreachable, the
  description does not parse, or `XDG_RUNTIME_DIR` is unset or empty and no socket was given.
- Red first, against a health check that says "reached" when it was not: against no socket at all the command exits 3
  and names the path it tried.

## Acceptance

- Against a stand-in daemon it prints the stand-in's version and exactly the methods it describes, and exits 0.
- Against a missing socket, a socket that answers an unparsable description, and an empty environment, it exits 3 and
  says which.
