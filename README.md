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

This leaves `server/target/porelo-mcp` and, beside it, the DEB and the RPM. Their integration tests run the executable
unpacked from each package and fail when it asks for a CPU feature beyond x86-64. porelo-release's `check-linkage`
fails the build when the executable links a library, or a symbol version, the DEB does not declare, and
`check-package-version` when either package's version is not the project's.

## License

GPL-3.0-or-later, see [LICENSE](LICENSE).
