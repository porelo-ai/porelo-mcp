# MC09 — A Start's Result As The Tool's Text

**Status:** Idea

**What must be true.** An agent that calls `Start` with a prompt and no progress token reads what the run answered in
the tool's text, without knowing to call `Tail` or `Logs` after it.

## Why

The operator's test of 2026-11-08 started a task with a prompt over stdio from a headless Claude Code. `Start` answered
`exitCode` 0 and an empty `output`, so the agent never saw the run's answer. porelo's B515 gives `Start`'s last reply a
`result`, at most 16 KiB, with `resultCut`, or the run's last lines where a plugin cannot read the result, marked as
such. It is blocked by B515 in porelo.

## To be checked

- The tool's text shows `result`, with a line saying it was cut or that it holds the run's last lines; the structured
  content stays the reply as it came.
- Whether any other method's reply gains such a field, which the same rule would show.
