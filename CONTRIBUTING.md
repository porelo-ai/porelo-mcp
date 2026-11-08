# Contributing

Changes to porelo-mcp follow the project's guide: how an issue is written, how a change is proposed and reviewed, and
what a commit carries. It is one page for every Porelo repository:
[Contributing to Porelo](https://porelo-ai.github.io/porelo-project/contributing/).

## In this repository

- **Building and testing:** JDK 25, the pinned GraalVM. `./mvnw -B -s settings.xml verify` runs the tests.
- **The contract** it serves is porelo's `org.fuin.porelo.Tasks1` interface, read from the running daemon; how the
  daemon starts it for a task is [doc/binding.md](doc/binding.md), changed only together with porelo.
- **Requirements** are in [issues/README.md](issues/README.md).
