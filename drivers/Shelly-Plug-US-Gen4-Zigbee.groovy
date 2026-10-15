/*
 * Shelly Plug US Gen4 (Zigbee)
 *
 * Hubitat Elevation driver for the Shelly Plug US Gen4 (S4PL-00116US) running its Zigbee firmware:
 * switch, power, energy, voltage, current and AC frequency, with a cheap health check.
 *
 * Units come from the plug. The Electrical Measurement cluster (0x0B04) publishes a multiplier and a
 * divisor for voltage (0x0600/0x0601), current (0x0602/0x0603), power (0x0604/0x0605) and frequency
 * (0x0400/0x0401); the Metering cluster (0x0702) publishes them for energy (0x0301/0x0302). Configure
 * reads them and every reading is scaled with them, the way Zigbee2MQTT's electricityMeter() does. The
 * units vary by firmware: on 020000FF power arrives in W and current in mA, while a driver that divides
 * both by 100 shows a 1 kW heater as 10 W. When the plug does not answer for a pair, the driver uses the
 * units measured on firmware 020000FF (FALLBACK below) and logs a warning.
 *
 * The plug bundles several attributes into one report (current and power together, for example), so
 * every attribute of a report is handled, not only the first.
 *
 * Configure never switches the relay. These plugs run space heaters, and the hub's Generic Zigbee
 * Outlet tests the relay with an off/on cycle while configuring.
 *
 * Shelly's RPC channel over Zigbee (cluster 0xFC01) is used only to write two optional settings (power
 * limit, power-on state); readings never depend on it, because Hubitat cuts its replies short.
 *
 * The attribute map and the parse structure follow the "Shelly 1PM Gen 4 Zigbee Driver" by joaomf and
 * kkossev (Apache-2.0, https://github.com/joaomf/hubitat). See NOTICE.
 *
 * Copyright 2026 Chris Busch
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0)
 *
 * 1.0.0  2026-10-12  First release (WOR-758)
 * 1.0.1  2026-10-15  Health check pings before it marks a plug offline (WOR-777)
 * 1.1.0  2026-10-15  Identify, light level, trip detection, power-on state and power limit settings,
 *                    power factor where offered (WOR-759)
 * 1.2.0  2026-10-15  setBluetooth command, for the Shelly app (Wi-Fi setup, firmware updates)
 */

import groovy.json.JsonOutput
import groovy.transform.Field
import hubitat.zigbee.zcl.DataType

static String driverVersion() { "1.2.0" }

metadata {
    definition(
        name:      "Shelly Plug US Gen4 (Zigbee)",
        namespace: "chsbusch-dot",
        author:    "Chris Busch",
        importUrl: "https://raw.githubusercontent.com/chsbusch-dot/hubitat-shelly-gen4-zigbee/main/drivers/Shelly-Plug-US-Gen4-Zigbee.groovy"
    ) {
        capability "Actuator"
        capability "Switch"
        capability "Outlet"
        capability "PowerMeter"
        capability "EnergyMeter"
        capability "VoltageMeasurement"
        capability "CurrentMeter"
        capability "Refresh"
        capability "Configuration"
        capability "HealthCheck"

        attribute "acFrequency",  "number"
        attribute "healthStatus", "enum", ["online", "offline", "unknown"]
        attribute "powerOnState", "enum", ["off", "on", "toggle", "previous"]
        attribute "lightLevel",   "enum", ["dark", "twilight", "bright"]
        attribute "lastSettingsWrite", "string"
        attribute "bluetooth",    "enum", ["on", "off"]
        attribute "powerFactor",  "number"
        attribute "tripCount",    "number"
        attribute "lastTrip",     "string"

        command "identify", [[name: "seconds", type: "NUMBER", description: "How long the LED blinks (1 to 600, default 10)"]]
        command "setBluetooth", [[name: "state", type: "ENUM", constraints: ["on", "off"],
                                  description: "Bluetooth on the plug; the Shelly app needs it for Wi-Fi setup and firmware updates"]]

        // As the hub recorded them: joined on firmware 02000021 (no out clusters listed), and the
        // simple descriptor of firmware 2.0.1 (1490-1852-020001FF), which adds Shelly's cluster FC21.
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0004,0005,0006,0B04,0702",
                    manufacturer: "Shelly", model: "Plug US", deviceJoinName: "Shelly Plug US Gen4"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0004,0005,0006,0B04,0702,FC21",
                    outClusters: "0019", manufacturer: "Shelly", model: "Plug US", deviceJoinName: "Shelly Plug US Gen4"
    }

    preferences {
        input name: "reportMaxSeconds", type: "number", title: "Longest time between reports (seconds)",
              description: "The plug reports at least this often even when nothing changes. It also sets the health check: no message for three of these periods marks the plug offline.",
              range: "60..3600", defaultValue: 600
        input name: "powerChangeWatts", type: "decimal", title: "Report power when it changes by (W)",
              range: "1..500", defaultValue: 5
        input name: "powerAdjustPct", type: "decimal", title: "Power adjustment (%)",
              description: "Leave at 0 unless the readings are off. 2 reads 2% higher.", defaultValue: 0
        input name: "voltageAdjustPct", type: "decimal", title: "Voltage adjustment (%)", defaultValue: 0
        input name: "currentAdjustPct", type: "decimal", title: "Current adjustment (%)", defaultValue: 0
        input name: "frequencyOffsetHz", type: "decimal", title: "AC frequency offset (Hz)", defaultValue: 0
        input name: "powerOnState", type: "enum", title: "After a power cut, the plug turns",
              options: ["keep": "Keep the plug's own setting", "off": "Off", "on": "On (a heater comes back on by itself)", "previous": "Back to how it was"],
              defaultValue: "keep"
        input name: "powerLimitWatts", type: "number", title: "Switch off above (W)", range: "100..1800",
              description: "The plug switches itself OFF when the load draws more than this. Leave empty to keep the plug's own setting; 1800 W is its maximum."
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
        input name: "logEnable", type: "bool", title: "Enable debug logging",
              description: "Turns itself off after 30 minutes.", defaultValue: false
    }
}

// One row per measured quantity: where the raw value lives, the plug's own multiplier and divisor
// attributes in the same cluster, how a reading is shown, the default reporting threshold (in the
// shown unit) and the raw ZCL type.
@Field static final Map QUANTITIES = [
    voltage  : [cluster: 0x0B04, attr: 0x0505, multAttr: 0x0600, divAttr: 0x0601, event: "voltage",     unit: "V",   decimals: 1, change: 1.0,  type: "uint16"],
    current  : [cluster: 0x0B04, attr: 0x0508, multAttr: 0x0602, divAttr: 0x0603, event: "amperage",    unit: "A",   decimals: 3, change: 0.05, type: "uint16"],
    power    : [cluster: 0x0B04, attr: 0x050B, multAttr: 0x0604, divAttr: 0x0605, event: "power",       unit: "W",   decimals: 1, change: 5,    type: "int16"],
    frequency: [cluster: 0x0B04, attr: 0x0300, multAttr: 0x0400, divAttr: 0x0401, event: "acFrequency", unit: "Hz",  decimals: 2, change: 0.1,  type: "uint16"],
    energy   : [cluster: 0x0702, attr: 0x0000, multAttr: 0x0301, divAttr: 0x0302, event: "energy",      unit: "kWh", decimals: 3, change: 0.01, type: "uint48"],
]

// Units measured on plug firmware 020000FF (2026-10-11): voltage 11960 = 119.6 V, current in mA, power
// in W (117 V x 8.989 A = 1052 W against a raw 1028), frequency 6005 = 60.05 Hz, energy in mWh.
// Used only for a quantity whose multiplier and divisor the plug did not report.
@Field static final Map FALLBACK = [
    voltage  : [mult: 1, div: 100],
    current  : [mult: 1, div: 1000],
    power    : [mult: 1, div: 1],
    frequency: [mult: 1, div: 100],
    energy   : [mult: 1, div: 1000000],
]

@Field static final Integer REPORT_MIN_SECONDS = 10
@Field static final Integer MFG_SHELLY = 0x1490
@Field static final List OPTIONAL_CLUSTERS = ["FC21"]
@Field static final Map POWER_ON_STATES = ["00": "off", "01": "on", "02": "toggle", "FF": "previous"]
@Field static final Map LIGHT_LEVELS = [0: "dark", 1: "twilight", 2: "bright"]
// Attributes only some firmware answers: "cluster/attr" -> the state flag that remembers the answer.
@Field static final Map OPTIONAL_ATTRIBUTES = ["0006/4003": "startUpOnOff", "0B04/0510": "powerFactorSupported", "FC21/0000": "lightLevelSupported"]
// An off the hub did not send, this soon after an on or at this share of the power limit, is a trip.
@Field static final Long TRIP_WINDOW_MS = 10000L
@Field static final BigDecimal TRIP_LOAD_SHARE = 0.9
@Field static final Integer PLUG_MAX_WATTS = 1800
@Field static final Long RX_STAMP_EVERY_MS = 60000L
@Field static final Long COMMAND_ECHO_MS = 10000L
@Field static final Integer PING_GRACE_SECONDS = 60

// Shelly's RPC channel over Zigbee: cluster FC01 on endpoint 0xEF, profile 0xC001. A request is written
// as its length (TxCtl, 0x0001) and its JSON text (TxData, 0x0000); the reply length is RxCtl (0x0002)
// and the reply text RxData (0x0000). Hubitat cuts the reply text short (kkossev, 2025), but its opening
// still names the request id and whether "result" or "error" follows, which is all a settings write needs.
@Field static final Integer RPC_POLLS = 6
@Field static final Integer RPC_CHUNK_BYTES = 40
@Field static final Integer RPC_ATTEMPTS = 2
@Field static final Integer POWER_LIMIT_MIN = 100
@Field static final Integer RPC_TIMEOUT_SECONDS = 25
@Field static final Map RPC_INITIAL_STATE = ["off": "off", "on": "on", "previous": "restore_last"]   // "keep" is absent: never sent
@Field static final Map START_UP_ON_OFF = ["off": 0x00, "on": 0x01, "previous": 0xFF]

// What the "Shelly 1PM Gen 4 Zigbee Driver" leaves on a device that is moved to this driver.
@Field static final List LEGACY_SETTINGS = ["powerCalibration", "voltageCalibration", "currentCalibration", "acFrequencyCalibration", "reportingInterval"]
@Field static final List LEGACY_ATTRIBUTES = ["producedEnergy", "rpcStatus", "rpcResponse", "inputMode"]
@Field static final List LEGACY_STATE = ["rpcJsonRequest", "rpcMachinePhase", "rpcMachineTries", "rpcRxAccum", "rpcRxBytesRead", "rpcRxTargetLen", "rpcRxText", "zclSeq", "scaleFirmware"]

// --------------------------------------------------------------------------- lifecycle

void installed() {
    log.info "${device.displayName}: installed (driver ${driverVersion()})"
    sendEvent(name: "healthStatus", value: "unknown")
    scheduleHousekeeping()
}

void updated() {
    log.info "${device.displayName}: preferences saved (driver ${driverVersion()})"
    migrateFromOtherDrivers()
    scheduleHousekeeping()
    runIn(2, "applyReporting")
    // After the reporting setup's radio traffic; only settings the user filled in are ever sent.
    state.remove("settingsSkip")
    runIn(30, "applyPlugSettings")
}

/** Reads the plug's units, then sets up reporting from them. Never switches the relay. */
List<String> configure() {
    log.info "${device.displayName}: configuring (driver ${driverVersion()}); the relay is not switched"
    migrateFromOtherDrivers()
    state.remove("fallbackWarned")
    ["plugSettings", "settingsSkip", "startUpOnOff", "powerFactorSupported", "lightLevelSupported"].each { state.remove(it) }
    scheduleHousekeeping()
    List<String> cmds = []
    cmds += zigbee.readAttribute(0x0000, 0x4000)      // software build, to notice a firmware change
    cmds += scaleReads()
    cmds += optionalReads()
    // The scale answers arrive within seconds; reporting thresholds are raw units, so wait for them.
    runIn(8, "applyReporting")
    return cmds
}

List<String> refresh() {
    logDebug "refresh()"
    List<String> cmds = []
    cmds += zigbee.readAttribute(0x0006, 0x0000)
    cmds += zigbee.readAttribute(0x0B04, [0x0505, 0x0508, 0x050B, 0x0300])
    cmds += zigbee.readAttribute(0x0702, 0x0000)
    if (state.powerFactorSupported == true) cmds += zigbee.readAttribute(0x0B04, 0x0510)
    if (state.lightLevelSupported == true) cmds += zigbee.readAttribute(0xFC21, 0x0000, [mfgCode: MFG_SHELLY])
    cmds += zigbee.readAttribute(0x0000, 0x4000)
    if (!scaleComplete()) cmds += scaleReads()
    return cmds
}

List<String> on() {
    logDebug "on()"
    state.pendingSwitch = [value: "on", at: now()]
    return zigbee.on()
}

List<String> off() {
    logDebug "off()"
    state.pendingSwitch = [value: "off", at: now()]
    return zigbee.off()
}

/** Blinks the plug's LED (Identify cluster 0x0003) so it can be found; the relay is not touched. */
List<String> identify(seconds = null) {
    int s = toDecimal(seconds).intValue()
    if (s <= 0) s = 10
    s = Math.min(600, s)
    logInfo "identify: blinking for ${s} s"
    List<String> cmds = []
    cmds += zigbee.command(0x0003, 0x00, String.format("%02X%02X", s & 0xFF, (s >> 8) & 0xFF))
    cmds += zigbee.readAttribute(0x0003, 0x0000)        // IdentifyTime: non-zero while it blinks
    return cmds
}

/** HealthCheck: ask for the switch state; any answer marks the plug online. */
List<String> ping() {
    logDebug "ping()"
    return zigbee.readAttribute(0x0006, 0x0000)
}

void logsOff() {
    log.info "${device.displayName}: debug logging turned off"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

/**
 * Every 15 minutes: after three silent reporting periods, ping. Only a ping that goes unanswered marks
 * the plug offline (healthConfirm). An idle plug skips its periodic reports yet answers at once: one plug
 * went "offline" 8 times in three days that way under 1.0.0, back online within seconds each time.
 */
void healthCheck() {
    Long last = state.lastRxMs as Long
    long timeoutMs = healthTimeoutSeconds() * 1000L
    if (last != null && now() - last <= timeoutMs) return
    state.healthPingMs = now()
    sendZigbee(ping())
    runIn(PING_GRACE_SECONDS, "healthConfirm")
}

/** PING_GRACE_SECONDS after a health ping: offline unless something arrived since the ping. */
void healthConfirm() {
    Long asked = state.healthPingMs as Long
    state.remove("healthPingMs")
    Long last = state.lastRxMs as Long
    if (asked == null || (last != null && last >= asked)) return
    if (device.currentValue("healthStatus") != "offline") {
        String since = last == null ? "since the driver started" : "for ${Math.round((now() - last) / 60000.0)} minutes"
        sendEvent(name: "healthStatus", value: "offline", descriptionText: "${device.displayName} is offline (no message ${since}, no answer to a ping)")
        logWarn "offline, no message ${since} and no answer to a ping"
    }
}

/**
 * Sets up attribute reporting with thresholds converted to the plug's raw units. Runs after Configure
 * (once the units are in), after Save Preferences, and again if the plug reports units that change a
 * threshold already sent.
 */
void applyReporting() {
    if (!scaleComplete() && !scaleAskedRecently()) {
        // Save Preferences before any Configure: ask for the units, then come back.
        sendZigbee(scaleReads())
        runIn(8, "applyReporting")
        return
    }
    Integer maxSec = reportMaxSecondsSetting()
    List<String> cmds = []
    cmds += zigbee.configureReporting(0x0006, 0x0000, DataType.BOOLEAN, 0, maxSec, 1)
    Map applied = [:]
    List<String> shown = []
    QUANTITIES.each { String q, Map d ->
        Map sc = scaleOf(q)
        if (sc.source != "plug") warnFallback(q)
        BigDecimal change = q == "power" ? powerChangeSetting() : (d.change as BigDecimal)
        Integer raw = rawChange(change, sc, d.type as String)
        applied[q] = raw
        shown << "${d.event} ${change} ${d.unit}"
        cmds += zigbee.configureReporting(d.cluster as Integer, d.attr as Integer, dataTypeOf(d.type as String), REPORT_MIN_SECONDS, maxSec, raw)
    }
    if (state.powerFactorSupported == true) {
        cmds += zigbee.configureReporting(0x0B04, 0x0510, DataType.INT8, REPORT_MIN_SECONDS, maxSec, 5)   // 0.05
        shown << "powerFactor 0.05"
    }
    if (state.lightLevelSupported == true) {
        // Zigbee2MQTT's interval for this Shelly cluster: at most once a minute, at least every 15 minutes.
        cmds += zigbee.configureReporting(0xFC21, 0x0000, DataType.UINT8, 60, 900, 1, [mfgCode: MFG_SHELLY])
        shown << "lightLevel"
    }
    state.reportingChange = applied
    logInfo "reporting every ${REPORT_MIN_SECONDS} to ${maxSec} s, on a change of ${shown.join(', ')}"
    cmds += refresh()
    sendZigbee(cmds)
}

// --------------------------------------------------------------------------- plug settings

/**
 * Sends the power-on state and power limit preferences when they differ from what the plug last
 * accepted, one setting at a time. Empty or "keep" preferences are never sent, so a plug nobody
 * configured is left as it is. The power-on state goes through the standard attribute 0006/0x4003
 * when the plug answered it on Configure, and through RPC if the plug refuses that write; the power
 * limit always goes through Shelly's RPC (Switch.SetConfig). A setting that is refused or unconfirmed
 * is not retried until the next Save Preferences.
 */
void applyPlugSettings() {
    if (state.rpcPending != null || state.startUpPending != null) {
        runIn(30, "applyPlugSettings")                 // one write at a time
        return
    }
    Map todo = pendingPlugSettings()
    if (!todo) return
    if (todo.power_limit != null) {
        BigDecimal drawing = toDecimal(device.currentValue("power"))
        if (drawing >= (todo.power_limit as BigDecimal) * TRIP_LOAD_SHARE) {
            settingsOutcome("power_limit", todo.power_limit, "not sent", "the plug is drawing ${drawing} W and would switch itself off")
            return
        }
    }
    if (todo.initial_state != null && state.startUpOnOff == true) {
        logInfo "setting the power-on state to ${todo.initial_state} (0006/4003)"
        state.startUpPending = [value: todo.initial_state]
        List<String> cmds = []
        cmds += zigbee.writeAttribute(0x0006, 0x4003, DataType.ENUM8, START_UP_ON_OFF[todo.initial_state] as Integer)
        cmds += zigbee.readAttribute(0x0006, 0x4003)   // the read-back confirms it (handlePowerOnState)
        sendZigbee(cmds)
        runIn(RPC_TIMEOUT_SECONDS, "startUpTimeout")
        return
    }
    String key = todo.initial_state != null ? "initial_state" : "power_limit"
    def value = key == "initial_state" ? RPC_INITIAL_STATE[todo.initial_state] : todo.power_limit
    sendRpc("Switch.SetConfig", [id: 0, config: [(key): value]], key, todo[key])
}

/**
 * Turns the plug's Bluetooth on or off through Shelly's RPC (BLE.SetConfig); "on" also enables RPC over
 * Bluetooth, which the Shelly app uses to reach a plug in Zigbee mode (Wi-Fi setup, firmware updates).
 * The relay is not touched.
 */
void setBluetooth(String onOff) {
    String v = onOff?.toString()?.toLowerCase() == "off" ? "off" : "on"
    if (state.rpcPending != null || state.startUpPending != null) {
        settingsOutcome("bluetooth", v, "not sent", "another write is in progress; try again in a minute", false)
        return
    }
    logInfo "turning Bluetooth ${v}"
    Map config = v == "on" ? [enable: true, rpc: [enable: true]] : [enable: false]
    sendRpc("BLE.SetConfig", [config: config], "bluetooth", v)
}

/** Preferences that differ from what the plug accepted, minus those already refused this round. */
private Map pendingPlugSettings() {
    Map wanted = [:]
    String onState = settings?.powerOnState as String
    if (onState && RPC_INITIAL_STATE[onState]) wanted.initial_state = onState
    BigDecimal limit = toDecimal(settings?.powerLimitWatts)
    if (limit > 0) wanted.power_limit = Math.max(POWER_LIMIT_MIN, Math.min(PLUG_MAX_WATTS, limit.intValue()))
    Map done = (state.plugSettings ?: [:]) as Map
    List skip = (state.settingsSkip ?: []) as List
    return wanted.findAll { k, v -> done[k] != v && !skip.contains(k) }
}

void startUpTimeout() {
    Map pending = state.startUpPending as Map
    if (pending == null) return
    state.remove("startUpPending")
    settingsOutcome("initial_state", pending.value, "unconfirmed", "no answer from 0006/4003 in ${RPC_TIMEOUT_SECONDS} s")
}

/** The standard power-on write was refused: forget 0006/4003 for writing and try RPC next. */
private void startUpRefused(String detail) {
    Map pending = state.startUpPending as Map
    if (pending == null) return
    state.remove("startUpPending")
    unschedule("startUpTimeout")
    state.startUpOnOff = false
    settingsOutcome("initial_state", pending.value, "refused", "${detail}; trying RPC", false)
}

/** Records one setting's outcome and moves on to the next pending setting, if any. */
private void settingsOutcome(String key, value, String result, String detail, boolean skipOnFail = true) {
    String what = key == "initial_state" ? "power-on state ${value}" : (key == "bluetooth" ? "Bluetooth ${value}" : "power limit ${value} W")
    String text = "${what}: ${result}${detail ? ' (' + detail + ')' : ''}"
    if (result == "accepted") {
        state.plugSettings = ((state.plugSettings ?: [:]) as Map) + [(key): value]
        if (key == "bluetooth") sendEvent(name: "bluetooth", value: value)
        logInfo text
    } else {
        logWarn text
        if (skipOnFail) state.settingsSkip = ((state.settingsSkip ?: []) as List) + [key]
    }
    sendEvent(name: "lastSettingsWrite", value: text)
    runIn(2, "applyPlugSettings")
}

/**
 * One RPC request: its length to TxCtl, then its JSON text to TxData in chunks of RPC_CHUNK_BYTES
 * (one Zigbee frame holds about 80 bytes; Zigbee2MQTT writes 40 at a time), then polls for the reply.
 */
private void sendRpc(String method, Map params, String key, value, int attempt = 1) {
    int id = (((state.rpcId ?: 0) as int) % 99) + 1
    state.rpcId = id
    byte[] bytes = JsonOutput.toJson([id: id, method: method, params: params]).getBytes("UTF-8")
    state.rpcPending = [id: id, polls: 0, key: key, value: value, reply: "", method: method, params: params, attempt: attempt]
    logDebug "RPC ${method} ${params}"
    List<String> cmds = [rpcFrame("02", "0100" + "23" + le32(bytes.length))]                     // TxCtl: length
    for (int off = 0; off < bytes.length; off += RPC_CHUNK_BYTES) {
        int n = Math.min(RPC_CHUNK_BYTES, bytes.length - off)
        cmds << "delay 150"
        cmds << rpcFrame("02", "0000" + "42" + String.format("%02X", n) + hexOf(bytes, off, n))     // TxData chunk
    }
    sendZigbee(cmds)
    runIn(2, "rpcPoll")
    runIn(RPC_TIMEOUT_SECONDS, "rpcTimeout")
}

/** Reads the reply length and the next reply chunk, a few times until the answer can be judged. */
void rpcPoll() {
    Map pending = state.rpcPending as Map
    if (pending == null) return
    int polls = ((pending.polls ?: 0) as int) + 1
    pending.polls = polls
    state.rpcPending = pending
    sendZigbee([rpcFrame("00", "0200"), "delay 300", rpcFrame("00", "0000")])
    if (polls < RPC_POLLS) runIn(3, "rpcPoll")
}

/**
 * No readable answer: these requests are idempotent (the same setting written twice), so ask once more
 * before calling it unconfirmed. On 2026-10-15 one of five live writes needed exactly that.
 */
void rpcTimeout() {
    Map pending = state.rpcPending as Map
    if (pending == null) return
    if (((pending.attempt ?: 1) as int) < RPC_ATTEMPTS) {
        logDebug "RPC ${pending.method}: no readable answer, asking once more"
        unschedule("rpcPoll")
        sendRpc(pending.method as String, pending.params as Map, pending.key as String, pending.value, ((pending.attempt ?: 1) as int) + 1)
        return
    }
    String again = pending.key == "bluetooth" ? "run setBluetooth again" : "Save Preferences sends it again"
    rpcResult("unconfirmed", "no readable answer in ${RPC_ATTEMPTS} tries; ${again}")
}

private void rpcResult(String result, String detail) {
    Map pending = state.rpcPending as Map
    if (pending == null) return
    state.remove("rpcPending")
    unschedule("rpcPoll")
    unschedule("rpcTimeout")
    settingsOutcome(pending.key as String, pending.value, result, detail)
}

private String rpcFrame(String command, String body) {
    int seq = (((state.zclSeq ?: 0) as int) + 1) & 0xFF   // ZCL transaction number, not the RPC id
    state.zclSeq = seq
    String mfg = String.format("%02X%02X", MFG_SHELLY & 0xFF, (MFG_SHELLY >> 8) & 0xFF)
    return "he raw 0x${device.deviceNetworkId} 1 0xEF 0xFC01 {04${mfg}${String.format('%02X', seq)}${command}${body}} {0xC001}".toString()
}

/** FC01 frames: write and default responses to the request, read responses with the reply. */
private void parseRpc(Map descMap) {
    String command = (descMap.command as String)?.toUpperCase()
    List<String> d = (descMap.data ?: []) as List<String>
    if (command == "04") {
        if (d && d[0] != "00") rpcResult("refused", "the plug rejected the request frame: ${zclStatus(d[0])}")
        return
    }
    if (command == "0B") {
        if (d.size() >= 2 && d[1] != "00") rpcResult("refused", "the plug answered ${zclStatus(d[1])}")
        return
    }
    if (command != "01" && command != "0A") return
    decodeAttributeRecords(d, command == "01").each { Map r ->
        if (r.status != "00") {
            logDebug "RPC ${r.attrId}: ${zclStatus(r.status as String)}"
        } else if (r.attrId == "0002" && r.value) {
            logDebug "RPC reply length ${Long.parseLong(r.value as String, 16)}"
        } else if (r.attrId == "0000") {
            handleRpcChunk(hexToText(r.value as String))
        }
    }
}

/**
 * Collects reply chunks and judges them. Chunks can arrive out of order (2026-10-15: the 30-byte tail
 * of an 80-byte reply reached the driver before its 50-byte opening), so the opening is kept apart from
 * the rest. Hubitat also cuts chunks short (that opening arrived as 46 of 50 bytes, ending in "r), so
 * only the opening is trusted: the request id, then "error" anywhere, or "result" (or the r of it,
 * right after src).
 */
private void handleRpcChunk(String text) {
    Map pending = state.rpcPending as Map
    if (pending == null || !text) return
    if (text.startsWith('{"id":')) {
        pending.head = text
    } else {
        String tail = ((pending.tail ?: "") as String) + text
        pending.tail = tail.length() > 300 ? tail.substring(0, 300) : tail
    }
    state.rpcPending = pending
    String head = pending.head as String
    if (!head) {
        logDebug "RPC reply tail before its opening: ${text}"
        return
    }
    def id = head =~ /^\{"id":(\d+)[,}]/
    if (!id.find()) return
    if ((id.group(1) as Integer) != (pending.id as Integer)) {
        logDebug "RPC reply ${id.group(1)} is not ours (${pending.id})"
        pending.remove("head")
        pending.remove("tail")
        state.rpcPending = pending
        return
    }
    String reply = head + ((pending.tail ?: "") as String)
    if (reply.contains('"error"')) {
        def code = reply =~ /"code":(-?\d+)/
        rpcResult("refused", code.find() ? "error ${code.group(1)}" : null)
    } else if (reply.contains('"result"') || (head =~ /"src":"[^"]*","r/).find()) {
        rpcResult("accepted", null)
    } else {
        logDebug "RPC reply not judged yet: ${reply}"
    }
}

private static String le32(int n) {
    return String.format("%02X%02X%02X%02X", n & 0xFF, (n >> 8) & 0xFF, (n >> 16) & 0xFF, (n >> 24) & 0xFF)
}

private static String hexOf(byte[] bytes, int off, int n) {
    StringBuilder sb = new StringBuilder()
    for (int k = off; k < off + n; k++) sb.append(String.format("%02X", bytes[k] & 0xFF))
    return sb.toString()
}

/** Raw bytes as text, every byte kept (a cut-short reply ends in garbage that must not hide its start). */
private static String hexToText(String hex) {
    StringBuilder sb = new StringBuilder()
    for (int k = 0; k + 1 < (hex ?: "").length(); k += 2) sb.append((char) Integer.parseInt(hex.substring(k, k + 2), 16))
    return sb.toString()
}

// --------------------------------------------------------------------------- parse

def parse(String description) {
    noteRx()
    Map descMap = zigbee.parseDescriptionAsMap(description)
    logDebug "parse: ${description} -> ${descMap}"
    if (!descMap) return null
    if (descMap.profileId == "0000") {
        logDebug "ZDO ${descMap.clusterId} ${descMap.data}"
        return null
    }
    String cluster = ((descMap.cluster ?: descMap.clusterId) as String)?.toUpperCase()
    if (cluster == "FC01") {
        parseRpc(descMap)
        return null
    }
    if (descMap.attrId != null) {
        // A report or read response; the plug bundles attributes, so take every one of them.
        handleAttribute(cluster, descMap.attrId as String, descMap.encoding as String, descMap.value as String)
        descMap.additionalAttrs?.each { Map a ->
            handleAttribute(cluster, a.attrId as String, (a.encoding ?: null) as String, a.value as String)
        }
    } else if (descMap.command != null) {
        parseCatchall(cluster, descMap)
    }
    return null
}

private void handleAttribute(String cluster, String attrId, String encoding, String value) {
    String attr = attrId?.toUpperCase()
    if (attr == null || value == null) return
    if (cluster == "0006" && attr == "0000") {
        handleSwitch(value)
        return
    }
    if (cluster == "0000" && attr == "4000") {
        handleSoftwareBuild(value, encoding)
        return
    }
    if (cluster == "0B04" && attr == "0510") {
        handlePowerFactor(value, encoding)
        return
    }
    if (cluster == "0006" && attr == "4003") {
        handlePowerOnState(value)
        return
    }
    if (cluster == "0003" && attr == "0000") {
        logInfo "identify: ${decodeRaw(value, encoding, 'uint16') ?: 0} s left"
        return
    }
    if (cluster == "FC21") {
        handleLightLevel(attr, value, encoding)
        return
    }
    if (cluster == "0702" && attr == "0300") {
        if (value.toUpperCase() != "00") logWarn "the plug counts energy in unit ${value}, not kWh; energy readings may be off"
        return
    }
    Map hit = lookupAttribute(cluster, attr)
    if (hit == null) {
        logDebug "ignored ${cluster}/${attr} = ${value}"
        return
    }
    if (hit.role == "value") handleMeasurement(hit.q as String, value, encoding)
    else handleScale(hit.q as String, hit.role as String, value)
}

private void handleSwitch(String hex) {
    String value
    try {
        value = Integer.parseInt(hex, 16) == 0 ? "off" : "on"
    } catch (NumberFormatException ignored) {
        return
    }
    String type = "physical"
    Map pending = state.pendingSwitch as Map
    if (pending != null && pending.value == value && now() - (pending.at as Long) <= COMMAND_ECHO_MS) {
        type = "digital"
        state.remove("pendingSwitch")
    }
    boolean changed = device.currentValue("switch") != value
    String text = "${device.displayName} was turned ${value} [${type}]"
    if (changed) logInfo text
    if (changed && value == "on") {
        state.onAtMs = now()
        state.peakWatts = 0
    }
    if (changed && value == "off" && type == "physical") checkTrip()
    sendEvent(name: "switch", value: value, type: type, descriptionText: text)
}

/**
 * An off the hub did not send, seconds after an on or under a load near the limit, is the plug's own
 * protection (or, rarely, someone at the button). A test plug did this after every on for hours on
 * 2026-10-13 with a ~2 kW load on its 1800 W rating; a rule watching tripCount can stop re-sending on.
 */
private void checkTrip() {
    Long onAt = state.onAtMs as Long
    BigDecimal peak = toDecimal(state.peakWatts)
    BigDecimal limit = toDecimal(settings?.powerLimitWatts) > 0 ? toDecimal(settings?.powerLimitWatts) : PLUG_MAX_WATTS
    boolean quick = onAt != null && now() - onAt <= TRIP_WINDOW_MS
    boolean heavy = peak >= limit * TRIP_LOAD_SHARE
    if (!quick && !heavy) return
    int count = toDecimal(device.currentValue("tripCount")).intValue() + 1
    String when = quick ? "${Math.round((now() - onAt) / 1000.0)} s after it was switched on" : "under load"
    String text = "switched itself off ${when}, at up to ${peak.setScale(0, BigDecimal.ROUND_HALF_UP)} W (limit ${limit} W): likely its overcurrent or overpower protection"
    logWarn "${text}"
    sendEvent(name: "tripCount", value: count, descriptionText: "${device.displayName} ${text}")
    sendEvent(name: "lastTrip", value: text, isStateChange: true)
}

private void handleMeasurement(String q, String hex, String encoding) {
    Map d = QUANTITIES[q] as Map
    Long raw = decodeRaw(hex, encoding, d.type as String)
    if (raw == null) {
        logDebug "${d.event}: no valid reading (${hex})"
        return
    }
    Map sc = scaleOf(q)
    BigDecimal value = new BigDecimal(raw).multiply(new BigDecimal(sc.mult as Long))
                                          .divide(new BigDecimal(sc.div as Long), 6, BigDecimal.ROUND_HALF_UP)
    value = adjust(q, value).setScale(d.decimals as Integer, BigDecimal.ROUND_HALF_UP)
    String text = "${device.displayName} ${d.event} is ${value} ${d.unit}"
    if (device.currentValue(d.event as String)?.toString() != value.toString()) logInfo text
    if (q == "power" && device.currentValue("switch") == "on" && value > toDecimal(state.peakWatts)) state.peakWatts = value
    sendEvent(name: d.event, value: value, unit: d.unit, descriptionText: text)
}

private void handleScale(String q, String role, String hex) {
    Long v = decodeRaw(hex, null, "uint24")
    String what = "${QUANTITIES[q].event} ${role == 'mult' ? 'multiplier' : 'divisor'}"
    if (v == null || v <= 0) {
        logWarn "the plug reported ${what} as ${hex}; ignoring it"
        return
    }
    Map before = scaleOf(q)
    Map scale = (state.scale ?: [:]) as Map
    scale[q] = ((scale[q] ?: [:]) as Map) + [(role): v]
    state.scale = scale
    logDebug "${what} = ${v}"
    Map sc = scaleOf(q)
    if (sc.source != "plug" || sc == before) return
    logInfo "${QUANTITIES[q].event} units from the plug: x${sc.mult}/${sc.div}"
    Map applied = state.reportingChange as Map
    if (applied?.get(q) != null) {
        Map d = QUANTITIES[q] as Map
        BigDecimal change = q == "power" ? powerChangeSetting() : (d.change as BigDecimal)
        if (rawChange(change, sc, d.type as String) != (applied[q] as Integer)) runIn(3, "applyReporting")
    }
}

private void handleSoftwareBuild(String value, String encoding) {
    String build = decodeCharString(value, encoding)
    if (!build) return
    if (device.getDataValue("softwareBuild") != build) device.updateDataValue("softwareBuild", build)
    // The hub keeps firmwareMT (manufacturer-image-version) from the plug's OTA queries; together with
    // the software build it names the firmware. one plug kept softwareBuild 02000021 from pairing while
    // firmwareMT had moved on to 020001FF (2026-10-12).
    String firmware = [build, device.getDataValue("firmwareMT")].findAll { it }.join(" ")
    String previous = state.firmware as String
    state.firmware = firmware
    if (previous != null && previous != firmware) {
        logWarn "firmware changed from ${previous} to ${firmware}; reading the plug's units again"
        state.remove("scale")
        state.remove("fallbackWarned")
        sendZigbee(scaleReads())
        runIn(8, "applyReporting")
    }
}

/** Catchall frames: configure-reporting answers, default responses, and attribute frames Hubitat did not decode. */
private void parseCatchall(String cluster, Map descMap) {
    List<String> data = (descMap.data ?: []) as List<String>
    String command = (descMap.command as String)?.toUpperCase()
    switch (command) {
        case "07":   // Configure Reporting Response
            if (data == ["00"] || data.isEmpty()) {
                logDebug "reporting accepted for cluster ${cluster}"
                return
            }
            for (int i = 0; i + 3 < data.size(); i += 4) {
                if (data[i] == "00") continue
                logWarn "the plug refused reporting for ${cluster}/${data[i + 3]}${data[i + 2]} (${zclStatus(data[i])})"
            }
            return
        case "04":   // Write Attributes Response: only the power-on state is ever written here
            if (cluster == "0006" && data != ["00"]) {
                for (int i = 0; i + 2 < data.size(); i += 3) {
                    if (data[i] != "00" && (data[i + 2] + data[i + 1]).toUpperCase() == "4003") startUpRefused("0006/4003 write: ${zclStatus(data[i])}")
                }
            }
            return
        case "0B":   // Default Response
            if (data.size() >= 2 && data[1] != "00") {
                String text = "the plug answered command ${data[0]} on cluster ${cluster} with ${zclStatus(data[1])}"
                if (cluster == "FC21") state.lightLevelSupported = false
                if (cluster in OPTIONAL_CLUSTERS) logInfo text else logWarn "${text}"
            }
            return
        case "01":   // Read Attributes Response
        case "0A":   // Report Attributes
            decodeAttributeRecords(data, command == "01").each { Map r ->
                if (r.status != "00") {
                    String key = "${cluster}/${r.attrId}".toString()
                    if (cluster in OPTIONAL_CLUSTERS || OPTIONAL_ATTRIBUTES[key]) {
                        if (OPTIONAL_ATTRIBUTES[key]) state[OPTIONAL_ATTRIBUTES[key] as String] = false
                        logInfo "the plug does not offer ${cluster}/${r.attrId} (${zclStatus(r.status as String)})"
                    } else {
                        logDebug "${cluster}/${r.attrId}: ${zclStatus(r.status as String)}"
                    }
                } else {
                    handleAttribute(cluster, r.attrId as String, r.encoding as String, r.value as String)
                }
            }
            return
        default:
            logDebug "ignored catchall ${cluster} command ${command} ${data}"
    }
}

// --------------------------------------------------------------------------- helpers

private void noteRx() {
    Long last = state.lastRxMs as Long
    if (last == null || now() - last >= RX_STAMP_EVERY_MS) state.lastRxMs = now()
    if (device.currentValue("healthStatus") != "online") {
        sendEvent(name: "healthStatus", value: "online", descriptionText: "${device.displayName} is online")
    }
}

private void scheduleHousekeeping() {
    unschedule("healthCheck")
    runEvery15Minutes("healthCheck")
    sendEvent(name: "checkInterval", value: healthTimeoutSeconds(), unit: "s")
    if (logEnable) runIn(1800, "logsOff")
}

/** Removes what the 1PM Gen 4 driver left behind; its calibration percentages would now be wrong. */
private void migrateFromOtherDrivers() {
    LEGACY_SETTINGS.each { String name ->
        def v = settings?.get(name)
        if (v == null) return
        if (name != "reportingInterval" && toDecimal(v) != 0) {
            logWarn "removed ${name} = ${v} left by the previous driver. This driver reads the plug's own units, so that correction would now be wrong. Use the adjustment settings if one is still needed."
        }
        device.removeSetting(name)
    }
    LEGACY_ATTRIBUTES.each { String name ->
        try {
            device.deleteCurrentState(name)
        } catch (Exception ignored) {
            // nothing stored under that name
        }
    }
    LEGACY_STATE.each { String key -> state.remove(key) }
}

/** Features not every firmware has: the standard power-on state and Shelly's light-level cluster. */
private List<String> optionalReads() {
    List<String> cmds = []
    cmds += zigbee.readAttribute(0x0006, 0x4003)                                          // StartUpOnOff
    cmds += zigbee.readAttribute(0x0B04, 0x0510)                                          // PowerFactor
    cmds += zigbee.readAttribute(0xFC21, [0x0000, 0x0001, 0x0002], [mfgCode: MFG_SHELLY])  // listed since fw 2.0.1
    return cmds
}

private void handlePowerOnState(String hex) {
    String v = POWER_ON_STATES[hex?.toUpperCase()]
    if (v == null) {
        logDebug "power-on state ${hex}"
        return
    }
    state.startUpOnOff = true
    if (device.currentValue("powerOnState") != v) logInfo "power-on state is ${v}"
    sendEvent(name: "powerOnState", value: v)
    Map pending = state.startUpPending as Map
    if (pending == null) return
    if (pending.value == v) {
        state.remove("startUpPending")
        unschedule("startUpTimeout")
        settingsOutcome("initial_state", v, "accepted", null)
    } else {
        startUpRefused("0006/4003 still reads ${v}")
    }
}

/** 0B04/0x0510 PowerFactor: int8, -100 to 100, shown as -1.00 to 1.00. */
private void handlePowerFactor(String hex, String encoding) {
    Long raw = decodeRaw(hex, encoding ?: "28", "int8")
    if (raw == null || raw < -100 || raw > 100) return
    state.powerFactorSupported = true
    BigDecimal pf = new BigDecimal(raw).divide(new BigDecimal(100), 2, BigDecimal.ROUND_HALF_UP)
    if (device.currentValue("powerFactor")?.toString() != pf.toString()) logInfo "power factor is ${pf}"
    sendEvent(name: "powerFactor", value: pf)
}

private void handleLightLevel(String attr, String hex, String encoding) {
    Long raw = decodeRaw(hex, encoding, "uint24")
    if (raw == null) return
    if (attr == "0000") {
        state.lightLevelSupported = true
        String v = LIGHT_LEVELS[raw as Integer]
        if (v == null) return
        if (device.currentValue("lightLevel") != v) logInfo "light level is ${v}"
        sendEvent(name: "lightLevel", value: v)
    } else {
        logInfo "light level ${attr == '0001' ? 'dark' : 'bright'} threshold: ${raw} lx"
    }
}

private List<String> scaleReads() {
    state.scaleAskedMs = now()
    List<String> cmds = []
    cmds += zigbee.readAttribute(0x0B04, [0x0600, 0x0601, 0x0602, 0x0603, 0x0604, 0x0605])
    cmds += zigbee.readAttribute(0x0B04, [0x0400, 0x0401])
    cmds += zigbee.readAttribute(0x0702, [0x0300, 0x0301, 0x0302])
    return cmds
}

/** Units were asked for within the last 5 minutes, so a missing answer means the plug has none. */
private boolean scaleAskedRecently() {
    Long asked = state.scaleAskedMs as Long
    return asked != null && now() - asked < 300000L
}

private boolean scaleComplete() {
    return QUANTITIES.keySet().every { String q -> scaleOf(q).source == "plug" }
}

/** The multiplier and divisor in use for a quantity: the plug's, or the 020000FF fallback. */
private Map scaleOf(String q) {
    Map s = ((state.scale ?: [:]) as Map)[q] as Map
    Long m = s?.mult as Long
    Long d = s?.div as Long
    if (m != null && d != null && m > 0 && d > 0) return [mult: m, div: d, source: "plug"]
    Map f = FALLBACK[q] as Map
    return [mult: f.mult as Long, div: f.div as Long, source: "default"]
}

private void warnFallback(String q) {
    List warned = (state.fallbackWarned ?: []) as List
    if (warned.contains(q)) return
    Map d = QUANTITIES[q] as Map
    Map f = FALLBACK[q] as Map
    logWarn "the plug has not reported its ${d.event} units (${String.format('%04X', d.cluster)}/${String.format('%04X', d.multAttr)}, ${String.format('%04X', d.divAttr)}); using x${f.mult}/${f.div}, measured on firmware 020000FF. If ${d.event} looks 10 or 100 times off, run Configure again or set an adjustment."
    state.fallbackWarned = warned + [q]
}

private BigDecimal adjust(String q, BigDecimal value) {
    switch (q) {
        case "power":     return applyPct(value, settings?.powerAdjustPct)
        case "voltage":   return applyPct(value, settings?.voltageAdjustPct)
        case "current":   return applyPct(value, settings?.currentAdjustPct)
        case "frequency": return value + toDecimal(settings?.frequencyOffsetHz)
        default:          return value
    }
}

private static BigDecimal applyPct(BigDecimal value, def pct) {
    BigDecimal p = toDecimal(pct)
    return p == 0 ? value : value * (1 + p / 100)
}

private static BigDecimal toDecimal(def v) {
    if (v == null) return 0
    try {
        return new BigDecimal(v.toString().trim())
    } catch (NumberFormatException ignored) {
        return 0
    }
}

private Integer reportMaxSecondsSetting() {
    BigDecimal v = toDecimal(settings?.reportMaxSeconds)
    if (v == 0) v = 600
    return Math.max(60, Math.min(3600, v.intValue()))
}

private BigDecimal powerChangeSetting() {
    BigDecimal v = toDecimal(settings?.powerChangeWatts)
    return v > 0 ? v : 5
}

private Integer healthTimeoutSeconds() {
    return reportMaxSecondsSetting() * 3 + 60
}

/** A reporting threshold in the plug's raw units: at least 1, at most what the attribute type holds. */
private static Integer rawChange(BigDecimal change, Map sc, String type) {
    BigDecimal raw = change * (sc.div as Long) / (sc.mult as Long)
    long r = Math.max(1L, raw.setScale(0, BigDecimal.ROUND_HALF_UP).longValue())
    long cap = type == "int16" ? 0x7FFFL : (type == "uint16" ? 0xFFFFL : 0x7FFFFFFFL)
    return Math.min(r, cap) as Integer
}

private static Integer dataTypeOf(String type) {
    switch (type) {
        case "int16":  return DataType.INT16
        case "uint48": return DataType.UINT48
        default:       return DataType.UINT16
    }
}

/** [q: quantity, role: value|mult|div] for a cluster/attribute pair, or null. */
private static Map lookupAttribute(String cluster, String attr) {
    for (e in QUANTITIES) {
        Map d = e.value as Map
        if (String.format("%04X", d.cluster) != cluster) continue
        if (String.format("%04X", d.attr) == attr) return [q: e.key, role: "value"]
        if (String.format("%04X", d.multAttr) == attr) return [q: e.key, role: "mult"]
        if (String.format("%04X", d.divAttr) == attr) return [q: e.key, role: "div"]
    }
    return null
}

/**
 * A raw attribute value from its big-endian hex string. Signed when the ZCL encoding says so (0x28 to
 * 0x2F), or, without an encoding, when the quantity's type is signed. Null for the ZCL "invalid" value
 * (all ones unsigned, the minimum signed) and for anything that is not hex.
 */
private static Long decodeRaw(String hex, String encoding, String type) {
    if (hex == null) return null
    String h = hex.trim().toUpperCase()
    if (!(h ==~ /[0-9A-F]{1,15}/)) return null
    Integer enc = null
    try {
        enc = encoding ? Integer.parseInt(encoding, 16) : null
    } catch (NumberFormatException ignored) {
        enc = null
    }
    boolean signed = enc != null ? (enc >= 0x28 && enc <= 0x2F) : type.startsWith("int")
    int bits = h.length() * 4
    if (enc != null && enc >= 0x28 && enc <= 0x2F) bits = (enc - 0x27) * 8
    else if (enc != null && enc >= 0x20 && enc <= 0x27) bits = (enc - 0x1F) * 8
    if (bits < h.length() * 4) bits = h.length() * 4
    long v = Long.parseLong(h, 16)
    if (signed) {
        long sign = 1L << (bits - 1)
        if (v == sign) return null
        if ((v & sign) != 0) v -= (1L << bits)
    } else if (v == (1L << bits) - 1) {
        return null
    }
    return v
}

/** A ZCL character string: Hubitat usually hands it over decoded, sometimes as hex of the characters. */
private static String decodeCharString(String value, String encoding) {
    if (value == null) return null
    String v = value.trim()
    if (encoding?.toUpperCase() == "42" && v.length() >= 2 && v.length() % 2 == 0 && v ==~ /[0-9A-Fa-f]+/) {
        StringBuilder sb = new StringBuilder()
        for (int i = 0; i < v.length(); i += 2) {
            int c = Integer.parseInt(v.substring(i, i + 2), 16)
            if (c < 0x20 || c > 0x7E) return v
            sb.append((char) c)
        }
        return sb.toString()
    }
    return v
}

/** Attribute records from a catchall payload (read response: id, status, type, value; report: id, type, value). */
private static List<Map> decodeAttributeRecords(List<String> d, boolean withStatus) {
    List<Map> out = []
    int i = 0
    while (i + 1 < d.size()) {
        String attrId = (d[i + 1] + d[i]).toUpperCase()
        i += 2
        String status = "00"
        if (withStatus) {
            if (i >= d.size()) break
            status = d[i++].toUpperCase()
            if (status != "00") {
                out << [attrId: attrId, status: status]
                continue
            }
        }
        if (i >= d.size()) break
        String encoding = d[i++].toUpperCase()
        int size = zclTypeSize(Integer.parseInt(encoding, 16))
        if (size == 0 && encoding == "42" && i < d.size()) {
            int len = Integer.parseInt(d[i++], 16)
            int end = Math.min(d.size(), i + len)     // Hubitat may hand over fewer bytes than declared
            out << [attrId: attrId, status: status, encoding: encoding, value: d.subList(i, end).join(""), partial: end < i + len]
            i = end
            continue
        }
        if (size == 0 || i + size > d.size()) break
        // Little-endian on the air; Hubitat's decoded values are big-endian hex.
        out << [attrId: attrId, status: status, encoding: encoding, value: d.subList(i, i + size).reverse().join("").toUpperCase()]
        i += size
    }
    return out
}

private static int zclTypeSize(int type) {
    if (type == 0x10 || type == 0x18 || type == 0x20 || type == 0x28 || type == 0x30) return 1
    if (type == 0x19 || type == 0x21 || type == 0x29 || type == 0x31) return 2
    if (type >= 0x20 && type <= 0x27) return type - 0x1F
    if (type >= 0x28 && type <= 0x2F) return type - 0x27
    return 0
}

private static String zclStatus(String hex) {
    switch (hex?.toUpperCase()) {
        case "00": return "success"
        case "86": return "unsupported attribute"
        case "C3": return "unsupported cluster"
        case "84": return "unsupported manufacturer-specific command"
        case "8C": return "unreportable attribute"
        case "8D": return "invalid data type"
        case "87": return "invalid value"
        case "81": return "unsupported command"
        default:   return "status 0x${hex}"
    }
}

private void sendZigbee(List<String> cmds) {
    if (cmds) sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
}

private void logDebug(String msg) {
    if (logEnable) log.debug "${device.displayName}: ${msg}"
}

private void logWarn(String msg) {
    log.warn "${device.displayName}: ${msg}"
}

private void logInfo(String msg) {
    if (txtEnable != false) log.info msg.startsWith(device.displayName as String) ? msg : "${device.displayName}: ${msg}"
}
