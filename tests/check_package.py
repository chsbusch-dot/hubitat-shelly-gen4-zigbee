#!/usr/bin/env python3
"""Check that packageManifest.json and the driver agree, and that user-visible text has no dashes.

HPM installs what the manifest names, so a version, name or path that drifts from the driver ships a
package that installs the wrong thing or reports the wrong version. And Chris's rule for anything a user
reads: no em or en dashes (README, the manifest's release notes, the driver's titles and log lines).

    python3 tests/check_package.py

No dependencies; nothing here touches a hub.
"""
from __future__ import annotations

import datetime
import json
import pathlib
import re
import sys
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[1]
RAW_PREFIX = "https://raw.githubusercontent.com/chsbusch-dot/hubitat-shelly-gen4-zigbee/main/"
REQUIRED = ["packageName", "author", "version", "minimumHEVersion", "dateReleased", "documentationLink",
            "communityLink", "licenseFile", "releaseNotes", "drivers"]
DASHES = re.compile("[–—]")
USER_VISIBLE = ["README.md", "packageManifest.json", "NOTICE", *sorted(p.relative_to(ROOT).as_posix() for p in ROOT.glob("drivers/*.groovy"))]


def problems() -> list[str]:
    out: list[str] = []
    manifest = json.loads((ROOT / "packageManifest.json").read_text())
    out += [f"manifest: missing {k}" for k in REQUIRED if k not in manifest]
    version = manifest.get("version", "")
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        out.append(f"manifest: version {version!r} is not x.y.z")
    if not str(manifest.get("releaseNotes", "")).startswith(f"{version} - "):
        out.append(f"manifest: releaseNotes must start with '{version} - '")
    try:
        datetime.date.fromisoformat(manifest.get("dateReleased", ""))
    except ValueError:
        out.append(f"manifest: dateReleased {manifest.get('dateReleased')!r} is not YYYY-MM-DD")
    if manifest.get("licenseFile") != RAW_PREFIX + "LICENSE" or not (ROOT / "LICENSE").is_file():
        out.append("manifest: licenseFile must point at LICENSE on main, and LICENSE must exist")

    ids = []
    for entry in manifest.get("drivers", []):
        ids.append(entry.get("id"))
        try:
            uuid.UUID(str(entry.get("id")))
        except ValueError:
            out.append(f"manifest: driver id {entry.get('id')!r} is not a UUID")
        location = str(entry.get("location", ""))
        if not location.startswith(RAW_PREFIX):
            out.append(f"manifest: {location} is not a raw URL on main of this repo")
            continue
        path = ROOT / location[len(RAW_PREFIX):]
        if not path.is_file():
            out.append(f"manifest: {location} names {path.relative_to(ROOT)}, which does not exist")
            continue
        source = path.read_text()
        definition = re.search(r"definition\((.*?)\)\s*\{", source, re.S)
        block = definition.group(1) if definition else ""
        for key in ("name", "namespace"):
            m = re.search(rf'\b{key}:\s*"([^"]*)"', block)
            if not m or m.group(1) != entry.get(key):
                out.append(f"manifest: driver {key} {entry.get(key)!r} differs from the driver's definition ({m.group(1) if m else None!r})")
        m = re.search(r'importUrl:\s*"([^"]*)"', block)
        if not m or m.group(1) != location:
            out.append(f"manifest: the driver's importUrl {m.group(1) if m else None!r} differs from its location")
        m = re.search(r'driverVersion\(\)\s*\{\s*"([^"]+)"', source)
        if not m or m.group(1) != version:
            out.append(f"manifest: version {version} differs from driverVersion() {m.group(1) if m else None!r} in {path.name}")
        if f"{version}  " not in source:
            out.append(f"{path.name}: the header changelog has no line for {version}")
    if len(set(ids)) != len(ids):
        out.append("manifest: duplicate driver ids")
    if not manifest.get("drivers"):
        out.append("manifest: no drivers")

    for name in USER_VISIBLE:
        path = ROOT / name
        if not path.is_file():
            out.append(f"{name}: missing")
            continue
        for n, line in enumerate(path.read_text().splitlines(), 1):
            if DASHES.search(line):
                out.append(f"{name}:{n}: em or en dash in user-visible text")
    return out


def main() -> int:
    found = problems()
    for p in found:
        print(f"FAIL  {p}")
    print(f"package check: {len(USER_VISIBLE)} user-visible files, {len(found)} problem(s)")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
