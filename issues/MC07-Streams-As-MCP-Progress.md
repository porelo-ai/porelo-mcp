# MC07 — Streams As MCP Progress

**Status:** Review
**Size:** M - streams over stdio first, a thread per streamed call, and cancelling; HTTP once the per-task side passes them.

**What must be true.** A method of the daemon that answers only as a stream, such as watching the tasks or following a
log, can be called as a tool, its replies reaching the agent as MCP progress until the stream ends or the agent cancels.

## Plan

- `McpSession` runs a `tools/call` with `_meta.progressToken` as a stream when its transport can carry one, on a thread
  of `Streams` (at most 64 at once), and `notifications/cancelled` cancels it by its request id.
- `Daemon.stream` calls with `more` through porelo-wire's `callMore`, handing on every reply as it comes; a cancel
  closes the connection, which ends a read that waits for the next change.
- stdio writes every message whole through one sink, so progress, answers and the reader's own answers never mix.
- HTTP: a call with a progress token from a client whose `Accept` names `text/event-stream` gets one, each message
  one event; the request's deadline ends when the stream starts; the client's closing cancels it; a cancel on another
  connection reaches it through the server's live streams.
- Red first: progress per reply with the last as result; a cancel ends the daemon's call and gets no answer; the 65th
  stream refused.

## Acceptance

- Over stdio: two continued replies and a last one give three progress notifications, the last also the result; a call
  without a token gets one answer; a cancelled `Watch` ends the daemon's call, gets no answer, and the next request is
  answered; the 65th stream at once is refused. Each seen red once.
- Over HTTP: each reply an event as it comes, the result the last, then the connection's end; a client without
  `text/event-stream` gets one answer; a closed connection and a cancel on another connection each end the daemon's
  call, without an answer; a stream outlives the connection's deadline; a stream cancelled before its first reply has
  answered `200`; a second stream under an id streaming on another connection is refused; a cancel lets go of a
  stream whose client stopped reading; a cancel just before its call ends it; `1` and `1.0` are one id, `"1"` another;
  `Accept` read by its media ranges. Each seen red once.
