# Server

The `porelo-mcp` program: it reads the daemon's `org.fuin.porelo.Tasks1` interface and offers each of its methods as an
MCP tool, passing every call on unchanged and every answer and refusal back as it came. It holds no rule of its own,
and it never parses the `porelo` command's output or reads Porelo's files.

- It depends on `porelo-wire` alone, for Varlink and JSON; why no MCP SDK is in [doc/decisions.md](../doc/decisions.md).
- Its tests run against a stand-in daemon serving the real interface, kept under `src/test/resources/varlink` with the
  porelo commit it was taken from.
