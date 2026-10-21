# CLAUDE.md

Hubitat Elevation driver for the Shelly Plug US Gen4 (S4PL-00116US) in Zigbee mode, shipped as a
Hubitat Package Manager package (`packageManifest.json`). It replaces the Shelly 1PM Gen 4 driver on
this plug: it scales readings with the plug's own multiplier/divisor attributes and reads every
attribute of a bundled report. The plugs it was written for run space heaters: treat anything that
switches the relay as safety relevant.

Maintainer-only notes (the live hub, deployment, publishing) live in a git-ignored `CLAUDE.local.md`,
which Claude Code loads next to this file. Nothing in this repo needs a hub or a plug.

## Layout

| Path | What it is | Ships via HPM |
|---|---|---|
| `drivers/Shelly-Plug-US-Gen4-Zigbee.groovy` | The driver | yes |
| `packageManifest.json` | HPM manifest; version must equal the driver's `driverVersion()` | n/a |
| `repository.json` | HPM listing (author, one package pointing at the manifest, category, tags); registered in HubitatCommunity/hubitat-packagerepositories | n/a |
| `README.md` | Public documentation (user-visible: no em or en dashes) | n/a |
| `reference/shelly-1pm-gen4-v1.2.1-additionalAttrs.groovy` | Upstream joaomf/kkossev driver v1.2.1 with the 2026-10-11 additionalAttrs patch (named in its header). Reference only | no |
| `tests/` | Package check, @Field check, Groovy execution tests, Groovy 2.4 compile, mutation check | n/a |

## Safety rules (hard)

- **Configure must never switch the relay.** The Generic Zigbee Outlet's off/on "options testing" is
  exactly what this driver exists to avoid. The suite checks it (`configure: ... never switches the
  relay`, mutant M03).
- Debug logging on a live plug only temporarily; it turns itself off after 30 minutes.
- Keep real installations out of the repo: no hub or device ids, device labels, IP or MAC addresses, or
  people's names in tests, docs or commit messages. The tests name their recorded plugs "plug A" and
  "plug B", use the label "Test Plug" and made-up network ids.
- README and every other user-visible text: no em or en dashes (`tests/check_package.py` checks).

## Tests

All must pass before a PR; CI (`.github/workflows/ci.yml`, on push and pull_request) runs the same.

```bash
python3 tests/check_package.py      # manifest vs driver (version, name, namespace, path); no dashes in user-visible files
tests/groovy/run.sh                 # @Field check, then the driver vs a stubbed hub (docker, groovy:4-alpine),
                                    # then tests/groovy/hub_compile.sh (Groovy 2.4, the hub's compiler;
                                    # HUB_COMPILE=skip leaves it to the CI job hub-compile)
```

Nothing touches a hub or a plug.

`tests/groovy/HubitatDriverStub.groovy` is the driver runtime: `FakeZigbee` returns hub-shaped command
strings and records every read, reporting setup and switch command; `parseDescriptionAsMap` returns
the descMap a test registered (`deliver(drv, frame(...))` in `driver_tests.groovy`). The metadata block
runs against a recorder, so capabilities, fingerprint and preference defaults are asserted too. Hub
classes the driver imports are stubbed under `tests/groovy/stubs/` (also on the Groovy 2.4 classpath).

`tests/groovy/hub_refuses/` holds positive controls: code groovy:4 accepts and the hub's Groovy 2.4
refuses. `hub_compile.sh` fails unless each is refused with its `// expect:` message. Add one whenever
the hub compile catches a new construct.

Optional, not in CI (about a minute): `tests/mutation/run.sh` breaks one guarded behaviour per mutant
(bundled reports, plug units, relay in configure, signed power, raw thresholds, migration, health,
digital/physical, fallback units, firmware re-read, identify, plug settings and RPC judging, LED settings, Wi-Fi setup, trips,
power factor). The unmutated `base` must pass and every mutant must fail. Anchors are exact strings; re-anchor a mutant when the code under it changes.

## Branches and PRs

- Branch off `origin/main` as `shelly/<type>/<slug>` (for example `shelly/fix/energy-divisor`).
- One feature per branch; never commit onto another feature's open-PR branch, and never stack a PR on
  another open PR's branch.
- Maintainer PRs carry `Closes WOR-<n>` (Linear team WOR) in the body; an outside contribution needs
  no ticket, a clear description is enough.
- A release bumps `driverVersion()`, the driver's header changelog, and `version`, `dateReleased` and
  `releaseNotes` (starting `<version> - `) in `packageManifest.json`. `tests/check_package.py` enforces
  that they agree.
