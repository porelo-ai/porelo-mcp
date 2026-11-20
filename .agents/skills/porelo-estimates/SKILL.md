---
name: porelo-estimates
description: Porelo rules for estimates, timing and the VELOCITY record. Use before analysis or implementation and in every push hand-over.
---

> **BEGIN Shared Skill porelo-estimates** · sha256 `0ab012d2caff7bb8` · changed 2026-11-20T07:25Z

# porelo-estimates

## Estimates and handovers

- **Before analysis or implementation, report `estimate=<min> velocity median=<min>`, and the
  estimate is the measured median of the issue's size.** A reason to expect more or less is named
  beside it and never changes the number; label a guess when no measurement exists, and report the
  cause at once when elapsed time reaches one and a half times the estimate.
- **Timing starts with the first plan reading or brief and ends when the work is ready for handover,
  including waits.** Keep the original estimate and starting size, record measured VM and sub-agent
  times, count returns after first readiness as rework, and explain long waits or size growth. Count
  shared time and token usage only once.
- **Every push handover includes one timing record**:
  `VELOCITY <repo> <issue> size=<XS|S|M|L> start=<UTC> ready=<UTC> est=<min> wall=<min> vm=<min> subagents=<model-effort:sec,...> rework=<n>`.
  Issues built together in one repository share one record, joining identifiers and starting sizes
  with `+`: estimates summed, the earliest start and the stack's readiness, VM time and tokens counted
  once.

> **END Shared Skill porelo-estimates** · sha256 `0ab012d2caff7bb8`
