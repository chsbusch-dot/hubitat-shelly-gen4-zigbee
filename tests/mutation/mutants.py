#!/usr/bin/env python3
"""Mutants of the driver: each one breaks a behaviour the suite claims to guard.

Every mutant replaces one exact anchor (it must occur once in the driver) and must make
tests/groovy/driver_tests.groovy fail. "base" is the unmutated driver and must pass. A mutant that
survives means a test claims more than it checks.

    python3 tests/mutation/mutants.py [NAME ...]   # writes tests/mutation/out/<name>/ (gitignored)
    tests/mutation/run.sh [NAME ...]               # builds them and runs the suite against each

When the code under an anchor changes, re-anchor the mutant. Nothing here touches a hub or a plug.
"""
from __future__ import annotations

import pathlib
import shutil
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
DRIVER = "drivers/Shelly-Plug-US-Gen4-Zigbee.groovy"
OUT = ROOT / "tests/mutation/out"

# name: (what it breaks, anchor, replacement)
MUTANTS = {
    "M01": ("bundled reports: only the first attribute is read (the frozen-power bug)",
            "descMap.additionalAttrs?.each { Map a ->", "[].each { Map a ->"),
    "M02": ("the plug's units are ignored, the defaults always apply",
            'if (m != null && d != null && m > 0 && d > 0) return [mult: m, div: d, source: "plug"]',
            'if (false) return [mult: m, div: d, source: "plug"]'),
    "M03": ("configure cycles the relay, as the Generic Zigbee Outlet does",
            "    cmds += optionalReads()\n    // The scale answers",
            "    cmds += optionalReads()\n    cmds += zigbee.off()\n    cmds += zigbee.on()\n    // The scale answers"),
    "M04": ("power decoded unsigned",
            'boolean signed = enc != null ? (enc >= 0x28 && enc <= 0x2F) : type.startsWith("int")',
            "boolean signed = false"),
    "M05": ("reporting thresholds sent in display units, not raw units",
            "BigDecimal raw = change * (sc.div as Long) / (sc.mult as Long)",
            "BigDecimal raw = change"),
    "M06": ("the 1PM driver's calibration settings are kept",
            "        device.removeSetting(name)\n", "\n"),
    "M07": ("the health check never marks the plug offline",
            "    if (last != null && now() - last <= timeoutMs) return\n",
            "    return\n"),
    "M08": ("every switch change is reported as physical",
            '        type = "digital"\n', "\n"),
    "M09": ("the 020000FF fallback divides power by 100 like the 1PM driver",
            "    power    : [mult: 1, div: 1],", "    power    : [mult: 1, div: 100],"),
    "M10": ("a firmware change does not re-read the units",
            "    if (previous != null && previous != firmware) {", "    if (false) {"),
    "M11": ("Save Preferences before Configure sets up reporting on unknown units",
            "    if (!scaleComplete() && !scaleAskedRecently()) {", "    if (false) {"),
    "M13": ("the health ping's answer is ignored: an idle plug goes offline again (1.0.0)",
            "    if (asked == null || (last != null && last >= asked)) return\n", "    if (asked == null) return\n"),
    "M14": ("offline without asking first",
            "    state.healthPingMs = now()\n    sendZigbee(ping())\n    runIn(PING_GRACE_SECONDS, \"healthConfirm\")\n",
            "    state.healthPingMs = now()\n    sendZigbee(ping())\n    healthConfirm()\n"),
    "M15": ("identify sends its seconds big-endian",
            'String.format("%02X%02X", s & 0xFF, (s >> 8) & 0xFF)', 'String.format("%02X%02X", (s >> 8) & 0xFF, s & 0xFF)'),
    "M19": ("the power-on state goes over RPC although the plug has 0006/4003",
            "    if (todo.initial_state != null && state.startUpOnOff == true) {", "    if (false) {"),
    "M20": ("a quick self-off after on is not counted as a trip",
            "    boolean quick = onAt != null && now() - onAt <= TRIP_WINDOW_MS\n", "    boolean quick = false\n"),
    "M21": ("a hub-sent off is checked as a trip",
            '    if (changed && value == "off" && type == "physical") checkTrip()', '    if (changed && value == "off") checkTrip()'),
    "M22": ("power factor read unsigned",
            'Long raw = decodeRaw(hex, encoding ?: "28", "int8")', 'Long raw = decodeRaw(hex, "20", "uint8")'),
    "M16": ("settings the plug already accepted are sent again",
            "return wanted.findAll { k, v -> done[k] != v && !skip.contains(k) }", "return wanted.findAll { k, v -> !skip.contains(k) }"),
    "M17": ("an RPC reply to another request id counts as ours",
            "    if ((id.group(1) as Integer) != (pending.id as Integer)) {", "    if (false) {"),
    "M18": ("an RPC error reply is not recognised",
            "    if (refused) {", "    if (false) {"),
    "M23": ("the RPC request goes out as one oversized frame",
            "@Field static final Integer RPC_CHUNK_BYTES = 40", "@Field static final Integer RPC_CHUNK_BYTES = 200"),
    "M24": ("a power limit below the current draw is sent anyway",
            "        if (drawing >= (todo.power_limit as BigDecimal) * TRIP_LOAD_SHARE) {", "        if (false) {"),
    "M25": ("a refused 0006/4003 write keeps using 0006/4003",
            "    state.startUpOnOff = false\n    settingsOutcome(", "    settingsOutcome("),
    "M26": ("Configure keeps what it knew about the plug's settings",
            '    ["plugSettings", "settingsSkip", "startUpOnOff", "powerFactorSupported", "lightLevelSupported", "wifiSupported"].each { state.remove(it) }\n', ""),
    "M27": ("setBluetooth on sends enable false",
            '    Map config = v == "on" ? [enable: true, rpc: [enable: true]] : [enable: false]', '    Map config = [enable: false]'),
    "M28": ("an RPC write that gets no answer is not asked again",
            "@Field static final Integer RPC_ATTEMPTS = 2", "@Field static final Integer RPC_ATTEMPTS = 1"),
    "M29": ("an RPC error opening cut short at \"e is left unjudged",
            '''(head =~ /"src":"[^"]*","e/).find() || tail.contains('"code":')''', '''tail.contains('"code":')'''),
    "M30": ("the RPC request chunks go out together, not one per acknowledgement",
            '    sendZigbee([rpcFrame("02", "0100" + "23" + le32(bytes.length))])',
            '    sendZigbee([rpcFrame("02", "0100" + "23" + le32(bytes.length))] + chunks.collect { rpcFrame("02", it) })'),
    "M31": ("a duplicate write acknowledgement sends something again",
            "    if (sent > chunks.size()) return", "    if (false) return"),
    "M32": ("a failing setting is added to the skip list again on every failure",
            "        if (skipOnFail && !skip.contains(key)) state.settingsSkip = skip + [key]", "        if (skipOnFail) state.settingsSkip = skip + [key]"),
    "M33": ("setWifi logs the password",
            '    logInfo "sending Wi-Fi network ${network} to the plug"', '    logInfo "sending Wi-Fi network ${network} (${password}) to the plug"'),
    "M34": ("the Wi-Fi writes go out together, not one per acknowledgement",
            "    sendZigbee([frames[0]])", "    sendZigbee(frames)"),
    "M35": ("a refresh during setWifi writes Action 0 and wipes the staged network",
            '    if (state.wifiPending == null) cmds += [wifiWrite(attrLe(0x0002) + "20" + "00"), "delay 500"]',
            '    cmds += [wifiWrite(attrLe(0x0002) + "20" + "00"), "delay 500"]'),
    "M36": ("the Wi-Fi frames, password included, are kept in state",
            "    state.wifiPending = [network: network]", "    state.wifiPending = [network: network, frames: frames]"),
    "M37": ("Status and IP keep being read after the plug has an address",
            '            if (known) {\n                state.remove("wifiReadsLeft")', '            if (false) {\n                state.remove("wifiReadsLeft")'),
    "M38": ("a Wi-Fi address of 0.0.0.0 counts as an address",
            '            boolean known = text && text != "0.0.0.0"', '            boolean known = text as boolean'),
    "M39": ("an accepted RPC reply cut on the quote before result (two-digit id) is left unjudged",
            '''(head =~ /"src":"[^"]*","r/).find() || tail.contains('restart_required')''', '''(head =~ /"src":"[^"]*","r/).find()'''),
    "M40": ("an RPC error cut on the quote before error (two-digit id) is left unjudged",
            ''' || tail.contains('"code":')''', ''),
    "M12": ("the hub's firmwareMT is left out of the firmware identity",
            '[build, device.getDataValue("firmwareMT")].findAll', "[build].findAll"),
}


def build(name: str) -> None:
    dest = OUT / name
    if dest.exists():
        shutil.rmtree(dest)
    for part in ("drivers", "tests/groovy"):
        shutil.copytree(ROOT / part, dest / part)
    note = "expectation: pass\n" if name == "base" else "expectation: killed\n"
    if name != "base":
        what, anchor, replacement = MUTANTS[name]
        path = dest / DRIVER
        text = path.read_text()
        count = text.count(anchor)
        if count != 1:
            raise SystemExit(f"{name}: anchor found {count} times, re-anchor it: {anchor!r}")
        path.write_text(text.replace(anchor, replacement))
        note += f"breaks: {what}\n"
    (dest / "MUTATION.txt").write_text(note)


def main(argv: list[str]) -> int:
    names = argv or ["base", *MUTANTS]
    if OUT.exists() and not argv:
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True, exist_ok=True)
    for name in names:
        if name != "base" and name not in MUTANTS:
            raise SystemExit(f"no mutant named {name}")
        build(name)
        print(name)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
