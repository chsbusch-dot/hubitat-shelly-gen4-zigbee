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
 * Shelly's RPC channel over Zigbee (cluster 0xFC01) is used only to write optional settings (power
 * limit, power-on state, Bluetooth); readings never depend on it, because Hubitat cuts its replies short.
 * Shelly's Wi-Fi setup cluster (0xFC02) gives the plug a network, which its firmware updates need.
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
 * 1.2.0  2026-10-15  setBluetooth command, for the Shelly app (Wi-Fi setup, firmware updates); RPC
 *                    request chunks wait for the plug's acknowledgement, an unanswered write is asked
 *                    once more (WOR-780)
 * 1.3.0  2026-10-15  setWifi command, wifiStatus and wifiIp, over Shelly's Wi-Fi setup cluster (WOR-781)
 * 1.3.1  2026-10-15  setWifi no longer writes DHCP (read-only on the plug, which refused the whole setup);
 *                    0.0.0.0 counts as no Wi-Fi address; wifiNetwork shows the network the plug is set to join
 * 1.3.2  2026-10-15  an RPC reply cut at the key after src (two-digit request id) is judged from its tail (WOR-784)
 * 1.4.0  2026-10-15  LED ring colour, mode and night mode as preferences (PLUGS_UI over RPC). Review fixes: a quick trip
 *                    needs a hub-sent on or some load; a write that never finished expires; an RPC retry keeps its id and
 *                    chunks before the first poll are ignored; a firmware change is noticed once; Configure forgets the
 *                    Bluetooth attribute; more ZCL types decoded (WOR-786)
 * 1.4.1  2026-10-15  Review fixes: a cutoff is re-checked against the load before every attempt and not sent while
 *                    the draw is unknown; Wi-Fi setup waits for a quiet channel and ignores stray acknowledgements;
 *                    a feature reported late gets its reporting; a refused power-on write stays on RPC; a trip under
 *                    load uses the recent load; an empty Wi-Fi password clears the old one; a firmware change probes
 *                    the optional features again; network names decode as UTF-8 (WOR-793)
 * 1.4.2  2026-10-15  a 0 W report right before an off no longer hides a trip; a stale Wi-Fi error no longer cancels a
 *                    setup that has not started yet (WOR-793)
 * 1.4.3  2026-10-15  trips judged on the recent load and the confirmed cutoff; a tail-only RPC success must match the
 *                    reply length; a cutoff needs a power reading since the last switch-on, in the plug's own watts;
 *                    default responses to reads are not refusals; each acknowledged chunk restarts the write timeout;
 *                    only 00 and 01 are switch states (WOR-793)
 * 1.4.4  2026-10-15  Fourth review: the cutoff guard runs again before every chunk and needs a reading from the current
 *                    reporting period; a preference saved while an old write was out is not skipped with it; the driver
 *                    sends its Wi-Fi reads itself, so the quiet window is measured from the real send; an RPC reply is
 *                    judged by its opening, by its tail only with the reply length, and again when the length comes last;
 *                    trips keep a few readings, so two 0 W reports still show the load; Configure keeps the confirmed
 *                    cutoff; Bluetooth off also clears rpc.enable; a default response refusing the power-on write falls
 *                    back to RPC; each Wi-Fi frame gets its own 30 s (WOR-793)
 */

import groovy.json.JsonOutput
import groovy.transform.Field
import hubitat.zigbee.zcl.DataType

static String driverVersion() { "1.4.4" }

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
        attribute "wifiStatus",   "string"
        attribute "wifiIp",       "string"
        attribute "wifiNetwork",  "string"

        command "identify", [[name: "seconds", type: "NUMBER", description: "How long the LED blinks (1 to 600, default 10)"]]
        command "setBluetooth", [[name: "state", type: "ENUM", constraints: ["on", "off"],
                                  description: "Bluetooth on the plug; the Shelly app needs it for Wi-Fi setup and firmware updates"]]
        command "setWifi", [[name: "network*", type: "STRING", description: "Wi-Fi network name (SSID)"],
                            [name: "password", type: "STRING", description: "Wi-Fi password, empty for an open network. Sent to the plug only; the driver does not log or store it"]]

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
        input name: "ledMode", type: "enum", title: "LED ring",
              options: ["keep": "Keep the plug's own setting", "power": "Colour follows the power draw", "switch": "One colour when on, another when off", "off": "Off"],
              defaultValue: "keep"
        input name: "ledOnColor", type: "enum", title: "LED colour when on (one colour mode)",
              options: ["keep": "Keep", "red": "Red", "green": "Green", "blue": "Blue", "yellow": "Yellow", "orange": "Orange", "purple": "Purple", "cyan": "Cyan", "white": "White"],
              defaultValue: "keep"
        input name: "ledOffColor", type: "enum", title: "LED colour when off (one colour mode)",
              options: ["keep": "Keep", "red": "Red", "green": "Green", "blue": "Blue", "yellow": "Yellow", "orange": "Orange", "purple": "Purple", "cyan": "Cyan", "white": "White"],
              defaultValue: "keep"
        input name: "ledBrightness", type: "number", title: "LED brightness (%)", range: "1..100",
              description: "Leave empty to keep the plug's own setting."
        input name: "ledNightMode", type: "enum", title: "LED at night",
              options: ["keep": "Keep the plug's own setting", "on": "Dim or switch off the LED between the hours below", "off": "No night mode"],
              defaultValue: "keep"
        input name: "ledNightFrom", type: "text", title: "Night starts (HH:MM)", defaultValue: "22:00"
        input name: "ledNightTo", type: "text", title: "Night ends (HH:MM)", defaultValue: "06:00"
        input name: "ledNightBrightness", type: "number", title: "LED brightness at night (%)", range: "0..100",
              description: "0 switches the LED off at night.", defaultValue: 10
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
// A reading this far below the load before it is a drop. A drop within the grace time of an off is the plug
// cutting its load (a protection trip reports 0 W, once or more, right before off), so the load before it counts.
@Field static final BigDecimal TRIP_PEAK_DECAY = 0.5
@Field static final Long TRIP_DROP_GRACE_MS = 15000L
@Field static final Integer WATTS_HISTORY = 6                                   // recent power readings kept for that
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
// A firmware change shows twice, seconds apart: the plug's build string, then the hub's firmwareMT catching up.
@Field static final Long FIRMWARE_SETTLE_MS = 300000L
// LED ring colours for the one-colour mode, as PLUGS_UI wants them: red, green and blue in percent.
@Field static final Map LED_RGB = [red: [100, 0, 0], green: [0, 100, 0], blue: [0, 0, 100], yellow: [100, 100, 0], orange: [100, 50, 0],
                                   purple: [60, 0, 100], cyan: [0, 100, 100], white: [100, 100, 100]]

// Shelly's Wi-Fi setup over Zigbee: cluster FC02 on endpoint 0xEF, profile 0xC001 (Shelly's Zigbee docs,
// Zigbee2MQTT's shellyWiFiSetup). Writes stage the station settings, Action 1 applies them and Action 0
// resets the staged values to what the plug runs; Status and IP are character strings. Firmware updates
// come over Wi-Fi only: Zigbee2MQTT offers no Zigbee OTA for this plug.
@Field static final Integer WIFI_TIMEOUT_SECONDS = 30
@Field static final Integer WIFI_READS = 4
// A write response on FC02 names no attribute, so a setup starts only once the channel has been quiet this long.
@Field static final Long WIFI_QUIET_MS = 10000L
// The frames of a setWifi carry the password, so they wait here, in memory, while the plug acknowledges
// them one by one: never in state, which the device page shows and the hub stores.
@Field static final java.util.concurrent.ConcurrentHashMap<String, List<String>> WIFI_FRAMES = new java.util.concurrent.ConcurrentHashMap<String, List<String>>()
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
    state.remove("prevWatts")                                               // 1.4.3 kept one earlier reading; watts keeps a few
    // After the reporting setup's radio traffic; only settings the user filled in are ever sent.
    state.remove("settingsSkip")
    runIn(30, "applyPlugSettings")
}

/** Reads the plug's units, then sets up reporting from them. Never switches the relay. */
List<String> configure() {
    log.info "${device.displayName}: configuring (driver ${driverVersion()}); the relay is not switched"
    migrateFromOtherDrivers()
    state.remove("fallbackWarned")
    ["plugSettings", "settingsSkip", "startUpOnOff", "startUpWritable", "powerFactorSupported", "lightLevelSupported", "wifiSupported"].each { state.remove(it) }
    try {
        device.deleteCurrentState("bluetooth")        // what the driver last sent, not what a reset plug has
    } catch (Exception ignored) {
        // nothing stored under that name
    }
    scheduleHousekeeping()
    List<String> cmds = []
    cmds += zigbee.readAttribute(0x0000, 0x4000)      // software build, to notice a firmware change
    cmds += scaleReads()
    cmds += optionalReads()
    // The scale answers arrive within seconds; reporting thresholds are raw units, so wait for them.
    runIn(8, "applyReporting")
    runIn(3, "wifiRead")                              // sent by the driver, not queued here: its Action 0 is timed (setWifi)
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
    if (state.wifiSupported != false) runIn(1, "wifiRead")          // sent by the driver, so its Action 0 is timed (setWifi)
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
    dropStalePending()
    if (state.rpcPending != null || state.startUpPending != null) {
        runIn(30, "applyPlugSettings")                 // one write at a time
        return
    }
    Map todo = pendingPlugSettings()
    if (!todo) return
    if (todo.power_limit != null) {
        String blocker = powerLimitBlocker(todo.power_limit as Integer)
        if (blocker) {
            settingsOutcome("power_limit", todo.power_limit, "not sent", blocker)
            return
        }
    }
    if (todo.initial_state != null && state.startUpOnOff == true && state.startUpWritable != false) {
        logInfo "setting the power-on state to ${todo.initial_state} (0006/4003)"
        state.startUpPending = [value: todo.initial_state, at: now()]
        List<String> cmds = []
        cmds += zigbee.writeAttribute(0x0006, 0x4003, DataType.ENUM8, START_UP_ON_OFF[todo.initial_state] as Integer)
        cmds += zigbee.readAttribute(0x0006, 0x4003)   // the read-back confirms it (handlePowerOnState)
        sendZigbee(cmds)
        runIn(RPC_TIMEOUT_SECONDS, "startUpTimeout")
        return
    }
    if (todo.initial_state != null || todo.power_limit != null) {
        String key = todo.initial_state != null ? "initial_state" : "power_limit"
        def value = key == "initial_state" ? RPC_INITIAL_STATE[todo.initial_state] : todo.power_limit
        sendRpc("Switch.SetConfig", [id: 0, config: [(key): value]], key, todo[key])
        return
    }
    sendRpc("PLUGS_UI.SetConfig", [config: [leds: todo.led]], "led", todo.led)
}

/**
 * Turns the plug's Bluetooth on or off through Shelly's RPC (BLE.SetConfig); "on" also enables RPC over
 * Bluetooth, which the Shelly app uses to reach a plug in Zigbee mode (Wi-Fi setup, firmware updates).
 * The relay is not touched.
 */
void setBluetooth(String onOff) {
    String v = onOff?.toString()?.toLowerCase() == "off" ? "off" : "on"
    dropStalePending()
    if (state.rpcPending != null || state.startUpPending != null) {
        settingsOutcome("bluetooth", v, "not sent", "another write is in progress; try again in a minute", false)
        return
    }
    logInfo "turning Bluetooth ${v}"
    // rpc.enable is the field current firmware honours (the top-level enable went with 2.0.0); both are sent either way.
    Map config = v == "on" ? [enable: true, rpc: [enable: true]] : [enable: false, rpc: [enable: false]]
    sendRpc("BLE.SetConfig", [config: config], "bluetooth", v)
}

/** Why a cutoff must not go out right now, or null: the plug draws near or above it, or its draw is unknown or old. */
private String powerLimitBlocker(Integer limit) {
    if (limit >= PLUG_MAX_WATTS) return null                                   // the maximum cannot undercut a load
    Long lastMs = state.lastWattsMs as Long
    if (lastMs == null) return "the plug has not reported its power draw yet; refresh, then save again"
    // The plug reports power at least once per longest interval; a reading older than that says nothing about now.
    long age = now() - lastMs
    if (age > reportMaxSecondsSetting() * 1000L + 60000L) return "the plug's last power reading is ${Math.round(age / 60000.0)} min old; refresh, then save again"
    Long onAt = state.onAtMs as Long
    if (device.currentValue("switch") == "on" && onAt != null && lastMs < onAt) return "no power reading since the plug was switched on; refresh, then save again"
    BigDecimal drawing = toDecimal(state.lastWatts)                            // the plug's own watts, not the adjusted display
    if (drawing >= limit * TRIP_LOAD_SHARE) return "the plug is drawing ${drawing} W and would switch itself off"
    return null
}

/**
 * The cutoff the plug confirmed, or its maximum; a preference it refused or has not received yet does not count.
 * Configure forgets the settings so that they are sent again, but not the cutoff: the plug still runs it.
 */
private BigDecimal acceptedPowerLimit() {
    def accepted = ((state.plugSettings ?: [:]) as Map).power_limit ?: state.cutoffWatts
    return accepted != null ? toDecimal(accepted) : PLUG_MAX_WATTS
}

/** Why a settings write must not go on (its next chunk, its retry), or null: the preference moved on, or the load forbids the cutoff now. */
private String retryBlocker(Map pending) {
    String key = pending.key as String
    if (key == "bluetooth") return null
    if (pendingPlugSettings()[key] != pending.value) return "the preference changed in the meantime"
    if (key == "power_limit") return powerLimitBlocker(pending.value as Integer)
    return null
}

/** Preferences that differ from what the plug accepted, minus those already refused this round. */
private Map pendingPlugSettings() {
    Map wanted = [:]
    String onState = settings?.powerOnState as String
    if (onState && RPC_INITIAL_STATE[onState]) wanted.initial_state = onState
    BigDecimal limit = toDecimal(settings?.powerLimitWatts)
    if (limit > 0) wanted.power_limit = Math.max(POWER_LIMIT_MIN, Math.min(PLUG_MAX_WATTS, limit.intValue()))
    Map leds = ledSettings()
    if (leds) wanted.led = leds
    Map done = (state.plugSettings ?: [:]) as Map
    List skip = (state.settingsSkip ?: []) as List
    return wanted.findAll { k, v -> done[k] != v && !skip.contains(k) }
}

/** The PLUGS_UI "leds" settings the preferences ask for; empty when every LED preference says keep. */
private Map ledSettings() {
    Map leds = [:]
    String mode = settings?.ledMode as String
    if (mode && mode != "keep") leds.mode = mode
    Map sw = [:]
    ["on", "off"].each { String s ->
        String c = settings?.get("led${s.capitalize()}Color".toString()) as String
        if (c && LED_RGB[c]) sw[s] = [rgb: LED_RGB[c]]
    }
    int bright = Math.min(100, toDecimal(settings?.ledBrightness).intValue())
    if (bright > 0) ["on", "off"].each { String s -> sw[s] = ((sw[s] ?: [:]) as Map) + [brightness: bright] }
    Map colors = [:]
    if (sw) colors["switch:0"] = sw
    if (bright > 0) colors.power = [brightness: bright]
    if (colors) leds.colors = colors
    String night = settings?.ledNightMode as String
    if (night == "off") {
        leds.night_mode = [enable: false]
    } else if (night == "on") {
        int nightBright = Math.max(0, Math.min(100, toDecimal(settings?.ledNightBrightness).intValue()))
        leds.night_mode = [enable: true, brightness: nightBright, active_between: [timeOf(settings?.ledNightFrom, "22:00"), timeOf(settings?.ledNightTo, "06:00")]]
    }
    return leds
}

/** "HH:MM", or the fallback when the setting is not a time of day. */
private static String timeOf(def v, String fallback) {
    String s = v?.toString()?.trim()
    return (s && s ==~ /([01]\d|2[0-3]):[0-5]\d/) ? s : fallback
}

/**
 * A write whose timeout job never fired (the hub restarted, the driver was updated mid-write) must not
 * block later writes forever: a pending write older than its timeouts allow is forgotten.
 */
private void dropStalePending() {
    [rpcPending: RPC_TIMEOUT_SECONDS * 3, startUpPending: RPC_TIMEOUT_SECONDS * 2, wifiPending: WIFI_TIMEOUT_SECONDS * 2].each { String key, int seconds ->
        Map pending = state.get(key) as Map
        if (pending == null) return
        Long at = pending.at as Long
        if (at != null && now() - at <= seconds * 1000L) return
        logWarn "forgetting a ${key == 'wifiPending' ? 'Wi-Fi' : 'settings'} write that never finished"
        state.remove(key)
        if (key == "wifiPending") WIFI_FRAMES.remove(device.deviceNetworkId as String)
    }
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
    state.startUpWritable = false                  // it still reads fine; handlePowerOnState must not undo this
    settingsOutcome("initial_state", pending.value, "refused", "${detail}; trying RPC", false)
}

/** Records one setting's outcome and moves on to the next pending setting, if any. */
private void settingsOutcome(String key, value, String result, String detail, boolean skipOnFail = true) {
    String what = key == "initial_state" ? "power-on state ${value}" : (key == "bluetooth" ? "Bluetooth ${value}" : (key == "led" ? "LED settings" : "power limit ${value} W"))
    String text = "${what}: ${result}${detail ? ' (' + detail + ')' : ''}"
    if (result == "accepted") {
        state.plugSettings = ((state.plugSettings ?: [:]) as Map) + [(key): value]
        if (key == "power_limit") state.cutoffWatts = value                 // outlives Configure: the plug keeps running it
        if (key == "bluetooth") sendEvent(name: "bluetooth", value: value)
        logInfo text
    } else {
        logWarn text
        // Skipped until the next Save, but only while the preference still asks for the value that failed: one
        // saved while the old write was still out must not inherit its failure.
        List skip = (state.settingsSkip ?: []) as List
        if (skipOnFail && pendingPlugSettings()[key] == value) state.settingsSkip = skip + [key]
    }
    sendEvent(name: "lastSettingsWrite", value: text)
    runIn(2, "applyPlugSettings")
}

/**
 * One RPC request: its length to TxCtl, then its JSON text to TxData in chunks of RPC_CHUNK_BYTES
 * (one Zigbee frame holds about 80 bytes; Zigbee2MQTT writes 40 at a time), then polls for the reply.
 * Only TxCtl goes out here; rpcWriteAcked sends each chunk once the plug has acknowledged the write before.
 */
private void sendRpc(String method, Map params, String key, value, int attempt = 1, Integer sameId = null) {
    int id = sameId != null ? sameId : (((state.rpcId ?: 0) as int) % 99) + 1
    state.rpcId = id
    byte[] bytes = JsonOutput.toJson([id: id, method: method, params: params]).getBytes("UTF-8")
    List<String> chunks = []
    for (int off = 0; off < bytes.length; off += RPC_CHUNK_BYTES) {
        int n = Math.min(RPC_CHUNK_BYTES, bytes.length - off)
        chunks << "0000" + "42" + String.format("%02X", n) + hexOf(bytes, off, n)                    // TxData chunk
    }
    state.rpcPending = [id: id, polls: 0, key: key, value: value, reply: "", method: method, params: params, attempt: attempt,
                        chunks: chunks, sent: 0, at: now()]
    logDebug "RPC ${method} ${params}"
    runIn(RPC_TIMEOUT_SECONDS, "rpcTimeout")
    sendZigbee([rpcFrame("02", "0100" + "23" + le32(bytes.length))])                                // TxCtl: length
}

/**
 * The plug acknowledged one write of the request: send the next chunk, or after the last one read the
 * reply. One chunk at a time, because the plug joins chunks in the order they arrive and the mesh can
 * reorder frames sent close together: on 2026-10-15 4612 answered chunks sent 150 ms apart with error
 * -103, the request's id and method intact and its config scrambled. A lost acknowledgement stalls the
 * request until rpcTimeout asks again.
 */
private void rpcWriteAcked() {
    Map pending = state.rpcPending as Map
    if (pending?.chunks == null) return
    List<String> chunks = pending.chunks as List<String>
    int sent = (pending.sent ?: 0) as int
    if (sent > chunks.size()) return                                            // a duplicate acknowledgement
    pending.sent = sent + 1
    pending.at = now()                                                          // progress: the write is alive
    state.rpcPending = pending
    if (sent < chunks.size()) {
        // What held when the request was built may not hold now: the load can rise and a preference can change
        // while the chunks go out one acknowledgement at a time. The plug is left with part of a request; the
        // next TxCtl starts a new one.
        String blocker = retryBlocker(pending)
        if (blocker) {
            rpcResult("not sent", blocker)
            return
        }
        sendZigbee([rpcFrame("02", chunks[sent])])
        runIn(RPC_TIMEOUT_SECONDS, "rpcTimeout")                                // each acknowledged chunk restarts the clock
    } else {
        runIn(2, "rpcPoll")
        runIn(RPC_TIMEOUT_SECONDS, "rpcTimeout")                                // the polls get their full time
    }
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
 * No readable answer: these requests are idempotent (the same setting written twice), so ask once more,
 * under the same request id so that a late answer to the first try still counts, before calling it
 * unconfirmed. On 2026-10-15 one of five live writes needed exactly that.
 */
void rpcTimeout() {
    Map pending = state.rpcPending as Map
    if (pending == null) return
    if (((pending.attempt ?: 1) as int) < RPC_ATTEMPTS) {
        String reason = retryBlocker(pending)
        if (reason) {
            rpcResult("not sent", reason)
            return
        }
        logDebug "RPC ${pending.method}: no readable answer, asking once more"
        unschedule("rpcPoll")
        sendRpc(pending.method as String, pending.params as Map, pending.key as String, pending.value, ((pending.attempt ?: 1) as int) + 1, pending.id as Integer)
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
    return shellyFrame("0xFC01", command, body)
}

/** A manufacturer-specific ZCL frame to one of Shelly's clusters on endpoint 0xEF (profile 0xC001). */
private String shellyFrame(String cluster, String command, String body) {
    int seq = (((state.zclSeq ?: 0) as int) + 1) & 0xFF   // ZCL transaction number, not the RPC id
    state.zclSeq = seq
    String mfg = String.format("%02X%02X", MFG_SHELLY & 0xFF, (MFG_SHELLY >> 8) & 0xFF)
    return "he raw 0x${device.deviceNetworkId} 1 0xEF ${cluster} {04${mfg}${String.format('%02X', seq)}${command}${body}} {0xC001}".toString()
}

/** FC01 frames: write and default responses to the request, read responses with the reply. */
private void parseRpc(Map descMap) {
    String command = (descMap.command as String)?.toUpperCase()
    List<String> d = (descMap.data ?: []) as List<String>
    if (command == "04") {
        if (d && d[0] != "00") {
            rpcResult("refused", "the plug rejected the request frame: ${zclStatus(d[0])}")
        } else {
            rpcWriteAcked()
        }
        return
    }
    if (command == "0B") {
        if (d.size() >= 2 && d[1] != "00") {
            if (d[0] == "02") rpcResult("refused", "the plug answered ${zclStatus(d[1])}")   // to a write of ours
            else logDebug "RPC: command ${d[0]} answered ${zclStatus(d[1])}"              // a read; the next poll asks again
        }
        return
    }
    if (command != "01" && command != "0A") return
    decodeAttributeRecords(d, command == "01").each { Map r ->
        if (r.status != "00") {
            logDebug "RPC ${r.attrId}: ${zclStatus(r.status as String)}"
        } else if (r.attrId == "0002" && r.value) {
            long len = Long.parseLong(r.value as String, 16)
            logDebug "RPC reply length ${len}"
            Map pending = state.rpcPending as Map
            if (pending != null && len > 0 && ((pending.polls ?: 0) as int) > 0) {   // before the first poll it is an earlier reply's
                pending.replyLength = len
                state.rpcPending = pending
                judgeRpcReply()                                               // the text may have come first
            }
        } else if (r.attrId == "0000") {
            handleRpcChunk(hexToText(r.value as String))
        }
    }
}

/**
 * Collects reply chunks. They can arrive out of order (2026-10-15: the 30-byte tail of an 80-byte reply
 * reached the driver before its 50-byte opening), so the opening is kept apart from the rest.
 */
private void handleRpcChunk(String text) {
    Map pending = state.rpcPending as Map
    if (pending == null || !text) return
    if (((pending.polls ?: 0) as int) == 0) {
        logDebug "RPC chunk before this request's first poll (a late or repeated answer to an earlier one), ignored: ${text}"
        return
    }
    if (text.startsWith('{"id":')) {
        pending.head = text
    } else {
        String tail = ((pending.tail ?: "") as String) + text
        pending.tail = tail.length() > 300 ? tail.substring(0, 300) : tail
    }
    state.rpcPending = pending
    if (!pending.head) logDebug "RPC reply tail before its opening: ${text}"
    judgeRpcReply()
}

/**
 * Judges what has arrived of the reply. The opening decides: the request id, then "error" or "result".
 * Hubitat hands over 46 of its 50 bytes, so with a one-digit request id it still ends on the r of result
 * or the e of error, right after src (4612's -103 arrived as ...","e), while with a two-digit id it ends
 * on the quote before them (2026-10-15, id 14) and says nothing. Then the plug's reply length tells with
 * the tail: a SetConfig success has exactly one length for this id and plug, anything else is an error.
 * A tail never decides on its own, and never against the opening: a read answered late carries the
 * previous reply's tail into this request (fourth review).
 */
private void judgeRpcReply() {
    Map pending = state.rpcPending as Map
    String head = pending?.head as String
    if (!head) return
    def id = head =~ /^\{"id":(\d+)[,}]/
    if (!id.find()) return
    if ((id.group(1) as Integer) != (pending.id as Integer)) {
        logDebug "RPC reply ${id.group(1)} is not ours (${pending.id})"
        pending.remove("head")
        pending.remove("tail")
        state.rpcPending = pending
        return
    }
    String tail = (pending.tail ?: "") as String
    boolean refused = head.contains('"error"') || (head =~ /"src":"[^"]*","e/).find()
    boolean accepted = head.contains('"result"') || (head =~ /"src":"[^"]*","r/).find()
    if (!refused && !accepted && !(head =~ /"src":"[^"]*","/).find()) {
        // Cut inside src (a longer device name than this plug's): no length can be worked out, so the text as a whole tells.
        refused = (head + tail).contains('"error"')
        accepted = (head + tail).contains('"result"')
    } else if (!refused && !accepted && pending.replyLength != null) {
        if (tail.contains('restart_required') && successLengthMatches(head, pending)) accepted = true
        else if (tail.contains('"code":') && !successLengthMatches(head, pending)) refused = true
    }
    if (refused) {
        def code = (head + tail) =~ /"code":(-?\d+)/
        rpcResult("refused", code.find() ? "error ${code.group(1)}" : "error, its code cut off")
    } else if (accepted) {
        rpcResult("accepted", null)
    } else {
        logDebug "RPC reply not judged yet: ${head}${tail}"
    }
}

/**
 * Whether the reply length the plug reported is exactly what a SetConfig success for this request id and this
 * plug measures (restart_required false or true). An error reply is longer, and a stale tail of an earlier reply
 * must not confirm the request after it (2026-10-15 review).
 */
private static boolean successLengthMatches(String head, Map pending) {
    Long len = pending.replyLength as Long
    def id = head =~ /^\{"id":(\d+)/
    def src = head =~ /"src":"([^"]*)"/
    if (len == null || !id.find() || !src.find()) return false
    int success = '{"id":'.length() + id.group(1).length() + ',"src":"'.length() + src.group(1).length() + '","result":{"restart_required":false}}'.length()
    return len == success || len == success - 1
}

private static String le32(int n) {
    return String.format("%02X%02X%02X%02X", n & 0xFF, (n >> 8) & 0xFF, (n >> 16) & 0xFF, (n >> 24) & 0xFF)
}

private static String hexOf(byte[] bytes, int off, int n) {
    StringBuilder sb = new StringBuilder()
    for (int k = off; k < off + n; k++) sb.append(String.format("%02X", bytes[k] & 0xFF))
    return sb.toString()
}

/** Raw bytes as UTF-8 text; a cut-short reply ends in garbage, which must not hide its start. */
private static String hexToText(String hex) {
    String h = hex ?: ""
    byte[] b = new byte[h.length().intdiv(2)]
    for (int k = 0; k + 1 < h.length(); k += 2) b[k.intdiv(2)] = (byte) Integer.parseInt(h.substring(k, k + 2), 16)
    return new String(b, "UTF-8")
}

// --------------------------------------------------------------------------- Wi-Fi (cluster FC02)

/**
 * Gives the plug a Wi-Fi network through Shelly's Wi-Fi setup cluster, so it can fetch firmware updates
 * (from the Shelly app or the plug's own web page, once it has an address). The password goes to the
 * plug and nowhere else: no log line, event or state holds it. An empty password is an open network.
 * Each write waits for the plug to acknowledge the one before; Action 1 goes last. The relay is not touched.
 */
void setWifi(String network, String password = null) {
    byte[] name = (network ?: "").getBytes("UTF-8")
    byte[] pass = (password ?: "").getBytes("UTF-8")
    dropStalePending()
    if (state.wifiPending != null) {
        wifiOutcome(network, "not sent", "another Wi-Fi write is in progress; try again in a minute")
        return
    }
    if (name.length < 1 || name.length > 32) {
        wifiOutcome(network, "not sent", "the network name must be 1 to 32 bytes")
        return
    }
    if (pass.length != 0 && (pass.length < 8 || pass.length > 63)) {
        wifiOutcome(network, "not sent", "a password has 8 to 63 characters; leave it empty for an open network")
        return
    }
    // Enable and SSID, as Zigbee2MQTT writes them. Not DHCP: 4613 answered READ_ONLY (0x88) for 0x0003 on
    // 2026-10-15, and the plug uses DHCP unless given a static address.
    List<String> frames = [wifiWrite(boolRecord(0x0004, true) + textRecord(0x0005, name))]
    frames << wifiWrite(textRecord(0x0006, pass))                     // the password; an empty one clears what the plug holds
    frames << wifiWrite(attrLe(0x0002) + "20" + "01")                 // Action 1: apply
    WIFI_FRAMES.put(device.deviceNetworkId as String, new ArrayList<String>(frames))
    // A write response names no attribute, so wait until no Wi-Fi write of ours (a refresh's Action 0, an
    // earlier setup) can still be answered, and count only acknowledgements earned after the first frame.
    Long busy = state.wifiBusyMs as Long
    long waitMs = busy == null ? 0L : Math.max(0L, busy + WIFI_QUIET_MS - now())
    int waitSeconds = waitMs == 0L ? 0 : ((int) ((waitMs + 999L) / 1000L)) + 1
    state.wifiPending = [network: network, at: now(), sent: 0]
    logInfo "sending Wi-Fi network ${network} to the plug${waitSeconds ? ' in ' + waitSeconds + ' s' : ''}"
    runIn(WIFI_TIMEOUT_SECONDS + waitSeconds, "wifiTimeout")
    if (waitSeconds) runIn(waitSeconds, "wifiStart") else wifiStart()
}

/** Sends the first frame of a staged setup. */
void wifiStart() {
    Map pending = state.wifiPending as Map
    if (pending == null || ((pending.sent ?: 0) as int) > 0) return
    if (!wifiSendNext(pending)) return                               // the hub restarted mid-way; wifiTimeout reports it
}

/** Sends the next staged frame; false when none is staged. */
private boolean wifiSendNext(Map pending) {
    String key = device.deviceNetworkId as String
    List<String> rest = WIFI_FRAMES.get(key)
    if (rest == null || rest.isEmpty()) return false
    WIFI_FRAMES.put(key, new ArrayList<String>(rest.subList(1, rest.size())))
    pending.sent = ((pending.sent ?: 0) as int) + 1
    state.wifiPending = pending
    state.wifiBusyMs = now()
    sendZigbee([rest[0]])
    runIn(WIFI_TIMEOUT_SECONDS, "wifiTimeout")                        // each frame gets its own time to be acknowledged
    return true
}

/** The plug acknowledged one Wi-Fi write: send the next one, or after Action 1 read back how it connects. */
private void wifiWriteAcked() {
    Map pending = state.wifiPending as Map
    if (pending == null) return
    if (((pending.sent ?: 0) as int) == 0) {
        logDebug "a Wi-Fi write was acknowledged before this setup sent anything (an earlier write), ignored"
        return
    }
    String key = device.deviceNetworkId as String
    List<String> rest = WIFI_FRAMES.get(key)
    if (rest == null) return                                          // the hub restarted mid-way; wifiTimeout reports it
    if (!rest.isEmpty()) {
        wifiSendNext(pending)
        return
    }
    wifiFinish("accepted", "wifiStatus and wifiIp show when it has joined")
    state.wifiReadsLeft = WIFI_READS
    runIn(10, "wifiRead")
}

void wifiTimeout() {
    if (state.wifiPending == null) return
    wifiFinish("unconfirmed", "not every write was acknowledged in ${WIFI_TIMEOUT_SECONDS} s; run setWifi again")
}

/** Reads Status and IP a few times after a setWifi, until the plug has an address. */
void wifiRead() {
    int left = ((state.wifiReadsLeft ?: 0) as int) - 1
    if (left > 0) {
        state.wifiReadsLeft = left
        runIn(15, "wifiRead")
    } else {
        state.remove("wifiReadsLeft")
    }
    sendZigbee(wifiReads())
}

/** Action 0 first, as Zigbee2MQTT does, so the attributes show what the plug runs; never during a setWifi. */
private List<String> wifiReads() {
    List<String> cmds = []
    if (state.wifiPending == null) {
        cmds += [wifiWrite(attrLe(0x0002) + "20" + "00"), "delay 500"]
        state.wifiBusyMs = now()
    }
    cmds << shellyFrame("0xFC02", "00", attrLe(0x0000) + attrLe(0x0001) + attrLe(0x0005))                  // Status, IP, SSID
    return cmds
}

private void wifiFinish(String result, String detail) {
    Map pending = (state.wifiPending ?: [:]) as Map
    state.remove("wifiPending")
    state.wifiBusyMs = now()
    unschedule("wifiTimeout")
    unschedule("wifiStart")
    WIFI_FRAMES.remove(device.deviceNetworkId as String)
    wifiOutcome(pending.network as String, result, detail)
}

private void wifiOutcome(String network, String result, String detail) {
    String text = "Wi-Fi${network ? ' ' + network : ''}: ${result}${detail ? ' (' + detail + ')' : ''}"
    if (result == "accepted") {
        logInfo text
    } else {
        logWarn text
    }
    sendEvent(name: "lastSettingsWrite", value: text)
}

/** FC02 frames: write responses to a setWifi, read responses with Status and IP. */
private void parseWifi(Map descMap) {
    String command = (descMap.command as String)?.toUpperCase()
    List<String> d = (descMap.data ?: []) as List<String>
    if (command == "04") {
        if (d && d[0] != "00") {
            if (wifiSetupActive()) wifiFinish("refused", "attribute ${d.size() >= 3 ? d[2] + d[1] : '?'}: ${zclStatus(d[0])}")
        } else {
            wifiWriteAcked()
        }
        return
    }
    if (command == "0B") {
        if (d.size() >= 2 && d[1] != "00") {
            if (d[1] == "C3") state.wifiSupported = false
            if (d[0] == "02" && wifiSetupActive()) wifiFinish("refused", "the plug answered ${zclStatus(d[1])}")
            else logDebug "FC02: command ${d[0]} answered ${zclStatus(d[1])}"
        }
        return
    }
    if (command != "01" && command != "0A") return
    decodeAttributeRecords(d, command == "01").each { Map r ->
        if (r.status != "00") {
            if (r.status == "86" || r.status == "C3") state.wifiSupported = false
            logDebug "FC02/${r.attrId}: ${zclStatus(r.status as String)}"
            return
        }
        if (r.value == null) return                                   // a type this driver does not decode
        state.wifiSupported = true
        String text = hexToText(r.value as String)
        if (r.attrId == "0000") {
            String v = text ?: "unknown"
            if (device.currentValue("wifiStatus") != v) logInfo "Wi-Fi status: ${v}"
            sendEvent(name: "wifiStatus", value: v)
        } else if (r.attrId == "0001") {
            boolean known = text && text != "0.0.0.0"                     // 4613 reported 0.0.0.0 while connecting
            String v = known ? text : "none"
            if (device.currentValue("wifiIp") != v) logInfo "Wi-Fi address: ${v}"
            sendEvent(name: "wifiIp", value: v)
            if (known) {
                state.remove("wifiReadsLeft")
                unschedule("wifiRead")
            }
        } else if (r.attrId == "0005") {
            String v = text ?: "none"
            if (device.currentValue("wifiNetwork") != v) logInfo "Wi-Fi network: ${v}"
            sendEvent(name: "wifiNetwork", value: v)
        }
    }
}

/** A setup that has sent at least one frame: only then is a Wi-Fi write response, good or bad, about it. */
private boolean wifiSetupActive() {
    Map pending = state.wifiPending as Map
    return pending != null && ((pending.sent ?: 0) as int) > 0
}

private String wifiWrite(String records) {
    return shellyFrame("0xFC02", "02", records)
}

private static String attrLe(int id) {
    return String.format("%02X%02X", id & 0xFF, (id >> 8) & 0xFF)
}

private static String boolRecord(int id, boolean v) {
    return attrLe(id) + "10" + (v ? "01" : "00")
}

private static String textRecord(int id, byte[] b) {
    return attrLe(id) + "42" + String.format("%02X", b.length) + hexOf(b, 0, b.length)
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
    if (cluster == "FC02") {
        parseWifi(descMap)
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
    switch (hex?.toUpperCase()) {
        case "00": value = "off"; break
        case "01": value = "on"; break
        default:
            logDebug "switch: ${hex} is not an on/off value, ignored"
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
        state.onType = type
        state.peakWatts = 0
    }
    if (changed && value == "off" && type == "physical") checkTrip()
    sendEvent(name: "switch", value: value, type: type, descriptionText: text)
}

/**
 * An off the hub did not send, seconds after an on or under a load near the limit, is the plug's own
 * protection (or, rarely, someone at the button). A test plug did this after every on for hours on
 * 2026-10-13 with a ~2 kW load on its 1800 W rating; a rule watching tripCount can stop re-sending on.
 * A quick off counts only after an on the hub sent, or once some power was seen: three button presses
 * on 2026-10-15 (on, off, on within 1.4 s, nothing plugged in) are a person, not a trip.
 */
private void checkTrip() {
    Long onAt = state.onAtMs as Long
    BigDecimal peak = toDecimal(state.peakWatts)
    BigDecimal limit = acceptedPowerLimit()
    boolean quick = onAt != null && now() - onAt <= TRIP_WINDOW_MS && (state.onType == "digital" || peak > 0)
    BigDecimal load = tripLoad()
    boolean heavy = load >= limit * TRIP_LOAD_SHARE
    if (!quick && !heavy) return
    BigDecimal shown = quick ? peak : load
    int count = toDecimal(device.currentValue("tripCount")).intValue() + 1
    String when = quick ? "${Math.round((now() - onAt) / 1000.0)} s after it was switched on" : "under load"
    String text = "switched itself off ${when}, at up to ${shown.setScale(0, BigDecimal.ROUND_HALF_UP)} W (limit ${limit} W): likely its overcurrent or overpower protection"
    logWarn "${text}"
    sendEvent(name: "tripCount", value: count, descriptionText: "${device.displayName} ${text}")
    sendEvent(name: "lastTrip", value: text, isStateChange: true)
}

/**
 * The load the plug carried into the off, from its recent power readings (state.watts, newest first). A
 * protection trip reports the cut, 0 W once or more, moments before the off: within the grace time a reading
 * that drops to under half the load before it is that cut, so the load before it stands. An older reading
 * stands as it is: a heater that fell to 50 W minutes ago is judged at 50 W, whatever it drew before.
 */
private BigDecimal tripLoad() {
    List readings = (state.watts ?: []) as List
    long graceFrom = now() - TRIP_DROP_GRACE_MS
    BigDecimal load = null
    for (int k = readings.size() - 1; k >= 0; k--) {                           // oldest first
        List r = readings[k] as List
        BigDecimal w = toDecimal(r[0])
        if (load == null || (r[1] as Long) < graceFrom || w >= load * TRIP_PEAK_DECAY) load = w
    }
    return load ?: 0
}

private void handleMeasurement(String q, String hex, String encoding) {
    Map d = QUANTITIES[q] as Map
    Long raw = decodeRaw(hex, encoding, d.type as String)
    if (raw == null) {
        logDebug "${d.event}: no valid reading (${hex})"
        return
    }
    Map sc = scaleOf(q)
    BigDecimal exact = new BigDecimal(raw).multiply(new BigDecimal(sc.mult as Long))
                                          .divide(new BigDecimal(sc.div as Long), 6, BigDecimal.ROUND_HALF_UP)
    BigDecimal plain = exact.setScale(d.decimals as Integer, BigDecimal.ROUND_HALF_UP)
    BigDecimal value = adjust(q, exact).setScale(d.decimals as Integer, BigDecimal.ROUND_HALF_UP)
    String text = "${device.displayName} ${d.event} is ${value} ${d.unit}"
    if (device.currentValue(d.event as String)?.toString() != value.toString()) logInfo text
    if (q == "power") {
        // The plug's own watts, before any adjustment: its protection and the cutoff go by these.
        state.lastWatts = plain
        state.lastWattsMs = now()
        state.watts = [[plain, now()]] + ((state.watts ?: []) as List).take(WATTS_HISTORY - 1)
        if (device.currentValue("switch") == "on" && plain > toDecimal(state.peakWatts)) state.peakWatts = plain
    }
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
        Long lastChange = state.firmwareChangedMs as Long
        if (lastChange != null && now() - lastChange < FIRMWARE_SETTLE_MS) {
            logDebug "firmware label ${previous} -> ${firmware}; the units were re-read ${Math.round((now() - lastChange) / 1000.0)} s ago"
            return
        }
        state.firmwareChangedMs = now()
        logWarn "firmware changed from ${previous} to ${firmware}; reading the plug's units again"
        state.remove("scale")
        state.remove("fallbackWarned")
        ["startUpOnOff", "startUpWritable", "powerFactorSupported", "lightLevelSupported", "wifiSupported"].each { state.remove(it) }
        sendZigbee(scaleReads() + optionalReads())                   // new firmware may offer what the old one refused
        runIn(3, "wifiRead")
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
                if (cluster == "0006" && data[0] == "02" && state.startUpPending != null) {   // a refusal of the power-on write
                    startUpRefused("0006/4003 write: ${zclStatus(data[1])}")
                    return
                }
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
                } else if (r.value == null) {
                    logDebug "${cluster}/${r.attrId} has type 0x${r.encoding}, which this driver does not decode; the rest of the frame is skipped"
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
    return cmds                                                                           // Wi-Fi: wifiRead, scheduled by the caller
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
    boolean known = state.powerFactorSupported == true
    state.powerFactorSupported = true
    if (!known && state.reportingChange != null) runIn(3, "applyReporting")   // answered after the reporting setup ran
    BigDecimal pf = new BigDecimal(raw).divide(new BigDecimal(100), 2, BigDecimal.ROUND_HALF_UP)
    if (device.currentValue("powerFactor")?.toString() != pf.toString()) logInfo "power factor is ${pf}"
    sendEvent(name: "powerFactor", value: pf)
}

private void handleLightLevel(String attr, String hex, String encoding) {
    Long raw = decodeRaw(hex, encoding, "uint24")
    if (raw == null) return
    if (attr == "0000") {
        boolean known = state.lightLevelSupported == true
        state.lightLevelSupported = true
        if (!known && state.reportingChange != null) runIn(3, "applyReporting")   // answered after the reporting setup ran
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
        boolean shortString = encoding == "41" || encoding == "42"          // octet and character strings, 1-byte length
        boolean longString = encoding == "43" || encoding == "44"           // their long forms, 2-byte length
        if ((shortString && i < d.size()) || (longString && i + 1 < d.size())) {
            int len = shortString ? Integer.parseInt(d[i], 16) : (Integer.parseInt(d[i], 16) | (Integer.parseInt(d[i + 1], 16) << 8))
            i += shortString ? 1 : 2
            int end = Math.min(d.size(), i + len)     // Hubitat may hand over fewer bytes than declared
            out << [attrId: attrId, status: status, encoding: encoding, value: d.subList(i, end).join(""), partial: end < i + len]
            i = end
            continue
        }
        if (size == 0) {
            out << [attrId: attrId, status: status, encoding: encoding, value: null]   // a type this driver does not size
            break
        }
        if (i + size > d.size()) break
        // Little-endian on the air; Hubitat's decoded values are big-endian hex.
        out << [attrId: attrId, status: status, encoding: encoding, value: d.subList(i, i + size).reverse().join("").toUpperCase()]
        i += size
    }
    return out
}

private static int zclTypeSize(int type) {
    if (type == 0x10 || type == 0x30) return 1                       // bool, enum8
    if (type == 0x31 || type == 0x38) return 2                       // enum16, semi-precision float
    if (type == 0x39) return 4                                       // single-precision float
    if (type == 0x3A) return 8                                       // double-precision float
    if (type >= 0x08 && type <= 0x0F) return type - 0x07             // data8 to data64
    if (type >= 0x18 && type <= 0x1F) return type - 0x17             // bitmap8 to bitmap64
    if (type >= 0x20 && type <= 0x27) return type - 0x1F             // uint8 to uint64
    if (type >= 0x28 && type <= 0x2F) return type - 0x27             // int8 to int64
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
        case "88": return "read-only attribute"
        case "81": return "unsupported command"
        case "82": return "unsupported general command"
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
