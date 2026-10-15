#!/usr/bin/env bash
#
# Build every mutant (tests/mutation/mutants.py) and run the driver suite against each, PAR at a time,
# in the same groovy:4-alpine image as tests/groovy/run.sh. Prints one line per mutant and exits
# non-zero if the unmutated "base" does not pass, or a mutant survives.
#
#   tests/mutation/run.sh            # all mutants
#   PAR=4 tests/mutation/run.sh M03  # some, with more parallelism
#
# Full output per mutant: tests/mutation/out/<name>/result.txt (out/ is gitignored). Not in CI.
# Nothing here touches a hub or a plug.

set -euo pipefail
cd "$(dirname "$0")/../.."
PAR="${PAR:-3}"

if ! command -v docker >/dev/null 2>&1; then
    echo "docker is required to run the mutation check" >&2
    exit 2
fi

names=($(python3 tests/mutation/mutants.py "$@"))

run_one() {
    d="$1"
    out="tests/mutation/out/$d/result.txt"
    if docker run --rm -v "$PWD:/w:ro" -w "/w/tests/mutation/out/$d" groovy:4-alpine sh -c '
            mkdir -p /tmp/h && groovyc -d /tmp/h $(find tests/groovy/stubs -name "*.groovy") tests/groovy/HubitatDriverStub.groovy || exit 3
            groovy -cp /tmp/h tests/groovy/driver_tests.groovy' > "$out" 2>&1; then rc=0; else rc=$?; fi
    summary="$(sed -n 's/.*result  : //p' "$out")"
    expect="$(sed -n 's/^expectation: //p' "tests/mutation/out/$d/MUTATION.txt")"
    killers="$(grep '^FAIL ' "$out" | sed 's/^FAIL  //' | head -2 | paste -sd '|' -)"
    printf '%-5s %-9s rc=%s %-22s %s\n' "$d" "[$expect]" "$rc" "${summary:-NO RESULT LINE}" "${killers:-killed-by: none}"
}
export -f run_one

results="$(printf '%s\n' "${names[@]}" | xargs -P "$PAR" -I{} bash -c 'run_one {}' | sort)"
echo "$results"

status=0
if ! echo "$results" | grep -E '^base ' | grep -q 'rc=0'; then
    echo "CONTROL FAILED: the unmutated base does not pass" >&2
    status=1
fi
if echo "$results" | grep -E '\[killed\] +rc=0' >/dev/null; then
    echo "A mutant survived:" >&2
    echo "$results" | grep -E '\[killed\] +rc=0' >&2
    status=1
fi
if echo "$results" | grep -E '\[killed\] +rc=3' >/dev/null; then
    echo "A mutant did not build (rc=3), so it proves nothing:" >&2
    echo "$results" | grep -E '\[killed\] +rc=3' >&2
    status=1
fi
exit $status
