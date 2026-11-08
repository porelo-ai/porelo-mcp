# MC04 — Stdio For A Person's Own Client

**Status:** Backlog; blocked by [MC02](README.md#mc02).
**Size:** S - one transport over the tools of [MC02](README.md#mc02), no new contract.

**What must be true.** A person adds `porelo-mcp stdio` to their own MCP client on the host and gets the reading tools
of their own daemon, with their own rights; a writing tool only when they named it in their own local Porelo settings,
and a destructive one never. When a method of their own judgement is among them, they are told so at start.

## Plan

- `stdio [--daemon-socket <path>]`, the daemon socket by default `$XDG_RUNTIME_DIR/porelo/porelod.sock`; MCP over
  standard input and output, logs on standard error only, so nothing but protocol reaches standard output.
- The methods offered come from the daemon's `McpMethods() -> (reading: []string, writing: []string, never: []string,
  judgement: []string)` on the person's socket: `reading`, `never` and `judgement` are porelo's own sets, named per
  method, and `writing` is the person's own list minus `never`. stdio offers `reading` and `writing` and refuses every
  name in `never` itself as well, so a fault on one side alone opens nothing. A daemon without `McpMethods` gets no
  tools, and stdio says why.
- Every offered method that is in `judgement` is named at start on standard error, one line saying the client may call
  it on the person's behalf.
- Red first, against a destructive method reaching an agent: a stand-in whose `writing` names a method that is also in
  `never` offers no such tool, and a call of it by name is refused as no tool without reaching the stand-in. Against a
  corrupted protocol stream: a test that drives the command over pipes and fails when anything but a JSON-RPC message
  appears on standard output.

## Acceptance

- An MCP client over pipes lists the tools and calls one against a stand-in daemon.
- Nothing but JSON-RPC messages appears on standard output, a refused call included.
- With no list, exactly the reading set is offered; a writing method named in the list is added; a destructive method
  named there stays out, and the stand-in daemon sees no call of it, even when the stand-in's `writing` names it.
- A judgement method in the list is offered and named on standard error at start; with none offered, no such line.
