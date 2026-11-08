# MC09 — A Start's Result As The Tool's Text

**Status:** Review
**Size:** S - the tool's text of a reply that carries `result`; no new method, no new rule.
**Blocked by:** porelo's B515, on porelo's main as `0cc6e7a55`; its names are as planned.

**What must be true.** An agent that calls `Start` with a prompt reads what the run answered in the tool's text,
without knowing to call `Tail` or `Logs` after it.

## Why

The operator's test of 2026-11-08 started a task with a prompt over stdio from a headless Claude Code. `Start` answered
`exitCode` 0 and an empty `output`, so the agent never saw the run's answer.

## The contract, from B515's plan

`Start`'s final reply gains `result: ?string`, at most 16 KiB; `resultKind: ?string`, `ANSWER` or `LAST_LINES`; and
`resultCut: ?bool`. A streamed run line comes as `runLine: ?string`, apart from porelo's own `line`.

## Plan

- **A reply whose `result` is a string** gets two texts:
  - first the result: after a line saying it is no answer for `LAST_LINES`, or naming the kind for a kind this build
    does not know, and before a line saying it was cut when `resultCut` is true;
  - then the reply as JSON, without `result`, `resultKind` and `resultCut`.
- **Any other reply** stays one text of the whole reply.
- **The structured content** stays the reply as it came.
- **Streamed:** the last reply is the answer, so a streamed `Start` ends the same way. Each progress, a `runLine`
  among them, stays the reply as JSON.
- **Not by method name:** any method whose reply carries `result` is shown so, since the daemon's contract says what
  the field means, and porelo-mcp keeps no list of methods.

## Acceptance

- `StartResultTest`:
  - an answer is the first text, and the rest of the reply the second;
  - last lines are said to be no answer;
  - a cut result says it was cut;
  - an unknown kind is named;
  - a reply without a result is one text;
  - a streamed start ends with the answer as its text.

  Five were seen red before the build. The sixth, a reply without a result, guards what stays.
