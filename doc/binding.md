# How the daemon starts porelo-mcp

The contract between `porelo-mcp` and `porelod`. It changes only together with porelo. What a task may call is decided
by `porelod` alone; `porelo-mcp` passes every answer and refusal on as it came.

## Start for a task

- `porelod` starts one `porelo-mcp` process per task it allows one, as the person's own user, again on resume, and
  stops it with the task:

      porelo-mcp serve --task <container> --socket <dir>/<container>.sock --daemon-socket <dir>/<container>.daemon.sock

- `<dir>` is `$XDG_RUNTIME_DIR/porelo/mcp`, made by `porelod` with mode `0700`. `porelod` removes a stale socket of the
  task before a start or a resume.
- `--task` only names the process in its log and in `task status`. It is never sent to the daemon and carries no right.
- The start goes in this order, and nothing of a later step happens when an earlier one failed:
  1. the arguments are read;
  2. the token is read from standard input;
  3. the interface is read from the daemon socket;
  4. `--socket` is checked and bound;
  5. `ready` is printed on standard output and flushed.

## The daemon socket

- `--daemon-socket` is a Varlink socket `porelod` opens for this one process, bound to the task. It serves only the
  methods the signed project file allows and takes the caller from the socket it is reached on, never from a field.
- Its `org.varlink.service.GetInterfaceDescription` for `org.fuin.porelo.Tasks1` is the interface cut down to those
  methods. It keeps every type and every error, so it parses as a complete interface, and an error a remaining method
  raises is always declared.
- `porelo-mcp` never opens the person's own daemon socket in this mode.

## The MCP socket

- Before binding, `porelo-mcp` refuses to start when `--socket` already exists, or when its directory is a symbolic
  link, is not owned by its own uid, or has a mode other than `0700`.
- It binds `--socket` and sets its mode to `0600`. It speaks MCP streamable HTTP on the path `/mcp` and has no TCP port.
  The gateway on the host connects to it; the task's container never sees it.
- On every connection it reads the peer's credentials (`SO_PEERCRED`) and closes, without an answer, a connection whose
  peer uid is not its own.

## The token

- `porelod` writes the token on standard input as one line: 32 to 1023 printable ASCII bytes (`0x21` to `0x7e`),
  ended by `\n`. `porelo-mcp` reads at most 1024 bytes. Input that ends before the `\n`, is longer, or holds any other
  byte is refused before anything is bound. Standard input is then closed. The token is never on the command line or in
  the environment.
- A request without `Authorization: Bearer <token>`, or with another token, gets `401` with no detail. The SHA-256
  digests of the two tokens are compared in constant time, so the comparison does not depend on where or whether the
  lengths differ. The token is never logged.
- The gateway adds the header as the destination's key, so the agent in the task never holds the token.

## Tools

- Each method the interface description offers is one tool, named by the method's name without its interface, such as
  `List` for `org.fuin.porelo.Tasks1.List`. Its input and output schemas come from the Varlink types, its description
  from the method's comment. The tools are read once per process; when the allowed set changes, `porelod` restarts it.
- The arguments of a call go to the daemon unchanged; `porelo-mcp` does not check them against the schema, so a
  parameter the daemon refuses comes back as the daemon's own `org.varlink.service.InvalidParameter`.
- A Varlink error reaches the agent as an MCP tool result with `isError: true`. Its structured content is
  `{"error": "<the error's full name>", "parameters": {<the error's parameters as they came>}}`, and its text holds the
  same.
- A name that is no tool is a JSON-RPC error (`-32602`). A call the daemon cannot be reached for, because its socket is
  gone or the connection broke, is a JSON-RPC error (`-32603`) naming the socket; the process does not exit for it.
- A call without a progress token, and every call over a task's socket, is made without `more: true`. A method that needs
  a stream gets the daemon's own `StreamRequired` then, passed on as any other error.

## MCP versions

`porelo-mcp` speaks both eras of MCP, over stdio and over a task's socket alike; each request is read by itself, so a
client may use either.

- **The first era**, with `initialize`: it answers with the version the client asks when that is `2025-11-25`,
  `2025-06-18` or `2025-03-26`, and with `2025-11-25` otherwise.
- **`2026-07-28`**, without a handshake: a request whose `params._meta` names `io.modelcontextprotocol/protocolVersion`.
  - The version must be `2026-07-28`; another gets `-32022` with `data: {"supported": [...], "requested": "..."}`.
    `io.modelcontextprotocol/clientCapabilities` must be an object, else `-32602`.
  - `server/discover` names every version spoken, both eras, the `tools` capability, `ttlMs` and `cacheScope:
    "private"`. Only it, `tools/list` and `tools/call` exist; `initialize` and `ping` are `-32601` in this era.
  - Every result carries `resultType: "complete"` and `_meta` naming `io.modelcontextprotocol/serverInfo`; `tools/list`
    also `ttlMs` and `cacheScope`. Tools, arguments, refusals and progress are as above.
- **Over a task's socket**, a request of `2026-07-28` (its body's `_meta`, `Mcp-Method`, or `MCP-Protocol-Version:
  2026-07-28`) must carry `MCP-Protocol-Version` and `Mcp-Method` as its body says, and for `tools/call` `Mcp-Name` as
  the tool's name. A header that is missing or says otherwise gets `400` with `-32020`, before the request is served; a
  request the session refuses as such (`-32700`, `-32600`, `-32602`, `-32021`, `-32022`) gets `400` too. None of these
  headers may appear twice. A request of the first era with an `MCP-Protocol-Version` neither era has gets `400`.

## Ready and exit

- `porelo-mcp` prints `ready` on standard output once it listens, and logs on standard error. `porelod` waits for that
  line, as it does for the gate.
- On `SIGTERM` or `SIGINT` it closes `--socket` and removes the path it bound itself, never a path it found.
- Exit codes:

  | Code | Meaning | `task status` shows |
  |---|---|---|
  | 0 | stopped on `SIGTERM` or `SIGINT` | stopped |
  | 1 | failed while running, after `ready` | failed, with the code |
  | 2 | refused at start: wrong arguments, a refused token, or a socket path or directory refused | failed, with the code |
  | 3 | the daemon socket was not reachable, or its interface not readable, before `ready` | could not reach its daemon socket, with the code |

## Outside a task: stdio

- `porelo-mcp stdio [--daemon-socket <path>]` serves a person's own MCP client on the host over standard input and
  output, on the person's own daemon socket, by default `$XDG_RUNTIME_DIR/porelo/porelod.sock`.
- It offers by default only the reading methods, named one by one, never guessed from a method's name. A writing method
  is offered only when the person names it in a list of their own local Porelo settings, never in a project file.
- A destructive method - such as stopping every task, clearing the vault, or panic - is never offered over stdio,
  whatever that list says.
- A method that stands for the person's own judgement, such as approving work or answering a clearance prompt, may be
  on that list. When one is offered, `porelo-mcp` says at start, on standard error, which of them the client may call.
- The sets come from the daemon at run time, by `org.fuin.porelo.Tasks1.McpMethods() -> (reading: []string, writing:
  []string, never: []string, judgement: []string)`: `reading`, `never` and `judgement` are porelo's own, named per
  method; `writing` is the person's own list minus `never`. Each name is a method's name without the interface, as a
  tool is named. `porelo-mcp` offers `reading` and `writing`, and refuses
  every name in `never` itself as well, so a fault on one side alone opens nothing. A daemon that does not answer
  `McpMethods` gets no tools offered.

### Streams

- A call over stdio that carries `_meta.progressToken` calls the method with Varlink's `more`. Each reply goes to the
  client at once as `notifications/progress`, with that token, a `progress` counting from 1, and the reply as JSON text
  in `message`. When the daemon ends the stream, its last reply is also the tool's result.
- Without a progress token a call is answered once, as the daemon answers a call without `more`. A method that answers
  once gives one reply to `more` too.
- A stream that sends only on a change, such as `Watch`, ends only when the client sends `notifications/cancelled` for
  its request. `porelo-mcp` then closes that daemon connection and sends no answer, as MCP has it. What the stream sent
  is in its progress notifications.
- At most 64 streams run at once in one stdio session, and only one per request id; one more, or a second one under an
  id still streaming, is refused at once with JSON-RPC error `-32600`. When standard output fails, every stream ends.
- Over a task's socket a call is answered once, progress token or not.

## Checking it

- `porelo-mcp --check [--daemon-socket <path>]` says whether it reaches the daemon, with the version from
  `org.varlink.service.GetInfo` and the methods it is offered.
- The daemon socket is by default `$XDG_RUNTIME_DIR/porelo/porelod.sock`.
- It exits 0 when it reached the daemon: `GetInfo` named a version and `GetInterfaceDescription` described
  `org.fuin.porelo.Tasks1`. It exits 3, naming what it tried, when it did not, or when `XDG_RUNTIME_DIR` is unset or
  empty and no socket was given. It exits 2 for a wrong argument.
