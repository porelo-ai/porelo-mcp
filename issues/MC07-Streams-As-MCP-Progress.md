# MC07 — Streams As MCP Progress

**Status:** Implementing
**Size:** M - streams over stdio first, a thread per streamed call, and cancelling; HTTP once the per-task side passes them.

**What must be true.** A method of the daemon that answers only as a stream, such as watching the tasks or following a
log, can be called as a tool, its replies reaching the agent as MCP progress until the stream ends or the agent cancels.

## Plan

- `McpSession` runs a `tools/call` with `_meta.progressToken` as a stream when its transport can carry one, on a thread
  of `Streams` (at most 64 at once), and `notifications/cancelled` cancels it by its request id.
- `Daemon.stream` calls with `more` through porelo-wire's `callMore`, handing on every reply as it comes; a cancel
  closes the connection, which ends a read that waits for the next change.
- stdio writes every message whole through one sink, so progress, answers and the reader's own answers never mix.
- HTTP follows once the per-task side passes `more`: a `text/event-stream` answer.
- Red first: progress per reply with the last as result; a cancel ends the daemon's call and gets no answer; the 65th
  stream refused.

## Acceptance

- Over stdio: two continued replies and a last one give three progress notifications, the last also the result; a call
  without a token gets one answer; a cancelled `Watch` ends the daemon's call, gets no answer, and the next request is
  answered; the 65th stream at once is refused. Each seen red once.
- Over HTTP: the same, once the per-task side streams.
