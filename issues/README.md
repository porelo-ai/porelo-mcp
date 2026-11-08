# Issues

The open tasks of `porelo-mcp`, grouped by state; the order within a group is the priority. The rules for an issue, and
who sets which state, are in [AGENTS.md](../AGENTS.md). Its prefix is `MC`. A group with nothing in it is left out.

## Review

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="mc01"></a>[MC01](MC01-The-MCP-SDK-Chosen-By-Measurement.md) | S | - | The MCP layer built on a Java MCP SDK or on a small layer of its own, chosen by measurement: a layer of its own, a third of the SDK's size and an eighth of its start. |
| <a id="mc02"></a>[MC02](MC02-Tools-Generated-From-The-Daemon-s-Interface.md) | M | - | Every method the daemon describes in `Tasks1` an MCP tool, its schemas from the Varlink types, its answers and refusals the daemon's, tested against a stand-in daemon. |
| <a id="mc03"></a>[MC03](MC03-MCP-Over-HTTP-On-A-Unix-Socket-For-A-Task.md) | M | - | `porelo-mcp serve` for one task: streamable HTTP on a `0600` Unix socket, the peer's uid checked, a bearer token from standard input, `ready` and the exit codes of the binding. |
| <a id="mc05"></a>[MC05](MC05-Whether-It-Reaches-The-Daemon.md) | S | - | `porelo-mcp --check` says whether it reaches the daemon, with its version and the methods offered. |
| <a id="mc04"></a>[MC04](MC04-Stdio-For-A-Person-s-Own-Client.md) | S | - | `porelo-mcp stdio` for a person's own MCP client on the host: the reading methods, writing ones only from the person's own list, destructive ones never. |

## Implementing

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="mc06"></a>[MC06](MC06-The-Native-Executable-In-Its-DEB-And-RPM-Packages.md) | M | - | The native executable in a DEB and an RPM package of its own, built for any x86-64 machine, checked and published by the build workflow. |

## Idea

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="mc07"></a>[MC07](MC07-Streams-As-MCP-Progress.md) | - | - | A method that answers only as a stream callable as a tool, its replies as MCP progress. |
| <a id="mc08"></a>[MC08](MC08-MCP-2026-07-28-Spoken.md) | - | - | A client that offers only MCP `2026-07-28` reaches the tools, over stdio and HTTP. |
