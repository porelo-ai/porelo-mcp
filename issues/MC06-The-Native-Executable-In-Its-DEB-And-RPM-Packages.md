# MC06 — The Native Executable In Its DEB And RPM Packages

**Status:** Review
**Size:** M - the native build, two packages and their publication in the build workflow.

**What must be true.** `porelo-mcp` installs from Porelo's package channels as a native executable, in a DEB and an RPM
package of its own, and starts on any x86-64 machine.

## Plan

- A `native` profile in `server` building the executable with the workflow-pinned GraalVM and `-march=x86-64`, checked
  for that instruction set and for the libraries it links, which both packages declare.
- The DEB with `jdeb` and the RPM, the executable at `/usr/bin/porelo-mcp`, the version from the build; a snapshot's
  package `<version>~snapshot.<run>`.
- The build workflow builds both, runs each unpacked executable's `--check` with an empty environment, and publishes
  them to the snapshots channel on a push to main and to the releases channel on a tag.
- Red first, against shipping an executable that cannot run where it is installed: the unpacked executable from each
  package, run with an empty environment, exits 3 naming `XDG_RUNTIME_DIR`; a package missing a library fails there.

## Acceptance

- Both packages install on the Ubuntu and the Fedora test machine. There `porelo-mcp --check` exits 0 with the version
  and methods against a stand-in daemon, and 3 naming the socket with no daemon.
- The build fails when the executable uses an instruction outside `-march=x86-64` or links a library neither package
  declares, each seen to fail once.
- The build workflow's whole run is green locally on the VM before the hand-over, and the published index shows the
  exact version.
