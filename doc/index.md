# Porelo MCP

`porelo-mcp` lets an AI agent work with Porelo. It speaks MCP, the Model Context Protocol, the common way in which
an AI coding agent such as Claude Code or Codex calls tools outside itself. Each thing Porelo lets an agent do, such
as listing tasks, starting one or reading its log, is one such tool.

For example, you ask the agent in your terminal "which of my tasks are still running, and what does the last one's
log say?" The agent calls the tools `List` and `Tail`, and Porelo answers. The agent never reads Porelo's files or
types `porelo` commands for it; it can do only what Porelo offers to agents, and every answer and every refusal is
Porelo's own.

## What it does

- **On your own computer**, it gives the agent you run yourself the tools of your Porelo, with your own rights:
  - `porelo-mcp tasks`: your tasks: list, start, stop and watch them, and read their logs.
  - `porelo-mcp planner`: the board of the Porelo planner, while the Porelo window runs it: list and read stories
    and issues, move them, change their fields and texts.
- **For a Porelo task**, the part of Porelo that runs on your computer, `porelod`, starts one `porelo-mcp` for the task
  itself, when the project file allows it. The agent inside the task then reaches Porelo's tools through it; a
  coordinating task, for example, starts, follows and stops the tasks it needs. What such a task may call is set in
  the project's signed project file and decided by `porelod`, never by `porelo-mcp`. Nothing has to be installed or
  set up for this beyond the package.

## How it is installed

- **From the same package repositories as Porelo**, as `porelo-mcp`, a `.deb` and an `.rpm` for x86-64. It puts the
  executable `/usr/bin/porelo-mcp` on the path and installs no service of its own. It needs glibc 2.34 or newer and
  zlib.

        sudo apt install porelo-mcp     # Debian, Ubuntu
        sudo dnf install porelo-mcp     # Fedora

- **See that it works**: `porelo-mcp --check` names the version of the running Porelo and every tool it offers to
  agents, and exits 0. Exit 3 says that it could not reach Porelo, and why.
- **Add it to your agent** as an MCP server that is started as a command. In Claude Code:

        claude mcp add porelo-tasks -- porelo-mcp tasks
        claude mcp add porelo-planner -- porelo-mcp planner

  Another MCP client takes the same: the command `porelo-mcp` with the argument `tasks` or `planner`. The agent
  lists the tools in its MCP view; a tool Porelo refuses comes back as an error with Porelo's reason.
- **The licenses of what it compiles in** stand in full in `THIRD-PARTY-NOTICES`, under
  `/usr/share/doc/porelo-mcp/` from the `.deb` and as a `%license` file from the `.rpm`.

## A short technical overview

- **The tools are generated, never written by hand**: from the interface Porelo's daemon describes at run time,
  every method marked for agents, and from the planner's OpenAPI description, every operation marked for agents. A
  method or operation added there and marked arrives as a tool without a change here.
- **It holds no rule of its own** and passes every call on unchanged. For a task it serves MCP over HTTP on a Unix
  socket only the person's own account can open, with a token, and never listens on a network port; on your own
  computer it speaks over standard input and output.
- **How `porelod` starts and binds it**, its tools, refusals, streams and exit codes:
  [How the daemon starts porelo-mcp](binding.md).
- **Why it is built as it is**: [Decisions](decisions.md). **Building it from source**:
  [the repository's README](../README.md#building).
