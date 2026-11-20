---
name: porelo-machines
description: Porelo rules for local VMs and rented machines: restarts, names, clean-up, shared machines, package removal, leases, provider credentials, shell pitfalls on remote machines. Use before any run on a VM or rented machine.
---

> **BEGIN Shared Skill porelo-machines** · sha256 `c9131191da90fb10` · changed 2026-11-20T07:25Z

# porelo-machines

## Testing and machines

- **A small change is tried on a local VM only as far as the change needs, not by a full run of
  the whole flow.** The complete run is the workflow's on GitHub afterwards.
- **A local VM is restarted only when agreed in the channel or asked of the operator.** A rented
  machine reachable from here is restarted only by the operator.
- **A rented machine's name says which agent and which run made it.** Every run deletes exactly what
  it created, after a failure too, and never sweeps by age or touches a machine it cannot attribute.
- **The scheduled sweep of the CI's machines deletes one only past the `porelo-expires` time it was
  made with, or one that carries none**; it is the one clean-up that is not a run's own.
- **A script that changes a machine refuses to start over leftovers, removes only what it created -
  on interrupt too - and exits non-zero naming anything it left.**
- **Before a run on a shared machine, check what it does to that machine and say so.**
- **A machine script is rehearsed with the same preparation as its target before an expensive run.**
  Compare its options with the build workflow and verify each prerequisite’s effect before starting
  dependent work.
- **Budget each lease using its provider’s billing units at the current price.** Include every new
  lease required for a rerun.
- **Porelo's own host is rented for a few hours at a time**, so local work never needs a running host;
  a product decision still allows hosts that stay.
- **A package removal on a shared machine is simulated first** (`dnf remove --assumeno`, `apt-get -s
  remove`), since dependents go with it; if it takes anything this run did not install, stop and ask in
  the channel.

## Security and tools

- **`pkill -f` and `pgrep -f` match their own command line**, and can kill the shell running them;
  use a bracket pattern like `[p]odman`, and stop a stray agent run the same way (`pkill -f '[a]gy -p ...'`).
- **`ssh -n host 'bash -s' <<EOF` runs nothing and exits 0**; a script sent on standard input goes
  without `-n`.
- **Organization secrets and variables have separate repository access lists.** Before a repository
  first publishes or runs rented acceptance legs, have the operator verify access to every required
  value, including the publication credential.
- **Provider credentials for development and CI are deliberately separate.** Never synchronize or
  copy them between environments; check an authentication or quota failure against the credential
  used in the failing environment.

## Secrets in a terminal

- **A secret is never fed through a terminal recorder such as `script`.** Use a supported
  secret-input method outside the recorder, and redact any potentially exposed output on its source
  machine before reading it into a transcript.

## Terminals and containers

- **A real terminal for a test is a detached `tmux` session**; killing the session does not end a task:
  `porelo task stop` and `remove` do.
- **A killed `podman build` leaves a working container holding its base image**, which only `podman ps -a --external`
  shows; a run that builds images checks for it and removes it with its own container, image and pulled base.

> **END Shared Skill porelo-machines** · sha256 `c9131191da90fb10`
