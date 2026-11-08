# MC07 — Streams As MCP Progress

**Status:** Accepted - by the operator, 2026-11-08; building waits for the coordinator's word.

**What must be true.** A method of the daemon that answers only as a stream, such as watching the tasks or following a
log, can be called as a tool, its replies reaching the agent as MCP progress until the stream ends or the agent cancels.

## To be checked

- Which methods stream. The interface marks none, and its comments name `Watch`, `WatchProjects`, `Start`, `Prepare`,
  `Tail` and `Prompts`, the last refusing a call without `more`. Whether a call asks for a stream only when the client
  sends a progress token, and what a stream that never ends (`Watch`) gives as the tool's result.
- On HTTP, progress needs a `text/event-stream` answer, which the socket's reader does not write yet; on stdio it is
  notifications between the request and its answer.
- Cancelling: the client's `notifications/cancelled` ends the call, through `porelo-wire`'s `callMore` returning false
  or the connection closed. `porelo-wire` needs no change for it.
- Whether the per-task daemon socket passes a stream through, which only the daemon's side can say.
