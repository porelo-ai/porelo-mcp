# MC02 — Tools Generated From The Daemon's Interface

**Status:** Review
**Size:** M - a Varlink interface parser, the schema mapping and the call path, with a stand-in daemon for the tests.

**What must be true.** An agent sees every method the daemon describes in `org.fuin.porelo.Tasks1` as an MCP tool with
an input and an output schema taken from the Varlink types, and calls it as it would the daemon: the answer and every
refusal are the daemon's, a refusal with its error name and parameters as they came.

## Plan

- `server`: a parser for the Varlink interface description language (`method`, `type`, `error`, structs, enums,
  `?` optionals, `[]` arrays, `[string]` maps, `object`, comments as descriptions), reading what
  `org.varlink.service.GetInterfaceDescription` returns; input refused with a message that names the line.
- A mapping from Varlink types to JSON Schema (2020-12): `bool`, `int` (integer), `float` (number), `string`, enums as
  `enum`, structs as objects, optionals left out of `required`, named types as `$defs`. A method's comment becomes the
  tool's description, and the tool's name is the method's name without its interface.
- The call path: `tools/call` with name `<Method>` sends `org.fuin.porelo.Tasks1.<Method>` with the arguments unchanged
  over `porelo-wire`'s `VarlinkClient`, unchecked against the schema, so the daemon refuses a bad parameter itself. The
  reply is the tool's structured content and text. A `VarlinkException` becomes a tool result with `isError: true` and
  structured content `{"error": <full name>, "parameters": {...}}`. A name that is no tool is JSON-RPC `-32602`; a
  daemon that cannot be reached is JSON-RPC `-32603` naming the socket. No call carries `more: true`.
- The MCP layer is its own, over `porelo-wire`'s `Json.parseDecimals`, as `doc/decisions.md` says: `initialize`,
  `tools/list`, `tools/call` and `ping`, the `id` and every number passed on exactly as they came. It speaks the
  versions with `initialize` up to `2025-11-25`, as `doc/decisions.md` says, and answers `server/discover` as an unknown
  method, so a client of both eras falls back.
- A stand-in daemon in the tests over `porelo-wire`'s `VarlinkServer`, described by a given interface. Where a test is
  about the contract, it serves the real `Tasks1` interface: from the published artifact that carries it, or until one
  does, the interface files taken from porelo's main at a named commit, that commit written beside them.
- Red first, against a method of the contract the agent cannot reach: a test that lists the tools of the real interface
  and finds every method it declares. Against a refusal the agent never sees: a test where the stand-in refuses a call
  and the agent's result carries that refusal's name and parameters unchanged.

## Acceptance

- Every method of the real `Tasks1` interface is a tool, counted against the methods the interface declares, and a
  method added to the stand-in's interface appears as a tool with no change to the code.
- A parameter the stand-in refuses as `org.varlink.service.InvalidParameter` reaches the agent as that refusal.
- Nothing in the code names a method of `Tasks1`.
