---
name: porelo-task
description: How a Porelo agent works inside a Porelo task (a container with /workspace, user agent): pushes go to the gate, issue states on the task branch, builds through the build reader, the room only for decisions. Load first when running inside a Porelo task.
---

> **BEGIN Shared Skill porelo-task** · sha256 `09eff9a8892709fe` · changed 2026-11-20T07:25Z

# porelo-task

Inside a task these replace the rules of `porelo-workstation`:

- **The agent pushes its own branch to the gate**; the task's push setting names the ref. Nobody else pushes for it.
- **Implementing and Review are set in commits on the task branch**; they count once the branch is merged.
- **A pushed commit is published**: in class `online` the gate forwards it at once, so it is corrected by a new commit.
- **The build is followed through Porelo's build reader**, not through the forge's API or its logs.
- **The room carries only what needs the person**: a decision or an action from them, being stuck, or work done;
  progress and check lists stay in the terminal.
- **There is no VM**: tests run in the container; a test that could not call a real tool is named.
- **A check that needs a host the project does not declare** (e.g. `check-citations` reaching GitHub) is named as not
  run, never worked around.
- **There is no `.AGENTS.md`**: what it would say comes from the task's first instruction.
- **The agent is the author of its commits, and the gate's signature binds them to the person**; each carries
  `Assisted-by: <tool>, <model>` like every agent's commit, and Porelo's own trailers stand only on the commits Porelo
  makes itself.

> **END Shared Skill porelo-task** · sha256 `09eff9a8892709fe`
