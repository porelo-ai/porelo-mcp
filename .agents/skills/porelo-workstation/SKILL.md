---
name: porelo-workstation
description: How a Porelo agent works on a developer machine, outside a Porelo task: the channel, who pushes, VM tests, local settings in .AGENTS.md. Load first when not running inside a Porelo task.
---

> **BEGIN Shared Skill porelo-workstation** · sha256 `60934ae2937f71ac` · changed 2026-11-20T07:25Z

# porelo-workstation

Outside a Porelo task the following holds; inside one, `porelo-task` replaces it.

## Agents and the rules they follow

- **Where a channel exists, an agent stays reachable on it while any work is open**, and says there at
  once when it waits on the operator.

## Commits and pushes

- **Unless the operator says otherwise, the operator pushes, and agents commit and stop.** An
  instruction to push covers exactly what it names.

## Testing and machines

- **Work is tested on local VMs before it is handed over, on rented machines reachable from here only
  when the operator says so, and in the GitHub build on every push.** Which machines a setup has is
  defined in the optional `.AGENTS.md` or fed into every agent's context; machines are rented only
  through the shared tooling, and nothing else names a provider.
- **How agents exchange files for a test on a local VM is defined in `.AGENTS.md`.** Where it says
  nothing, a test uses only the agent's own artifacts.

## The channel and the operator

- **When several agents run in parallel, they may communicate through a shared channel.** How it
  works depends on the local setup and is defined in the optional `.AGENTS.md` or fed into every
  agent's context when it starts.
- **Only the coordinating agent writes to the operator; while the operator sits at the console, only
  there.** Other agents say what the operator should know in the channel, to the coordinator.
- **A post states only what a result already on screen shows**: an edit, the room read and the post
  that reports them are separate commands, and a shared hand-over file is read again right before it
  is rewritten.
- **Replies to the operator after reading messages mention only what requires this role to act or
  answer.** State the action or need without retelling other entries; unrelated reads produce no
  summary, waiting update, or watcher acknowledgment.

> **END Shared Skill porelo-workstation** · sha256 `60934ae2937f71ac`
