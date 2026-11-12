# MC11 — Tests That Follow The Pyramid

```json
{
  "$schema": "issue-1.json",
  "id": "MC11",
  "title": "Tests That Follow The Pyramid",
  "state": "Estimated",
  "size": "M",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": [],
  "covers": "The tests follow the pyramid: the never-set guard and the token's refusals unit-tested, stdio, serve and the session over a fake daemon in process, deadlines on an injected clock; today 29 % unit, 64 % integration."
}
```

**Why this size.** One module, but five steps, three of them S: about a day of moving tests down behind two new seams.

**What must be true.** Most of `porelo-mcp`'s tests are unit tests over a fake daemon that run in milliseconds. Fewer
tests reach a real Unix socket or a started process, and the fewest run the native executable from its packages. Every
flow, the never-set guard among them, is proven at the lowest level that can show it.

## Where it stands

The levels: a unit test is one class or method, with no socket, no process and no outside system. An integration test
puts components together against a real Unix socket, the stand-in daemon or a started JVM. An end-to-end test runs the
native executable the packages carry. The shares aimed at are about 70-80 % unit, 15-20 % integration and 5-10 %
end-to-end. The suites were measured on a clean build of `9663faa` on the development machine:

| Level | Suites | Tests | Share | Test time |
|---|---|---|---|---|
| Unit | `NullMarkedTest`, `StreamsRegistryTest`, `VarlinkIdlTest`, `ModernEraTest`, `SocketPlaceTest`, and 11 methods of `CallsTest`, `ContractToolsTest`, `CheckCommandTest` and `HttpStreamsTest` | 28 | 29 % | 0.7 s |
| Integration, in process over a real Unix socket | `McpHttpServerTest`, the rest of `HttpStreamsTest`, `CallsTest`, `CheckCommandTest`, `ContractToolsTest`, `StartResultTest` | 43 | 44 % | 6.8 s |
| Integration, a started JVM | `StdioCommandTest`, `StreamsTest`, `ServeCommandTest` | 19 | 20 % | 16.2 s |
| End-to-end, the native executable from its DEB and RPM | `NativeServeIT`, `PackagesIT` (`-Pnative`) | 7 | 7 % | 0.8 s |

The unit phase takes 51 s on a clean build (`clean test`), with 33 s of test classes. The native build with its
integration tests takes 2 min 38 s (`-Pnative clean verify`), 1 min 8 s of it the native image. The pyramid stands on its head: integration is 64 %
of the tests and 97 % of the unit phase's test time, while unit tests are 29 %.

### Flows proven only through a socket or a started process

- **The never-set guard of `stdio`** (`McpMethods.offered`: only the reading set and the person's own list are offered,
  a method in `never` never is, whatever the other sets say) is proven only by `StdioCommandTest`. That test starts a
  JVM against the stand-in daemon. `McpMethods` has no test of its own.
- **The sets' own failures in `stdio`**: a set that is missing or holds something other than names, a daemon without
  `McpMethods`, and the judgement methods named at start. These are proven only by a started JVM.
- **The bearer token's refusals** (`BearerToken.read`: ended before its line did, shorter than 32 bytes, longer than
  1 023, a byte that is no printable ASCII) are proven only by `ServeCommandTest`, which starts one JVM per refused
  token. Every other test reads only a valid token.
- **Streams over stdio** (progress per reply, a second stream under an id, cancel, at most 64 at once, standard output
  failing) are proven only by `StreamsTest`, which starts a JVM per test, although `StdioLines.serve` already takes its
  streams as arguments.
- **A start's result as the tool's text** (`StartResultTest`) and **a daemon's refusal reaching the agent unchanged**
  (four `CallsTest` methods) go through the stand-in daemon on a socket. They prove `McpSession`'s mapping, and a
  `Daemon` lambda would do. Only `SocketDaemon`'s own errors need the socket.

### Tests that wait

- `McpHttpServerTest` and `HttpStreamsTest` run on a real 800 ms connection deadline. They sleep 10 to 1 500 ms to
  get past it: `theConnectionsDeadlineDoesNotCutAStream` takes 1.8 s, `aStalledPeerIsClosedAfterTheDeadline…` 0.8 s
  and `atMostSixtyFourConnections…` 0.8 s.
- `StreamsTest` sleeps 20 to 100 ms in its stand-in handlers to hold a stream open, and `ServeCommandTest` polls
  every 50 ms for `ready`.
- No test needs the network. The native build reaches only Maven repositories.

## Plan

1. **`McpMethods` gets its own unit tests**, over a `Daemon` lambda: each set, `never` overriding the reading set and
   the person's own list, a refusal, and a set that is malformed. XS, harm guarded: a destructive method offered to a
   person's client. Red first by letting `offered` skip the `never` check.
2. **`BearerToken.read` gets its own unit tests** for every refusal and the one accepted form. `ServeCommandTest` keeps
   a single process test, that a refused token exits 2 before any socket is bound. XS, harm guarded: a weak or
   malformed token accepted.
3. **`stdio` and `serve` take their daemon through a small seam** (a `Function<Path, Daemon>` that `Main.run` passes
   in, `SocketDaemon::new` in `main`). Then `StdioCommandTest`, `StreamsTest` and most of `ServeCommandTest` call
   `Main.run` or `StdioLines.serve` in process, over piped streams and a fake daemon. A started JVM stays for what only
   a process shows: nothing but JSON-RPC on standard output, SIGTERM stopping cleanly, no TCP socket. About 16 tests
   move down, 3 stay. S.
4. **`McpSession`'s mapping tests use a `Daemon` lambda** (`StartResultTest`, the `CallsTest` refusal and number tests,
   `ContractToolsTest`'s added method). `SocketDaemon` keeps narrow tests of its own against the stand-in: unreachable,
   closed, garbage, a refusal's name and parameters, `more` replies. About 12 tests move down. S.
5. **The connection deadline and the stream holds use an injected clock and latches**, not wall time. `McpHttpServer`
   takes its deadline scheduler from its caller, the tests advance it, and the handlers hold on a latch instead of
   sleeping. One test per deadline keeps the real 800 ms, to prove the wiring. S.

After 1 to 5, about 63 of 97 tests are unit tests (65 %). The integration tests are those of `SocketDaemon`,
`McpHttpServer`'s peer check and socket, and the few process tests, and the unit phase should take well under the 23 s
of test time it has now.

## Acceptance

- `McpMethods` and `BearerToken` each have unit tests that fail when their guard is removed, seen red once.
- The unit phase's test count is at least 60 % unit tests, and its test time is measured and given before and after.
- No test sleeps to wait for a deadline or a stream; the one real-deadline test per kind is named.
- The process tests left each name what only a process can show.

## Decided by the operator (2026-11-12)

- About 65 % unit tests is enough for this issue; `McpHttpServer`'s request handling keeps its socket, and the issue
  stays M.

## To be checked

- Whether `StdioLines.serve`'s handling of a failed standard output can be shown in process with a `PrintStream`
  whose `checkError` turns true, or needs the started JVM.
- The measured share and time after each step, read from the surefire reports.
- The plan's reading by another model before the build, as an issue of size M needs.
