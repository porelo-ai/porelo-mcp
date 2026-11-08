# porelo-mcp

<img src="doc/images/crazy-chicken.svg" width="350" alt="Crazy chicken - just hatched">

> **Crazy chicken - just hatched.** Interfaces, formats and even the purpose may change any day. Use it only to try ideas, never to build on.

Porelo's daemon contract offered as MCP tools. Every method of the daemon's `org.fuin.porelo.Tasks1` interface becomes
a tool, generated from the interface the daemon describes, so a method added there arrives as a tool without hand work.
It holds no rule of its own: every answer and every refusal is the daemon's.

- [server](server/README.md) - the `porelo-mcp` program.
- [How the daemon starts and binds it for a task](doc/binding.md).

## Building

```
./mvnw -B -s settings.xml verify
```

JDK 25, the pinned GraalVM. See [AGENTS.md](AGENTS.md) for the conventions.

The native executable and its packages, with `rpm` and zlib's headers installed:

```
./mvnw -B -s settings.xml -Pnative verify
```

This leaves `server/target/porelo-mcp`, the DEB in `server/target` and the RPM under `server/target/rpm`. Their
integration tests run the executable unpacked from each package, and fail when it asks for a CPU feature beyond
x86-64 or links a library the packages do not declare.

## License

GPL-3.0-or-later, see [LICENSE](LICENSE).
