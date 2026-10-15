#!/usr/bin/env bash
#
# Run the driver's execution tests against a stubbed Hubitat runtime, then compile the driver with
# Groovy 2.4, the hub's compiler line (tests/groovy/hub_compile.sh).
#
# The driver's own methods run with descMaps shaped like the hub's, so this observes behaviour (scaling,
# bundled reports, reporting thresholds, no relay command during configure) without a hub or a plug.
# Everything runs in containers, so there is no local Groovy to install.
#
# Exits non-zero if any check fails. HUB_COMPILE=skip leaves the Groovy 2.4 compile to the CI job
# hub-compile, for a machine that cannot pull groovy:2.4-jdk8.

set -euo pipefail
cd "$(dirname "$0")/../.."

# The groovy:4 image accepts things the hub's compiler rejects; check the known one first.
python3 tests/check_hubitat_fields.py

if ! command -v docker >/dev/null 2>&1; then
    echo "docker is required to run these tests" >&2
    exit 2
fi

status=0
docker run --rm -v "$PWD:/w:ro" -w /w groovy:4-alpine sh -c '
    set -e
    mkdir -p /tmp/hclasses
    groovyc -d /tmp/hclasses $(find tests/groovy/stubs -name "*.groovy") tests/groovy/HubitatDriverStub.groovy
    groovy -cp /tmp/hclasses tests/groovy/driver_tests.groovy
' || status=$?

# groovy:4 accepts code the hub refuses, so the driver also goes through Groovy 2.4.
if [ "${HUB_COMPILE:-}" = skip ]; then
    echo "hub compile: SKIPPED (HUB_COMPILE=skip); the CI job hub-compile runs it"
else
    hub=0
    tests/groovy/hub_compile.sh || hub=$?
    [ "$status" -ne 0 ] || status=$hub
fi
exit "$status"
