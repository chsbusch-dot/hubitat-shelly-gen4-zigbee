# CLAUDE.md

Hubitat Elevation driver for the Shelly Plug US Gen4 (S4PL-00116US) in Zigbee mode, shipped as a
Hubitat Package Manager package (`packageManifest.json`). It replaces the Shelly 1PM Gen 4 driver on
Chris's plugs: it scales readings with the plug's own multiplier/divisor attributes and reads every
attribute of a bundled report. The plugs run space heaters: treat anything that switches the relay as
safety relevant.

## Layout

| Path | What it is | Ships via HPM |
|---|---|---|
| `drivers/Shelly-Plug-US-Gen4-Zigbee.groovy` | The driver | yes |
| `packageManifest.json` | HPM manifest; version must equal the driver's `driverVersion()` | n/a |
| `README.md` | Public documentation (user-visible: no em/en dashes; no Codex humanizer pass for GitHub READMEs, Chris 2026-10-15) | n/a |
| `reference/shelly-1pm-gen4-v1.2.1-additionalAttrs.groovy` | Upstream joaomf/kkossev driver as installed on the hub (code 2795) with the 2026-10-11 additionalAttrs patch. Reference only | no |
| `tests/` | Package check, @Field check, Groovy execution tests, Groovy 2.4 compile, mutation check | n/a |

## Live hub

The hub's addresses, device and app ids, device labels and rule names are kept OUT of this public
repo, in `CLAUDE.local.md` (git-ignored; Claude Code loads it next to this file) and in the session
memory. Read it before touching the hub. Never commit it, and never put a person's name, a device
label, an IP or a MAC into tests, docs or commit messages: tests use the neutral label "Test Plug" and
a made-up MAC.

Hub access is the `hubitat-rules` MCP server; gateway tools (`hub_read_*`, `hub_manage_*`) list their
sub-tools when called with no arguments. Write tools need the `bestPracticeKey` of their guide section
(`hub_get_tool_guide(section=...)`), which rotates hourly: read it fresh, never hard-code it.

Uploading driver code: curl the file to the hub's `/hub/fileManager/upload` (form fields `uploadFile`,
`folder=/`), check the served bytes (`/local/<file>`) by sha256, then `hub_create_driver` /
`hub_update_driver` with `sourceFile` (plus `expectedVersion` on an update), and compare the hub's
source (`/driver/ajax/code?id=<driver>`) with the branch byte for byte.

## Safety rules (hard)

- **Configure must never switch the relay.** The Generic Zigbee Outlet's off/on "options testing" is
  exactly what this driver exists to avoid. The suite checks it (`configure: ... never switches the
  relay`, mutant M03).
- Device-specific rules (which plug may be switched for a test, which apps hold secrets) are in
  `CLAUDE.local.md`.
- Debug logging on a live plug only temporarily; it turns itself off after 30 minutes.
- **This repo is public** (since 2026-10-15; Chris's call). It started as one clean commit; the private
  development history, with personal data in it, lives in `chsbusch-dot/hubitat-shelly-gen4-zigbee-history`
  (local remote `history`). Never push a branch that descends from that history here: branch only from
  `origin/main`. Old local branches push to `history` by config.
- **Still always-ask:** the HPM repository listing and any community.hubitat.com post each need Chris's
  explicit yes. Never change repo visibility.
- README and forum text are public: no em or en dashes. GitHub READMEs skip the Codex humanizer pass
  (Chris, 2026-10-15); a forum post still gets it, with per-call approval from Chris.

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

- Branch off `main` as `shelly/<type>/<slug>` (for example `shelly/fix/energy-divisor`).
- One feature per branch; never commit onto another feature's open-PR branch.
- PR body carries `Closes WOR-<n>` (Linear team WOR).
- A release bumps `driverVersion()`, the driver's header changelog, and `version`, `dateReleased` and
  `releaseNotes` (starting `<version> - `) in `packageManifest.json`. `tests/check_package.py` enforces
  that they agree.
