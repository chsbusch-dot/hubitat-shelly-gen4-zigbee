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
            "    if (todo.initial_state != null && state.startUpOnOff == true && state.startUpWritable != false) {", "    if (false) {"),
    "M20": ("a quick self-off after on is not counted as a trip",
            '    boolean quick = onAt != null && now() - onAt <= TRIP_WINDOW_MS && (state.onType == "digital" || peak > 0)\n', "    boolean quick = false\n"),
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
            "        if (blocker) {", "        if (false) {"),
    "M25": ("a refused 0006/4003 write keeps using 0006/4003",
            "    state.startUpWritable = false                  // it still reads fine; handlePowerOnState must not undo this\n    settingsOutcome(", "    settingsOutcome("),
    "M26": ("Configure keeps what it knew about the plug's settings",
            '    ["plugSettings", "settingsSkip", "startUpOnOff", "startUpWritable", "powerFactorSupported", "lightLevelSupported", "wifiSupported"].each { state.remove(it) }\n', ""),
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
            '''    logInfo "sending Wi-Fi network ${network} to the plug${waitSeconds ? ' in ' + waitSeconds + ' s' : ''}"''', '''    logInfo "sending Wi-Fi network ${network} (${password}) to the plug${waitSeconds ? ' in ' + waitSeconds + ' s' : ''}"'''),
    "M34": ("the Wi-Fi writes go out together, not one per acknowledgement",
            "    sendZigbee([rest[0]])", "    sendZigbee(rest)"),
    "M35": ("a refresh during setWifi writes Action 0 and wipes the staged network",
            "    if (state.wifiPending == null) {\n        cmds += [wifiWrite(attrLe(0x0002) + \"20\" + \"00\"), \"delay 500\"]",
            "    if (true) {\n        cmds += [wifiWrite(attrLe(0x0002) + \"20\" + \"00\"), \"delay 500\"]"),
    "M36": ("the Wi-Fi frames, password included, are kept in state",
            "    state.wifiPending = [network: network, at: now(), sent: 0]", "    state.wifiPending = [network: network, at: now(), sent: 0, frames: frames]"),
    "M37": ("Status and IP keep being read after the plug has an address",
            '            if (known) {\n                state.remove("wifiReadsLeft")', '            if (false) {\n                state.remove("wifiReadsLeft")'),
    "M38": ("a Wi-Fi address of 0.0.0.0 counts as an address",
            '            boolean known = text && text != "0.0.0.0"', '            boolean known = text as boolean'),
    "M39": ("an accepted RPC reply cut on the quote before result (two-digit id) is left unjudged",
            "(head =~ /\"src\":\"[^\"]*\",\"r/).find() || (tail.contains('restart_required') && successLengthMatches(head, pending))", "(head =~ /\"src\":\"[^\"]*\",\"r/).find()"),
    "M40": ("an RPC error cut on the quote before error (two-digit id) is left unjudged",
            ''' || tail.contains('"code":')''', ''),
    "M41": ("a quick self-off after a button press at no load counts as a trip",
            ' && (state.onType == "digital" || peak > 0)', ''),
    "M42": ("a pending write that never finished blocks later writes forever",
            "        if (at != null && now() - at <= seconds * 1000L) return", "        if (true) return"),
    "M43": ("a reply chunk arriving before the first poll is taken for this request's answer",
            "    if (((pending.polls ?: 0) as int) == 0) {", "    if (false) {"),
    "M44": ("an RPC retry takes a new request id, so the first try's late answer is lost",
            "    int id = sameId != null ? sameId : (((state.rpcId ?: 0) as int) % 99) + 1", "    int id = (((state.rpcId ?: 0) as int) % 99) + 1"),
    "M45": ("a firmware change seen twice within minutes re-reads the units twice",
            "        if (lastChange != null && now() - lastChange < FIRMWARE_SETTLE_MS) {", "        if (false) {"),
    "M46": ("Configure keeps the stale Bluetooth attribute",
            '        device.deleteCurrentState("bluetooth")        // what the driver last sent, not what a reset plug has\n', "\n"),
    "M47": ("a single-precision float ends the frame, hiding the attributes after it",
            "    if (type == 0x39) return 4                                       // single-precision float\n", "\n"),
    "M48": ("the LED mode preference is never sent",
            '    if (mode && mode != "keep") leds.mode = mode', '    if (false) leds.mode = mode'),
    "M49": ("a night-mode time that is not a time of day is sent as it is",
            r'''    return (s && s ==~ /([01]\d|2[0-3]):[0-5]\d/) ? s : fallback''', "    return s ?: fallback"),
    "M50": ("a cutoff retry skips the load check and the preference",
            "        String reason = retryBlocker(pending)", "        String reason = null"),
    "M51": ("a cutoff below the maximum goes out before the plug reported its draw",
            '    if (lastMs == null) return "the plug has not reported its power draw yet; refresh, then save again"', "    if (lastMs == null) return null"),
    "M52": ("a Wi-Fi setup starts while an earlier Wi-Fi write can still be answered",
            "@Field static final Long WIFI_QUIET_MS = 10000L", "@Field static final Long WIFI_QUIET_MS = 0L"),
    "M53": ("an acknowledgement before the setup's first frame advances the setup",
            "    if (((pending.sent ?: 0) as int) == 0) {", "    if (false) {"),
    "M54": ("a feature answered after the reporting setup never gets its reporting",
            '        if (!known && state.reportingChange != null) runIn(3, "applyReporting")   // answered after the reporting setup ran\n        String v = LIGHT_LEVELS[raw as Integer]', "        String v = LIGHT_LEVELS[raw as Integer]"),
    "M55": ("the reading before the last is always taken as the load, so an old high reading makes a later off a trip",
            "    if (lastMs != null && now() - lastMs <= TRIP_DROP_GRACE_MS && load < prev * TRIP_PEAK_DECAY) load = prev", "    load = prev"),
    "M56": ("an empty Wi-Fi password is not sent, so the old one stays",
            "    frames << wifiWrite(textRecord(0x0006, pass))                     // the password; an empty one clears what the plug holds",
            "    if (pass.length > 0) frames << wifiWrite(textRecord(0x0006, pass))"),
    "M57": ("a firmware change keeps the old unsupported flags",
            '        ["startUpOnOff", "startUpWritable", "powerFactorSupported", "lightLevelSupported", "wifiSupported"].each { state.remove(it) }\n        sendZigbee(scaleReads() + optionalReads())',
            "        sendZigbee(scaleReads())"),
    "M58": ("network names are decoded byte by byte, not as UTF-8",
            '    return new String(b, "UTF-8")', '    return new String(b, "ISO-8859-1")'),
    "M59": ("no grace after the load dropped: a 0 W report right before the off hides a trip",
            "@Field static final Long TRIP_DROP_GRACE_MS = 15000L", "@Field static final Long TRIP_DROP_GRACE_MS = 0L"),
    "M60": ("a stale Wi-Fi error cancels a setup that has not started",
            "    return pending != null && ((pending.sent ?: 0) as int) > 0", "    return pending != null"),
    "M61": ("a drop right before the off is taken as the load, hiding a protection trip",
            "    if (lastMs != null && now() - lastMs <= TRIP_DROP_GRACE_MS && load < prev * TRIP_PEAK_DECAY) load = prev", "    if (false) load = prev"),
    "M62": ("a stale tail confirms the next request whatever the reply length",
            "(tail.contains('restart_required') && successLengthMatches(head, pending))", "tail.contains('restart_required')"),
    "M63": ("a cutoff goes out on a power reading older than the switch-on",
            '    if (device.currentValue("switch") == "on" && onAt != null && lastMs < onAt) return "no power reading since the plug was switched on; refresh, then save again"\n', "\n"),
    "M64": ("the cutoff guard judges the adjusted display value",
            "    BigDecimal drawing = toDecimal(state.lastWatts)                            // the plug's own watts, not the adjusted display", '    BigDecimal drawing = toDecimal(device.currentValue("power"))'),
    "M65": ("a trip is judged against the preference, not the cutoff the plug confirmed",
            "    BigDecimal limit = acceptedPowerLimit()", "    BigDecimal limit = toDecimal(settings?.powerLimitWatts) > 0 ? toDecimal(settings?.powerLimitWatts) : PLUG_MAX_WATTS"),
    "M66": ("a default response to a read counts as a refused RPC write",
            '            if (d[0] == "02") rpcResult("refused", "the plug answered ${zclStatus(d[1])}")   // to a write of ours', '            if (true) rpcResult("refused", "the plug answered ${zclStatus(d[1])}")'),
    "M67": ("a default response to a read cancels a Wi-Fi setup",
            '            if (d[0] == "02" && wifiSetupActive()) wifiFinish("refused", "the plug answered ${zclStatus(d[1])}")', '            if (wifiSetupActive()) wifiFinish("refused", "the plug answered ${zclStatus(d[1])}")'),
    "M68": ("an acknowledged chunk does not restart the write timeout",
            '        runIn(RPC_TIMEOUT_SECONDS, "rpcTimeout")                                // each acknowledged chunk restarts the clock\n', "\n"),
    "M69": ("any switch value other than 00 counts as on",
            '        default:\n            logDebug "switch: ${hex} is not an on/off value, ignored"\n            return', '        default: value = "on"; break'),
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
