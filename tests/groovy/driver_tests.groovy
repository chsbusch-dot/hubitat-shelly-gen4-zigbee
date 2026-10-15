/*
 * Execution tests for the Shelly Plug US Gen4 (Zigbee) driver.
 *
 * The driver's own methods run against a stubbed Hubitat runtime (HubitatDriverStub), fed with the
 * descMaps that zigbee.parseDescriptionAsMap hands a driver on the hub. They observe behaviour: the
 * events sent, the Zigbee commands returned or sent, what is scheduled and what is logged.
 *
 * Nothing here touches a hub or a plug.
 *
 * Run:  tests/groovy/run.sh      (compiles the stubs, then executes this file)
 */

import org.codehaus.groovy.control.CompilerConfiguration

// --------------------------------------------------------------------------- harness

int checks = 0
int failures = 0
List<String> failureDetail = []

def check = { String name, Closure body ->
    checks++
    try {
        body.call()
    } catch (Throwable t) {
        failures++
        failureDetail << "${name}: ${t.message}".toString()
        println "FAIL  ${name}\n        ${t.message}"
    }
}

def expect = { boolean condition, String message ->
    if (!condition) throw new AssertionError(message)
}

def driverFile = new File("drivers/Shelly-Plug-US-Gen4-Zigbee.groovy")
def compilerConfig = new CompilerConfiguration()
compilerConfig.scriptBaseClass = HubitatDriverStub.name
def shell = new GroovyShell(HubitatDriverStub.classLoader, new Binding(), compilerConfig)
def driverClass = shell.parse(driverFile).getClass()

/** A write response on FC01, status SUCCESS: both plugs sent one for every TxCtl and TxData write on 2026-10-15. */
def rpcWriteAck = [raw: "catchall", profileId: "C001", clusterId: "FC01", clusterInt: 0xFC01, sourceEndpoint: "EF", destinationEndpoint: "01",
                   options: "0040", messageType: "00", dni: "7257", isClusterSpecific: false, isManufacturerSpecific: true, manufacturerId: "1490",
                   command: "04", direction: "01", data: ["00"]]

/**
 * A fresh driver per test, preferences at their declared defaults unless `prefs` says otherwise. Its plug
 * acknowledges each FC01 write at once; a test that needs them late or missing sets onHubCommand to null.
 */
def newDriver = { Map prefs = [:] ->
    def d = driverClass.getDeclaredConstructor().newInstance()
    d.run()
    d.metadataRecord.inputs.each { if (it.defaultValue != null) d.settings[it.name as String] = it.defaultValue }
    d.settings.putAll(prefs)
    d.device.onRemoveSetting = { String n -> d.settings.remove(n) }
    driverClass.WIFI_FRAMES.clear()                                   // in memory, shared by every instance
    d.onHubCommand = { List<String> cmds ->
        cmds.findAll { (it =~ /0xFC01 \{049014[0-9A-F]{2}02/).find() }.each {
            String key = "ack-${d.zigbee.descriptions.size()}"
            d.zigbee.descriptions[key] = rpcWriteAck
            d.parse(key)
        }
    }
    return d
}

int seq = 0
/** Hand the driver one message, as the hub would: parse(description) with its parsed map. */
def deliver = { drv, Map descMap ->
    String key = "description-${++seq}"
    drv.zigbee.descriptions[key] = descMap
    drv.parse(key)
    drv.persistState()
}

/** A decoded attribute frame (report "0A" or read response "01"), shaped like the hub's descMap. */
def frame = { String cluster, String attrId, String encoding, String value, List extra = null, String command = "0A" ->
    Map m = [raw: "test", dni: "7257", endpoint: "01", cluster: cluster, size: "0C", attrId: attrId, encoding: encoding,
             command: command, value: value, clusterInt: Integer.parseInt(cluster, 16), attrInt: Integer.parseInt(attrId, 16)]
    if (extra) m.additionalAttrs = extra
    return m
}
def more = { String attrId, String encoding, String value ->
    [value: value, encoding: encoding, attrId: attrId, consumedBytes: 5, attrInt: Integer.parseInt(attrId, 16)]
}
def catchall = { String cluster, String command, List data, String profile = "0104" ->
    [raw: "catchall", profileId: profile, clusterId: cluster, clusterInt: Integer.parseInt(cluster, 16), sourceEndpoint: "01",
     destinationEndpoint: "01", options: "0040", messageType: "00", dni: "7257", isClusterSpecific: false,
     isManufacturerSpecific: false, manufacturerId: "0000", command: command, direction: "01", data: data]
}
def hex = { long v, int digits -> String.format("%0${digits}X", v) }

// Written out here rather than read from the driver, so a wrong attribute id in the driver fails a test.
def SCALE = [
    voltage  : [cluster: "0B04", mult: "0600", div: "0601", enc: "21", digits: 4],
    current  : [cluster: "0B04", mult: "0602", div: "0603", enc: "21", digits: 4],
    power    : [cluster: "0B04", mult: "0604", div: "0605", enc: "21", digits: 4],
    frequency: [cluster: "0B04", mult: "0400", div: "0401", enc: "21", digits: 4],
    energy   : [cluster: "0702", mult: "0301", div: "0302", enc: "22", digits: 6],
]
/** The plug answering configure's scale reads: mult and div of a quantity in one read response. */
def giveUnits = { drv, Map units ->
    units.each { String q, List md ->
        Map s = SCALE[q]
        deliver(drv, frame(s.cluster, s.mult, s.enc, hex(md[0] as long, s.digits),
                           [more(s.div, s.enc, hex(md[1] as long, s.digits))], "01"))
    }
}
// Units the 020000FF plug is expected to publish: V/100, mA, W, Hz/100, mWh.
def UNITS_020000FF = [voltage: [1, 100], current: [1, 1000], power: [1, 1], frequency: [1, 100], energy: [1, 1000000]]

// --------------------------------------------------------------------------- metadata

check("metadata: the capabilities, attributes and fingerprint the plug needs") {
    def d = newDriver()
    def md = d.metadataRecord
    ["Switch", "Outlet", "PowerMeter", "EnergyMeter", "VoltageMeasurement", "CurrentMeter", "Refresh", "Configuration", "HealthCheck"].each {
        expect(md.capabilities.contains(it), "capability ${it} declared: ${md.capabilities}")
    }
    expect(md.attributes.acFrequency?.getAt(0) == "number", "acFrequency is a number attribute: ${md.attributes}")
    expect(md.attributes.healthStatus?.getAt(1) == ["online", "offline", "unknown"], "healthStatus enum: ${md.attributes}")
    expect(md.fingerprints.size() == 2 && md.fingerprints.every { it.model == "Plug US" && it.manufacturer == "Shelly" && it.endpointId == "01" },
           "fingerprints for manufacturer Shelly, model Plug US: ${md.fingerprints}")
    // Device 4612's data on the hub (2026-10-12): inClusters 0000,0003,0004,0005,0006,0B04,0702 with no out clusters
    // from its join on 02000021; after firmware 2.0.1 its simple descriptor read ...,0702,FC21 with out cluster 0019.
    expect(md.fingerprints.any { it.inClusters == "0000,0003,0004,0005,0006,0B04,0702" && it.outClusters == null }, "join-time clusters: ${md.fingerprints}")
    expect(md.fingerprints.any { it.inClusters == "0000,0003,0004,0005,0006,0B04,0702,FC21" && it.outClusters == "0019" }, "2.0.1 clusters: ${md.fingerprints}")
    expect(md.definition.namespace == "chsbusch-dot" && md.definition.importUrl.endsWith("/drivers/Shelly-Plug-US-Gen4-Zigbee.groovy"), "definition: ${md.definition}")
}

check("metadata: adjustments default to 0 and none reuses a 1PM driver setting name") {
    def md = newDriver().metadataRecord
    ["powerAdjustPct", "voltageAdjustPct", "currentAdjustPct", "frequencyOffsetHz"].each {
        expect(md.inputNamed(it)?.defaultValue == 0, "${it} defaults to 0: ${md.inputNamed(it)}")
    }
    ["powerCalibration", "voltageCalibration", "currentCalibration", "acFrequencyCalibration", "reportingInterval"].each {
        expect(md.inputNamed(it) == null, "${it} is not declared: a value left by the 1PM driver must not carry over")
    }
    expect(md.inputNamed("logEnable")?.defaultValue == false && md.inputNamed("txtEnable")?.defaultValue == true, "logging defaults")
}

// --------------------------------------------------------------------------- scaling

check("scaling: readings use the multiplier and divisor the plug reports (020000FF: W and mA)") {
    def d = newDriver()
    giveUnits(d, UNITS_020000FF)
    deliver(d, frame("0B04", "0505", "21", hex(11700, 4)))
    deliver(d, frame("0B04", "0508", "21", hex(8989, 4)))
    deliver(d, frame("0B04", "050B", "29", hex(1028, 4)))
    deliver(d, frame("0B04", "0300", "21", hex(6005, 4)))
    expect(d.lastValue("voltage").toString() == "117.0", "117.0 V: ${d.lastValue('voltage')}")
    expect(d.lastValue("amperage").toString() == "8.989", "8.989 A, not 89.89 (the /100 driver): ${d.lastValue('amperage')}")
    expect(d.lastValue("power").toString() == "1028.0", "1028.0 W, not 10.28 W: ${d.lastValue('power')}")
    expect(d.lastValue("acFrequency").toString() == "60.05", "60.05 Hz: ${d.lastValue('acFrequency')}")
    expect(d.eventsNamed("power").last().unit == "W" && d.eventsNamed("amperage").last().unit == "A", "units on the events")
    expect(d.logsMatching("has not reported").isEmpty(), "no fallback warning when the plug reported its units: ${d.logLines}")
}

check("scaling: a firmware that reports power in centiwatts (divisor 100) is scaled by its own divisor") {
    def d = newDriver()
    giveUnits(d, [power: [1, 100]])
    deliver(d, frame("0B04", "050B", "29", hex(2426, 4)))
    expect(d.lastValue("power").toString() == "24.3", "24.26 W shown as 24.3: ${d.lastValue('power')}")
}

check("scaling: a multiplier other than 1 is applied") {
    def d = newDriver()
    giveUnits(d, [voltage: [2, 10]])
    deliver(d, frame("0B04", "0505", "21", hex(600, 4)))
    expect(d.lastValue("voltage").toString() == "120.0", "600 x 2 / 10 = 120.0 V: ${d.lastValue('voltage')}")
}

check("scaling: energy uses the metering cluster's multiplier and divisor (uint48 counter)") {
    def d = newDriver()
    giveUnits(d, [energy: [1, 1000]])
    deliver(d, frame("0702", "0000", "25", hex(5049, 12)))
    expect(d.lastValue("energy").toString() == "5.049", "5049 Wh = 5.049 kWh: ${d.lastValue('energy')}")
    expect(d.eventsNamed("energy").last().unit == "kWh", "kWh unit")
}

check("scaling: without the plug's units the 020000FF defaults apply") {
    def d = newDriver()
    deliver(d, frame("0B04", "050B", "29", hex(1028, 4)))
    deliver(d, frame("0B04", "050B", "29", hex(1100, 4)))
    deliver(d, frame("0B04", "0508", "21", hex(8989, 4)))
    deliver(d, frame("0702", "0000", "25", hex(5049000, 12)))
    expect(d.lastValue("power").toString() == "1100.0", "power in W by default: ${d.lastValue('power')}")
    expect(d.lastValue("amperage").toString() == "8.989", "current in mA by default: ${d.lastValue('amperage')}")
    expect(d.lastValue("energy").toString() == "5.049", "energy in mWh by default: ${d.lastValue('energy')}")
    expect(d.logsMatching("has not reported").isEmpty(), "a reading alone does not warn: the units may still be on their way: ${d.logLines}")
}

check("scaling: a plug that never answers for its units gets one warning per quantity, after Configure waited") {
    def d = newDriver()
    d.configure()
    giveUnits(d, [voltage: [1, 100], current: [1, 1000], frequency: [1, 100], energy: [1, 1000000]])   // no power pair
    d.advance(8000)
    List w = d.logsMatching("has not reported its power units")
    expect(w.size() == 1 && w[0].startsWith("WARN") && w[0].contains("0B04/0604, 0605"), "one warning naming the attributes: ${w}")
    expect(d.logsMatching("has not reported").size() == 1, "only power: ${d.logLines.findAll { it.contains('has not') }}")
    expect(d.zigbee.reportingFor(0x0B04, 0x050B)?.change == 5, "and the default units still give thresholds: ${d.zigbee.reporting}")
    d.updated()
    d.advance(2000)
    expect(d.logsMatching("has not reported").size() == 1, "not repeated on Save Preferences: ${d.logLines.findAll { it.contains('has not') }}")
}

check("reporting: Save Preferences before any Configure asks for the units first, without warnings") {
    def d = newDriver()
    d.updated()
    d.advance(2000)
    expect(d.zigbee.reporting.isEmpty(), "no reporting set up on unknown units: ${d.zigbee.reporting}")
    expect(d.sentCommands().any { it.contains("0x0B04 0x0600") } && d.dueAt.applyReporting == d.clockMs + 8000, "units asked, setup rescheduled: ${d.hubCommands} ${d.dueAt}")
    giveUnits(d, UNITS_020000FF + [power: [1, 100]])
    d.advance(8000)
    expect(d.zigbee.reportingFor(0x0B04, 0x050B)?.change == 500, "set up with the plug's units: ${d.zigbee.reporting}")
    expect(d.logsMatching("has not reported").isEmpty(), "no false warning: ${d.logLines}")
}

check("scaling: units arriving after a fallback reading are taken and logged once") {
    def d = newDriver()
    deliver(d, frame("0B04", "050B", "29", hex(1028, 4)))
    giveUnits(d, [power: [1, 10]])
    giveUnits(d, [power: [1, 10]])
    deliver(d, frame("0B04", "050B", "29", hex(1028, 4)))
    expect(d.lastValue("power").toString() == "102.8", "now scaled by the plug's /10: ${d.lastValue('power')}")
    expect(d.logsMatching("power units from the plug: x1/10").size() == 1, "logged once: ${d.logLines}")
}

check("scaling: a zero or invalid divisor from the plug is ignored, the default stays") {
    def d = newDriver()
    giveUnits(d, [power: [1, 0]])
    deliver(d, frame("0B04", "050B", "29", hex(1028, 4)))
    expect(d.lastValue("power").toString() == "1028.0", "default /1 kept: ${d.lastValue('power')}")
    expect(d.logsMatching("reported power divisor as 0000").size() == 1, "the bad value is logged: ${d.logLines}")
}

// --------------------------------------------------------------------------- recorded frames
// The descMaps below are copied from the hub's debug log of plug 4612 (firmware 2.0.1, firmwareMT
// 1490-1852-020001FF) on 2026-10-12, as zigbee.parseDescriptionAsMap returned them to this driver.

def REC_SCALE_0B04 = [raw: "281B010B0448000621010001060021640002060021010003060021E803040600210100050600210100", dni: "281B", endpoint: "01",
    cluster: "0B04", size: "48", attrId: "0600", encoding: "21", command: "01", value: "0001", clusterInt: 2820, attrInt: 1536,
    additionalAttrs: [[value: "0064", encoding: "21", attrId: "0601", consumedBytes: 5, attrInt: 1537],
                      [value: "0001", encoding: "21", attrId: "0602", consumedBytes: 5, attrInt: 1538],
                      [value: "03E8", encoding: "21", attrId: "0603", consumedBytes: 5, attrInt: 1539],
                      [value: "0001", encoding: "21", attrId: "0604", consumedBytes: 5, attrInt: 1540],
                      [value: "0001", encoding: "21", attrId: "0605", consumedBytes: 5, attrInt: 1541]]]
def REC_SCALE_FREQ = [raw: "281B010B04180004210100010400216400", dni: "281B", endpoint: "01", cluster: "0B04", size: "18", attrId: "0400",
    encoding: "21", command: "01", value: "0001", clusterInt: 2820, attrInt: 1024,
    additionalAttrs: [[value: "0064", encoding: "21", attrId: "0401", consumedBytes: 5, attrInt: 1025]]]
def REC_SCALE_0702 = [raw: "281B0107022600033000010300220100000203002240420F", dni: "281B", endpoint: "01", cluster: "0702", size: "26",
    attrId: "0300", encoding: "30", command: "01", value: "00", clusterInt: 1794, attrInt: 768,
    additionalAttrs: [[value: "000001", encoding: "22", attrId: "0301", consumedBytes: 6, attrInt: 769],
                      [value: "0F4240", encoding: "22", attrId: "0302", consumedBytes: 6, attrInt: 770]]]
def REC_REFRESH_0B04 = [raw: "281B010B0430050521EF2E0805002100000B0500290000000300216F17", dni: "281B", endpoint: "01", cluster: "0B04",
    size: "30", attrId: "0505", encoding: "21", command: "01", value: "2EEF", clusterInt: 2820, attrInt: 1285,
    additionalAttrs: [[value: "0000", encoding: "21", attrId: "0508", consumedBytes: 5, attrInt: 1288],
                      [value: "0000", encoding: "29", attrId: "050B", consumedBytes: 5, attrInt: 1291],
                      [value: "176F", encoding: "21", attrId: "0300", consumedBytes: 5, attrInt: 768]]]
def REC_ENERGY = [raw: "281B010702120000259859E0000000", dni: "281B", endpoint: "01", cluster: "0702", size: "12", attrId: "0000",
    encoding: "25", command: "0A", value: "000000E05998", clusterInt: 1794, attrInt: 0]
def REC_BUILD = [raw: "281B0100001400404205322E302E31", dni: "281B", endpoint: "01", cluster: "0000", size: "14", attrId: "4000",
    encoding: "42", command: "01", value: "2.0.1", clusterInt: 0, attrInt: 16384]
def REC_CR_OK = [raw: "catchall: 0104 0B04 01 01 0040 00 281B 00 00 0000 07 01 00", profileId: "0104", clusterId: "0B04", clusterInt: 2820,
    sourceEndpoint: "01", destinationEndpoint: "01", options: "0040", messageType: "00", dni: "281B", isClusterSpecific: false,
    isManufacturerSpecific: false, manufacturerId: "0000", command: "07", direction: "01", data: ["00"]]

check("recorded: 4612's configure answers give its units, and its readings scale with them") {
    def d = newDriver()
    d.configure()
    [REC_SCALE_0B04, REC_SCALE_FREQ, REC_SCALE_0702, REC_CR_OK, REC_BUILD].each { deliver(d, it) }
    expect(d.state.scale == [voltage: [mult: 1L, div: 100L], current: [mult: 1L, div: 1000L], power: [mult: 1L, div: 1L],
                             frequency: [mult: 1L, div: 100L], energy: [mult: 1L, div: 1000000L]], "all five pairs: ${d.state.scale}")
    deliver(d, REC_REFRESH_0B04)
    deliver(d, REC_ENERGY)
    expect(d.lastValue("voltage").toString() == "120.2", "0x2EEF = 12015 = 120.2 V: ${d.lastValue('voltage')}")
    expect(d.lastValue("acFrequency").toString() == "59.99", "0x176F = 5999: ${d.lastValue('acFrequency')}")
    expect(d.lastValue("power").toString() == "0.0" && d.lastValue("amperage").toString() == "0.000", "off, no load: ${d.events}")
    expect(d.lastValue("energy").toString() == "14.703", "0xE05998 = 14703000 mWh: ${d.lastValue('energy')}")
    expect(d.device.data.softwareBuild == "2.0.1", "${d.device.data}")
    d.advance(8000)
    expect(d.logsMatching("has not reported").isEmpty() && d.logLines.findAll { it.startsWith("WARN") }.isEmpty(), "no warnings: ${d.logLines.findAll { it.startsWith('WARN') }}")
    expect(d.zigbee.reportingFor(0x0B04, 0x0505).change == 100 && d.zigbee.reportingFor(0x0702, 0x0000).change == 10000, "${d.zigbee.reporting}")
}

check("recorded: 4613's bundled current+power report while its heater ran (the frozen-power frame)") {
    // 2026-10-12 08:20:52 from plug 4613 (firmware 2.0.0, 020000FF) on this driver: raw 7257010B04140805213E190B0529A602
    def d = newDriver()
    giveUnits(d, UNITS_020000FF)
    deliver(d, [raw: "7257010B04140805213E190B0529A602", dni: "7257", endpoint: "01", cluster: "0B04", size: "14", attrId: "0508",
                encoding: "21", command: "0A", value: "193E", clusterInt: 2820, attrInt: 1288,
                additionalAttrs: [[value: "02A6", encoding: "29", attrId: "050B", consumedBytes: 5, attrInt: 1291]]])
    expect(d.lastValue("amperage").toString() == "6.462" && d.lastValue("power").toString() == "678.0", "both from one frame: ${d.events}")
    // and a three-value frame with voltage: 08:20:11, 118.85 V x 9.987 A = 1187 VA against 1193 W
    deliver(d, [raw: "7257010B041E0505216D2E08052103270B0529A904", dni: "7257", endpoint: "01", cluster: "0B04", size: "1E", attrId: "0505",
                encoding: "21", command: "0A", value: "2E6D", clusterInt: 2820, attrInt: 1285,
                additionalAttrs: [[value: "2703", encoding: "21", attrId: "0508", consumedBytes: 5, attrInt: 1288],
                                  [value: "04A9", encoding: "29", attrId: "050B", consumedBytes: 5, attrInt: 1291]]])
    BigDecimal va = (d.lastValue("voltage") as BigDecimal) * (d.lastValue("amperage") as BigDecimal)
    expect(d.lastValue("power").toString() == "1193.0" && Math.abs((d.lastValue("power") as BigDecimal) / va - 1) < 0.02,
           "power within 2% of V x I (${va}): ${d.events.takeRight(3)}")
}

// --------------------------------------------------------------------------- bundled reports

check("bundled report: current with power in additionalAttrs yields both (the frozen-power bug)") {
    def d = newDriver()
    giveUnits(d, UNITS_020000FF)
    deliver(d, frame("0B04", "0508", "21", hex(8989, 4), [more("050B", "29", hex(1028, 4))]))
    expect(d.lastValue("amperage").toString() == "8.989", "current from attrId: ${d.lastValue('amperage')}")
    expect(d.lastValue("power").toString() == "1028.0", "power from additionalAttrs, not frozen: ${d.events}")
}

check("bundled report: three attributes in one frame, including voltage") {
    def d = newDriver()
    giveUnits(d, UNITS_020000FF)
    deliver(d, frame("0B04", "0505", "21", hex(11960, 4), [more("0508", "21", hex(450, 4)), more("050B", "29", hex(52, 4))]))
    expect(d.lastValue("voltage").toString() == "119.6" && d.lastValue("amperage").toString() == "0.450" && d.lastValue("power").toString() == "52.0",
           "all three: ${d.events}")
}

check("bundled report: metering frames are walked the same way") {
    def d = newDriver()
    giveUnits(d, [energy: [1, 1000]])
    deliver(d, frame("0702", "0400", "2A", hex(1028, 6), [more("0000", "25", hex(5100, 12))]))
    expect(d.lastValue("energy").toString() == "5.100", "energy from additionalAttrs behind an ignored attribute: ${d.events}")
}

// --------------------------------------------------------------------------- value decoding

check("decoding: power is signed (int16) and the ZCL invalid values send nothing") {
    def d = newDriver()
    giveUnits(d, UNITS_020000FF)
    deliver(d, frame("0B04", "050B", "29", "FC18"))
    expect(d.lastValue("power").toString() == "-1000.0", "0xFC18 = -1000 W: ${d.lastValue('power')}")
    int before = d.events.size()
    deliver(d, frame("0B04", "050B", "29", "8000"))
    deliver(d, frame("0B04", "0505", "21", "FFFF"))
    deliver(d, frame("0702", "0000", "25", "FFFFFFFFFFFF"))
    deliver(d, frame("0B04", "0508", "21", "not hex"))
    expect(d.events.size() == before, "no event for 0x8000, 0xFFFF, all-ones energy or garbage: ${d.events.drop(before)}")
}

check("decoding: an additional attribute without an encoding uses the quantity's own type") {
    def d = newDriver()
    giveUnits(d, UNITS_020000FF)
    deliver(d, frame("0B04", "0508", "21", hex(100, 4), [[attrId: "050B", value: "FF9C"]]))
    expect(d.lastValue("power").toString() == "-100.0", "int16 from the quantity table: ${d.lastValue('power')}")
}

// --------------------------------------------------------------------------- configure

check("configure: reads units and software build; never switches the relay") {
    def d = newDriver()
    List<String> cmds = d.configure()
    expect(d.zigbee.switchCommands.isEmpty(), "no on/off/toggle: ${d.zigbee.switchCommands}")
    expect(!cmds.any { it.contains("0x0006 0x0") && it.startsWith("he cmd") }, "no on/off cluster command in what configure returns: ${cmds}")
    List reads = d.zigbee.reads
    expect(reads.any { it.cluster == 0x0B04 && it.attrs.containsAll([0x0600, 0x0601, 0x0602, 0x0603, 0x0604, 0x0605]) }, "0B04 multipliers/divisors read: ${reads}")
    expect(reads.any { it.cluster == 0x0B04 && it.attrs.containsAll([0x0400, 0x0401]) }, "frequency scale read: ${reads}")
    expect(reads.any { it.cluster == 0x0702 && it.attrs.containsAll([0x0300, 0x0301, 0x0302]) }, "0702 unit, multiplier, divisor read: ${reads}")
    expect(reads.any { it.cluster == 0x0000 && it.attrs == [0x4000] }, "software build read: ${reads}")
    expect(!cmds.any { it.startsWith("he raw") && !it.startsWith("he raw 0x7257 1 0xEF 0xFC02 ") }, "no raw frames but the Wi-Fi status read: ${cmds}")
    expect(d.zigbee.reporting.isEmpty(), "reporting waits for the units: ${d.zigbee.reporting}")
    expect(d.dueAt.applyReporting == d.clockMs + 8000, "applyReporting in 8 s: ${d.dueAt}")
    expect(d.periodic.healthCheck == 15, "health check every 15 minutes: ${d.periodic}")
}

check("configure then units: thresholds are converted to the plug's raw units") {
    def d = newDriver()
    d.configure()
    giveUnits(d, UNITS_020000FF)
    d.advance(8000)
    def z = d.zigbee
    expect(z.reportingFor(0x0B04, 0x050B)?.change == 5, "power 5 W = 5 raw: ${z.reporting}")
    expect(z.reportingFor(0x0B04, 0x0508)?.change == 50, "current 0.05 A = 50 mA: ${z.reporting}")
    expect(z.reportingFor(0x0B04, 0x0505)?.change == 100, "voltage 1 V = 100 raw: ${z.reporting}")
    expect(z.reportingFor(0x0B04, 0x0300)?.change == 10, "frequency 0.1 Hz = 10 raw: ${z.reporting}")
    expect(z.reportingFor(0x0702, 0x0000)?.change == 10000, "energy 0.01 kWh = 10000 mWh: ${z.reporting}")
    expect(z.reportingFor(0x0B04, 0x050B).min == 10 && z.reportingFor(0x0B04, 0x050B).max == 600, "10 to 600 s: ${z.reportingFor(0x0B04, 0x050B)}")
    expect(z.reportingFor(0x0B04, 0x050B).type == 0x29 && z.reportingFor(0x0702, 0x0000).type == 0x25, "int16 power, uint48 energy")
    Map sw = z.reportingFor(0x0006, 0x0000)
    expect(sw != null && sw.min == 0 && sw.max == 600 && sw.type == 0x10, "switch reported at once and every 600 s: ${sw}")
    List<String> sent = d.sentCommands()
    expect(sent.any { it.startsWith("he cr") } && sent.any { it.startsWith("he rattr 0x7257 0x01 0x0006") }, "sent in one batch with a refresh: ${d.hubCommands}")
    expect(d.zigbee.switchCommands.isEmpty(), "still no relay command")
}

check("reporting: a power divisor of 100 turns the 5 W threshold into 500 raw") {
    def d = newDriver()
    giveUnits(d, UNITS_020000FF + [power: [1, 100]])
    d.applyReporting()
    expect(d.zigbee.reportingFor(0x0B04, 0x050B).change == 500, "${d.zigbee.reporting}")
}

check("reporting: the power threshold and longest interval follow the preferences") {
    def d = newDriver(powerChangeWatts: 20, reportMaxSeconds: 1200)
    giveUnits(d, UNITS_020000FF)
    d.applyReporting()
    Map p = d.zigbee.reportingFor(0x0B04, 0x050B)
    expect(p.change == 20 && p.max == 1200, "20 W, 1200 s: ${p}")
    expect(d.zigbee.reportingFor(0x0006, 0x0000).max == 1200, "the switch heartbeat too")
}

check("reporting: an out-of-range interval is clamped, a threshold never drops below 1 raw unit") {
    def d = newDriver(reportMaxSeconds: 5, powerChangeWatts: 0.1)
    giveUnits(d, UNITS_020000FF)
    d.applyReporting()
    Map p = d.zigbee.reportingFor(0x0B04, 0x050B)
    expect(p.max == 60 && p.change == 1, "max 60 s, change 1: ${p}")
}

check("reporting: units that change a threshold already sent schedule a new setup; the same units do not") {
    def d = newDriver()
    d.configure()
    d.advance(8000)                                         // the plug did not answer: all fallbacks
    giveUnits(d, [power: [1, 1]])                           // same as the fallback: nothing to redo
    expect(d.dueAt.applyReporting == null, "no re-apply for unchanged thresholds: ${d.dueAt}")
    giveUnits(d, [current: [1, 100]])                       // 0.05 A is now 5 raw, not 50
    expect(d.dueAt.applyReporting == d.clockMs + 3000, "re-apply scheduled: ${d.dueAt}")
    d.advance(3000)
    expect(d.zigbee.reportingFor(0x0B04, 0x0508).change == 5, "${d.zigbee.reportingFor(0x0B04, 0x0508)}")
}

check("updated: saving preferences re-applies reporting and keeps the health check scheduled") {
    def d = newDriver()
    d.updated()
    expect(d.dueAt.applyReporting == d.clockMs + 2000 && d.periodic.healthCheck == 15, "${d.dueAt} ${d.periodic}")
    Map ci = d.eventsNamed("checkInterval").last()
    expect(ci.value == 1860, "checkInterval = 3 x 600 s + 60: ${ci}")
}

// --------------------------------------------------------------------------- adjustments

check("adjustments: default 0 changes nothing; set, they apply") {
    def d = newDriver()
    giveUnits(d, UNITS_020000FF)
    deliver(d, frame("0B04", "050B", "29", hex(1000, 4)))
    expect(d.lastValue("power").toString() == "1000.0", "unadjusted: ${d.lastValue('power')}")
    d.settings.powerAdjustPct = 2
    d.settings.voltageAdjustPct = "-1"
    d.settings.frequencyOffsetHz = 0.05
    deliver(d, frame("0B04", "050B", "29", hex(1000, 4)))
    deliver(d, frame("0B04", "0505", "21", hex(12000, 4)))
    deliver(d, frame("0B04", "0300", "21", hex(5995, 4)))
    expect(d.lastValue("power").toString() == "1020.0", "+2%: ${d.lastValue('power')}")
    expect(d.lastValue("voltage").toString() == "118.8", "-1% (a string setting): ${d.lastValue('voltage')}")
    expect(d.lastValue("acFrequency").toString() == "60.00", "+0.05 Hz: ${d.lastValue('acFrequency')}")
}

// --------------------------------------------------------------------------- migration

check("migration: the 1PM driver's calibration workaround is removed, with a warning for each non-zero one") {
    def d = newDriver(powerCalibration: 9900, currentCalibration: -90, voltageCalibration: 0, acFrequencyCalibration: 0, reportingInterval: 120)
    d.state.putAll(rpcJsonRequest: "{}", rpcMachinePhase: "done", zclSeq: 71, rpcRxText: "")
    d.device.current.putAll(rpcStatus: "rxctl:80", producedEnergy: 0)
    d.configure()
    expect(d.device.removedSettings.toSet() == ["powerCalibration", "currentCalibration", "voltageCalibration", "acFrequencyCalibration", "reportingInterval"].toSet(),
           "all five removed: ${d.device.removedSettings}")
    expect(!d.settings.containsKey("powerCalibration") && !d.settings.containsKey("currentCalibration"), "gone from settings: ${d.settings}")
    List w = d.logsMatching("left by the previous driver")
    expect(w.size() == 2 && w.any { it.contains("powerCalibration = 9900") } && w.any { it.contains("currentCalibration = -90") }, "warned for the two non-zero: ${w}")
    expect(!d.state.keySet().any { it.startsWith("rpc") } && d.state.zclSeq != 71, "RPC state cleared; zclSeq restarts with this driver's own frames: ${d.state}")
    expect(d.device.deletedStates.containsAll(["rpcStatus", "rpcResponse", "producedEnergy", "inputMode"]), "stale attributes deleted: ${d.device.deletedStates}")
    giveUnits(d, UNITS_020000FF)
    deliver(d, frame("0B04", "050B", "29", hex(1028, 4)))
    expect(d.lastValue("power").toString() == "1028.0", "no x100 from a leftover 9900%: ${d.lastValue('power')}")
}

check("migration: a device with nothing to migrate is left alone and logs nothing about it") {
    def d = newDriver()
    d.updated()
    expect(d.device.removedSettings.isEmpty() && d.logsMatching("previous driver").isEmpty(), "${d.device.removedSettings} ${d.logLines}")
}

// --------------------------------------------------------------------------- switch

check("switch: a report that follows on() is digital; one nobody asked for is physical") {
    def d = newDriver()
    List<String> cmd = d.on()
    expect(cmd[0] == "he cmd 0x7257 0x01 0x0006 0x01 {}", "on() returns the on command: ${cmd}")
    d.advance(1500)
    deliver(d, frame("0006", "0000", "10", "01"))
    Map e = d.eventsNamed("switch").last()
    expect(e.value == "on" && e.type == "digital", "digital on: ${e}")
    d.advance(60_000)
    deliver(d, frame("0006", "0000", "10", "00"))
    e = d.eventsNamed("switch").last()
    expect(e.value == "off" && e.type == "physical", "physical off (button on the plug): ${e}")
    expect(d.logsMatching("was turned off [physical]").size() == 1, "logged: ${d.logLines}")
}

check("switch: a late echo (over 10 s after the command) counts as physical") {
    def d = newDriver()
    d.off()
    d.advance(11_000)
    deliver(d, frame("0006", "0000", "10", "00"))
    expect(d.eventsNamed("switch").last().type == "physical", "${d.eventsNamed('switch')}")
}

check("switch: an unchanged heartbeat report is not logged again") {
    def d = newDriver()
    deliver(d, frame("0006", "0000", "10", "01"))
    deliver(d, frame("0006", "0000", "10", "01", null, "01"))
    expect(d.logsMatching("was turned on").size() == 1, "${d.logLines}")
}

// --------------------------------------------------------------------------- health

check("health: an idle plug that answers the ping is not marked offline (4612 under 1.0.0)") {
    def d = newDriver()
    deliver(d, frame("0006", "0000", "10", "01"))
    expect(d.lastValue("healthStatus") == "online", "online after a message: ${d.events}")
    d.advance(1_800_000)
    d.healthCheck()
    expect(d.hubCommands.isEmpty() && d.dueAt.healthConfirm == null, "30 min: no ping yet: ${d.hubCommands}")
    d.advance(120_000)
    d.healthCheck()
    expect(d.sentCommands().any { it.startsWith("he rattr 0x7257 0x01 0x0006 0x0000") }, "32 min: pinged: ${d.hubCommands}")
    expect(d.lastValue("healthStatus") == "online" && d.dueAt.healthConfirm == d.clockMs + 60_000, "not offline yet, confirm in 60 s: ${d.events} ${d.dueAt}")
    d.advance(3_000)
    deliver(d, frame("0006", "0000", "10", "01", null, "01"))             // the ping's answer
    d.advance(60_000)
    expect(d.eventsNamed("healthStatus").every { it.value == "online" } && d.logsMatching("offline").isEmpty(), "never offline: ${d.events}")
    expect(d.state.healthPingMs == null, "the ping is settled: ${d.state}")
}

check("health: a plug that stays silent through the ping goes offline once, and back online on a message") {
    def d = newDriver()
    deliver(d, frame("0006", "0000", "10", "01"))
    d.advance(1_920_000)
    d.healthCheck()
    d.advance(60_000)
    expect(d.lastValue("healthStatus") == "offline", "33 min, ping unanswered: offline: ${d.events}")
    expect(d.logsMatching("offline, no message for 33 minutes and no answer to a ping").size() == 1, "${d.logLines}")
    d.advance(900_000)
    d.healthCheck()
    expect(d.sentCommands().count { it.startsWith("he rattr 0x7257 0x01 0x0006 0x0000") } == 2, "pinged again while offline: ${d.hubCommands}")
    d.advance(60_000)
    expect(d.eventsNamed("healthStatus").count { it.value == "offline" } == 1 && d.logsMatching("offline").size() == 1, "offline sent once: ${d.events}")
    deliver(d, frame("0B04", "0505", "21", hex(11960, 4)))
    expect(d.lastValue("healthStatus") == "online", "back online: ${d.events}")
}

check("health: a plug never heard from goes offline after an unanswered ping") {
    def d = newDriver()
    d.healthCheck()
    d.advance(60_000)
    expect(d.lastValue("healthStatus") == "offline" && d.logsMatching("no message since the driver started and no answer to a ping").size() == 1, "${d.events} ${d.logLines}")
}

check("health: the receive stamp is written at most once a minute") {
    def d = newDriver()
    deliver(d, frame("0006", "0000", "10", "01"))
    Long first = d.state.lastRxMs
    d.advance(30_000)
    deliver(d, frame("0B04", "0505", "21", hex(11960, 4)))
    expect(d.state.lastRxMs == first, "not rewritten after 30 s")
    d.advance(31_000)
    deliver(d, frame("0B04", "0505", "21", hex(11961, 4)))
    expect(d.state.lastRxMs == d.clockMs, "rewritten after 61 s")
}

check("health: ping() reads the switch state") {
    def d = newDriver()
    expect(d.ping()[0].startsWith("he rattr 0x7257 0x01 0x0006 0x0000"), "${d.ping()}")
}

// --------------------------------------------------------------------------- catchall frames

check("catchall: a read response with unsupported scale attributes keeps the defaults") {
    def d = newDriver()
    // 0604 unsupported, 0605 unsupported (attr LE, status 86)
    deliver(d, catchall("0B04", "01", ["04", "06", "86", "05", "06", "86"]))
    expect(d.state.scale == null, "nothing stored: ${d.state.scale}")
    deliver(d, frame("0B04", "050B", "29", hex(1028, 4)))
    expect(d.lastValue("power").toString() == "1028.0", "fallback used: ${d.lastValue('power')}")
}

check("catchall: a read response mixing success and failure is decoded little-endian") {
    def d = newDriver()
    // 0604 = 1 (uint16 01 00), 0605 = 1000 (uint16 E8 03), 0400 unsupported
    deliver(d, catchall("0B04", "01", ["04", "06", "00", "21", "01", "00", "05", "06", "00", "21", "E8", "03", "00", "04", "86"]))
    expect(d.state.scale?.power == [mult: 1L, div: 1000L], "power units stored: ${d.state.scale}")
    deliver(d, catchall("0B04", "0A", ["0B", "05", "29", "10", "27"]))
    expect(d.lastValue("power").toString() == "10.0", "a catchall report: 10000 / 1000 W: ${d.lastValue('power')}")
}

check("catchall: a refused reporting configuration is logged with its attribute") {
    def d = newDriver()
    deliver(d, catchall("0B04", "07", ["00"]))
    expect(d.logsMatching("refused").isEmpty(), "success is quiet")
    deliver(d, catchall("0B04", "07", ["86", "00", "00", "03"]))
    List w = d.logsMatching("refused reporting for 0B04/0300 (unsupported attribute)")
    expect(w.size() == 1 && w[0].startsWith("WARN"), "${d.logLines}")
}

check("catchall: a default response with an error status is logged") {
    def d = newDriver()
    deliver(d, catchall("0006", "0B", ["01", "00"]))
    expect(d.logsMatching("answered command").isEmpty(), "success is quiet")
    deliver(d, catchall("0006", "0B", ["01", "81"]))
    expect(d.logsMatching("answered command 01 on cluster 0006 with unsupported command").size() == 1, "${d.logLines}")
}

check("zdo: bind responses and other ZDO frames are ignored quietly") {
    def d = newDriver()
    // recorded on the hub from 4612, 2026-10-12: catchall: 0000 8021 00 00 0040 00 281B 00 00 0000 00 00 C500
    deliver(d, catchall("8021", "00", ["C5", "00"], "0000"))
    expect(d.events.findAll { it.name != "healthStatus" }.isEmpty() && d.logLines.findAll { it.startsWith("WARN") }.isEmpty(), "${d.events} ${d.logLines}")
}

// --------------------------------------------------------------------------- firmware

check("firmware: a new software build or OTA version clears the units and reads them again") {
    def d = newDriver()
    d.device.data.firmwareMT = "1490-1852-020000FF"
    giveUnits(d, UNITS_020000FF)
    deliver(d, frame("0000", "4000", "42", "2.0.1", null, "01"))
    expect(d.state.scale != null && d.dueAt.applyReporting == null, "first build seen: nothing to redo: ${d.dueAt}")
    expect(d.device.data.softwareBuild == "2.0.1", "softwareBuild follows what the plug reports: ${d.device.data}")
    deliver(d, frame("0000", "4000", "42", "2.0.1", null, "01"))
    expect(d.hubCommands.isEmpty(), "same firmware: nothing sent")
    d.device.data.firmwareMT = "1490-1852-020001FF"      // the hub saw a newer OTA version
    deliver(d, frame("0000", "4000", "42", "2.0.1", null, "01"))
    expect(d.state.scale == null, "units cleared: ${d.state.scale}")
    expect(d.sentCommands().any { it.contains("0x0B04 0x0600") }, "scale reads sent: ${d.hubCommands}")
    expect(d.dueAt.applyReporting == d.clockMs + 8000, "reporting redone: ${d.dueAt}")
    expect(d.logsMatching("firmware changed from 2.0.1 1490-1852-020000FF to 2.0.1 1490-1852-020001FF").size() == 1, "${d.logLines}")
    giveUnits(d, UNITS_020000FF)
    deliver(d, frame("0000", "4000", "42", "2.0.2", null, "01"))
    expect(d.state.scale == null && d.logsMatching("firmware changed").size() == 2, "a new build alone counts too: ${d.logLines}")
}

check("firmware: a build handed over as hex of its characters is decoded") {
    def d = newDriver()
    deliver(d, frame("0000", "4000", "42", "3032303030304646", null, "01"))
    expect(d.device.data.softwareBuild == "020000FF", "${d.device.data}")
}

// --------------------------------------------------------------------------- extras (WOR-759)

/** A catchall read response on FC01 carrying the reply text, as Hubitat hands it over: cut short. */
def rpcReply = { String text, int declaredLen ->
    List data = ["00", "00", "00", "42", String.format("%02X", declaredLen)] + text.getBytes("UTF-8").collect { String.format("%02X", it & 0xFF) }
    [raw: "catchall", profileId: "C001", clusterId: "FC01", clusterInt: 0xFC01, sourceEndpoint: "EF", destinationEndpoint: "01",
     options: "0040", messageType: "00", dni: "7257", isClusterSpecific: false, isManufacturerSpecific: true, manufacturerId: "1490",
     command: "01", direction: "01", data: data]
}
def hexOfText = { String s -> s.getBytes("UTF-8").collect { String.format("%02X", it & 0xFF) }.join("") }

check("identify: blinks for the given seconds, little-endian, reads IdentifyTime back, never touches the relay") {
    def d = newDriver()
    List<String> cmds = d.identify(10)
    expect(d.zigbee.clusterCommands == [[cluster: 0x0003, command: 0x00, payload: "0A00"]], "Identify 10 s = 0A00: ${d.zigbee.clusterCommands}")
    expect(d.zigbee.reads.any { it.cluster == 0x0003 && it.attrs == [0x0000] } && d.zigbee.switchCommands.isEmpty(), "${d.zigbee.reads}")
    d.identify(300)
    expect(d.zigbee.clusterCommands.last().payload == "2C01", "300 s = 0x012C: ${d.zigbee.clusterCommands.last()}")
    d.identify(null); d.identify(0); d.identify(5000)
    expect(d.zigbee.clusterCommands.takeRight(3)*.payload == ["0A00", "0A00", "5802"], "default 10 s, max 600 s: ${d.zigbee.clusterCommands.takeRight(3)}")
}

check("configure: also reads the power-on state and Shelly's light-level cluster (manufacturer 0x1490)") {
    def d = newDriver()
    d.configure()
    expect(d.zigbee.reads.any { it.cluster == 0x0006 && it.attrs == [0x4003] }, "0006/4003: ${d.zigbee.reads}")
    Map fc21 = d.zigbee.reads.find { it.cluster == 0xFC21 }
    expect(fc21 != null && fc21.attrs == [0x0000, 0x0001, 0x0002] && fc21.params.mfgCode == 0x1490, "FC21 with mfgCode: ${fc21}")
}

check("light level: FC21 attribute 0 maps to dark, twilight or bright; an unsupported cluster is not a warning") {
    def d = newDriver()
    deliver(d, frame("FC21", "0000", "20", "01", [more("0001", "22", "000064")], "01"))
    expect(d.lastValue("lightLevel") == "twilight", "${d.events}")
    expect(d.logsMatching("light level dark threshold: 100 lx").size() == 1, "${d.logLines}")
    deliver(d, catchall("FC21", "0B", ["00", "C3"]))
    expect(d.logLines.findAll { it.startsWith("WARN") }.isEmpty() && d.logsMatching("unsupported cluster").size() == 1, "${d.logLines}")
}

check("power-on state: a read answer is shown and remembered as supported; unsupported is remembered too") {
    def d = newDriver()
    deliver(d, frame("0006", "4003", "30", "FF", null, "01"))
    expect(d.lastValue("powerOnState") == "previous" && d.state.startUpOnOff == true, "${d.events} ${d.state}")
    def e = newDriver()
    deliver(e, catchall("0006", "01", ["03", "40", "86"]))
    expect(e.state.startUpOnOff == false && e.logsMatching("does not offer 0006/4003 (unsupported attribute)").size() == 1, "${e.state} ${e.logLines}")
}

/** Every RPC request sent so far, its TxData chunks joined back into the JSON text. */
def rpcRequests = { drv ->
    List<String> out = []
    StringBuilder cur = null
    drv.sentCommands().findAll { it.contains("0xFC01") }.each { String c ->
        def m = c =~ /\{049014[0-9A-F]{2}(02|00)([0-9A-F]*)\}/
        if (!m.find()) return
        String cmd = m.group(1)
        String body = m.group(2)
        if (cmd == "02" && body.startsWith("010023")) {
            if (cur != null) out << cur.toString()
            cur = new StringBuilder()
        } else if (cmd == "02" && body.startsWith("000042") && cur != null) {
            cur.append(new String(body.substring(8).decodeHex(), "UTF-8"))
        }
    }
    if (cur != null) out << cur.toString()
    out
}
/** One complete reply chunk. */
def chunk = { drv, String text -> deliver(drv, rpcReply(text, text.getBytes("UTF-8").length)) }

check("recorded: 4612 (fw 2.0.1) answers the extras probe: light level yes, power-on state and power factor no") {
    // 2026-10-15 09:34 from plug 4612 on this driver, as parseDescriptionAsMap returned them
    def d = newDriver()
    d.configure()
    deliver(d, catchall("0003", "0B", ["00", "00"]))                                         // Identify accepted
    deliver(d, [raw: "281B0100030C0000210900", dni: "281B", endpoint: "01", cluster: "0003", size: "0C", attrId: "0000",
                encoding: "21", command: "01", value: "0009", clusterInt: 3, attrInt: 0])
    expect(d.logsMatching("identify: 9 s left").size() == 1, "${d.logLines}")
    deliver(d, catchall("0006", "01", ["03", "40", "86"]))
    deliver(d, catchall("0B04", "01", ["10", "05", "86"]))
    deliver(d, [raw: "281B01FC212600002000010000220A000002000022320000", dni: "281B", endpoint: "01", cluster: "FC21", size: "26",
                attrId: "0000", encoding: "20", command: "01", value: "00", clusterInt: 64545, attrInt: 0,
                additionalAttrs: [[value: "00000A", encoding: "22", attrId: "0001", consumedBytes: 6, attrInt: 1],
                                  [value: "000032", encoding: "22", attrId: "0002", consumedBytes: 6, attrInt: 2]]])
    expect(d.lastValue("lightLevel") == "dark" && d.logsMatching("dark threshold: 10 lx").size() == 1 && d.logsMatching("bright threshold: 50 lx").size() == 1, "${d.events} ${d.logLines}")
    expect(d.state.startUpOnOff == false && d.state.powerFactorSupported == false && d.state.lightLevelSupported == true, "${d.state}")
    expect(d.logLines.findAll { it.startsWith("WARN") }.isEmpty(), "no warnings: ${d.logLines.findAll { it.startsWith('WARN') }}")
    giveUnits(d, UNITS_020000FF)
    d.advance(8_000)
    Map ll = d.zigbee.reportingFor(0xFC21, 0x0000)
    expect(ll != null && ll.min == 60 && ll.max == 900 && ll.params.mfgCode == 0x1490 && ll.type == 0x20, "light level reported with Shelly's mfgCode: ${ll}")
    expect(d.zigbee.reportingFor(0x0B04, 0x0510) == null, "no power factor reporting: ${d.zigbee.reporting}")
    expect(d.zigbee.reads.any { it.cluster == 0xFC21 && it.attrs == [0x0000] && it.params.mfgCode == 0x1490 }, "refresh reads it: ${d.zigbee.reads}")
}

check("light level: a plug without FC21 gets no light-level reporting") {
    def d = newDriver()
    d.configure()
    deliver(d, catchall("FC21", "0B", ["00", "C3"]))
    giveUnits(d, UNITS_020000FF)
    d.advance(8_000)
    expect(d.state.lightLevelSupported == false && d.zigbee.reportingFor(0xFC21, 0x0000) == null, "${d.state} ${d.zigbee.reporting}")
}

check("plug settings: nothing is ever sent while the preferences are empty or on keep") {
    [[:], [powerOnState: "keep"]].each { Map prefs ->
        def d = newDriver(prefs)
        d.updated()
        d.advance(60_000)
        expect(!d.sentCommands().any { it.contains("0xFC01") } && d.zigbee.writes.isEmpty() && d.state.rpcPending == null, "${prefs}: ${d.hubCommands}")
    }
}

check("plug settings: the power-on state uses the standard attribute when the plug has it, confirmed by the read-back") {
    def d = newDriver(powerOnState: "off")
    deliver(d, frame("0006", "4003", "30", "01", null, "01"))           // Configure found it supported, currently on
    d.updated()
    d.advance(30_000)
    expect(d.zigbee.writes == [[cluster: 0x0006, attr: 0x4003, type: 0x30, value: 0x00]], "write 4003 = 0: ${d.zigbee.writes}")
    expect(!d.sentCommands().any { it.contains("0xFC01") }, "no RPC needed: ${d.hubCommands}")
    deliver(d, frame("0006", "4003", "30", "00", null, "01"))           // the read-back
    expect(d.lastValue("lastSettingsWrite") == "power-on state off: accepted" && d.state.plugSettings == [initial_state: "off"], "${d.events} ${d.state}")
    d.updated(); d.advance(30_000)
    expect(d.zigbee.writes.size() == 1, "not sent again once accepted: ${d.zigbee.writes}")
    expect(d.zigbee.switchCommands.isEmpty(), "the relay is not touched")
}

check("plug settings: a refused 0006/4003 write (write response or read-back) falls back to RPC") {
    def d = newDriver(powerOnState: "previous")
    deliver(d, frame("0006", "4003", "30", "00", null, "01"))
    d.updated(); d.advance(30_000)
    deliver(d, catchall("0006", "04", ["88", "03", "40"]))             // READ_ONLY
    expect(d.lastValue("lastSettingsWrite") == "power-on state previous: refused (0006/4003 write: read-only attribute; trying RPC)", "${d.events}")
    expect(d.state.startUpOnOff == false, "4003 no longer used for writing: ${d.state}")
    d.advance(2_000)
    expect(rpcRequests(d) == ['{"id":1,"method":"Switch.SetConfig","params":{"id":0,"config":{"initial_state":"restore_last"}}}'], "RPC instead: ${rpcRequests(d)}")
    def e = newDriver(powerOnState: "on")
    deliver(e, frame("0006", "4003", "30", "00", null, "01"))
    e.updated(); e.advance(30_000)
    deliver(e, frame("0006", "4003", "30", "00", null, "01"))           // read-back still off
    expect(e.lastValue("lastSettingsWrite") == "power-on state on: refused (0006/4003 still reads off; trying RPC)" && e.state.startUpOnOff == false, "${e.events}")
}

check("plug settings: the power limit goes out as RPC Switch.SetConfig in 40-byte TxData chunks") {
    def d = newDriver(powerLimitWatts: 1500)
    d.state.wifiSupported = false                                       // no FC02 frames: the ZCL sequence numbers below start at 1
    d.updated()
    d.advance(30_000)
    String json = '{"id":1,"method":"Switch.SetConfig","params":{"id":0,"config":{"power_limit":1500}}}'
    List<String> rpc = d.sentCommands().findAll { it.contains("0xFC01") }
    expect(json.length() == 84 && rpc[0] == "he raw 0x7257 1 0xEF 0xFC01 {049014010201002354000000} {0xC001}", "TxCtl: 84 (0x54) as uint32 LE: ${rpc[0]}")
    expect(rpc[1] == "he raw 0x7257 1 0xEF 0xFC01 {0490140202000042" + "28" + hexOfText(json.substring(0, 40)) + "} {0xC001}", "chunk 1, 40 bytes: ${rpc[1]}")
    expect(rpc[2].contains("0490140302000042" + "28" + hexOfText(json.substring(40, 80))) && rpc[3].contains("0490140402000042" + "04" + hexOfText(json.substring(80))), "chunks 2 and 3: ${rpc}")
    expect(rpcRequests(d) == [json], "the chunks join into the request: ${rpcRequests(d)}")
    d.advance(2_000)
    expect(d.hubCommands.last() == ["he raw 0x7257 1 0xEF 0xFC01 {0490140500" + "0200} {0xC001}", "delay 300", "he raw 0x7257 1 0xEF 0xFC01 {0490140600" + "0000} {0xC001}"],
           "2 s later: read RxCtl (0002) then RxData (0000): ${d.hubCommands.last()}")
    expect(d.zigbee.switchCommands.isEmpty() && !d.sentCommands().any { it.contains("0x0006 0x0") && it.startsWith("he cmd") }, "the relay is not touched")
}

check("plug settings: a reply that opens with \"result\" is accepted and not sent again") {
    def d = newDriver(powerLimitWatts: 1500)
    d.updated(); d.advance(32_000)
    // what a plug returned on 2026-10-11: the opening only, then garbage (rxctl 80)
    deliver(d, rpcReply('{"id":1,"src":"shellyplugusg4-aabbcc000001","r' + "ýý", 80))
    expect(d.lastValue("lastSettingsWrite") == "power limit 1500 W: accepted", "${d.events}")
    expect(d.state.plugSettings == [power_limit: 1500] && d.state.rpcPending == null && d.dueAt.rpcTimeout == null, "${d.state} ${d.dueAt}")
    int sent = d.sentCommands().count { it.contains("0xFC01") }
    d.updated(); d.advance(60_000)
    expect(d.sentCommands().count { it.contains("0xFC01") } == sent, "unchanged settings are not re-sent")
}

check("plug settings: a reply judged across chunks and key orders") {
    def d = newDriver(powerLimitWatts: 1500)
    d.updated(); d.advance(32_000)
    chunk(d, '{"id":1,"src":"shellyplugusg4-aabbcc0')
    expect(d.state.rpcPending != null, "not judged on half an opening")
    chunk(d, '00001","dst":"hub","result":{"restart_')
    expect(d.lastValue("lastSettingsWrite") == "power limit 1500 W: accepted", "a dst key before result: ${d.events}")
}

check("recorded: 4612 accepted power_limit 1800, its reply tail arriving before its cut-short opening") {
    // 2026-10-15 09:36 from plug 4612 (fw 2.0.1): RxCtl 80; RxData 30 bytes of tail, then 46 of the 50-byte opening.
    // The plug's own id in src is replaced by a made-up one of the same length.
    def d = newDriver(powerLimitWatts: 1800)
    d.updated(); d.advance(32_000)
    deliver(d, [raw: "catchall", profileId: "C001", clusterId: "FC01", clusterInt: 0xFC01, sourceEndpoint: "EF", destinationEndpoint: "01",
                options: "0040", messageType: "00", dni: "281B", isClusterSpecific: false, isManufacturerSpecific: true, manufacturerId: "1490",
                command: "01", direction: "01", data: ["02", "00", "00", "23", "50", "00", "00", "00"]])
    deliver(d, rpcReply('t":{"restart_required":false}}', 30))
    expect(d.state.rpcPending != null && d.lastValue("lastSettingsWrite") == null, "a tail alone is not judged: ${d.events}")
    deliver(d, rpcReply('{"id":1,"src":"shellyplugusg4-aabbcc000001","r', 50))
    expect(d.lastValue("lastSettingsWrite") == "power limit 1800 W: accepted", "${d.events}")
}

check("plug settings: one setting at a time; error, foreign id, default response and silence each end as they should") {
    def d = newDriver(powerLimitWatts: 1500, powerOnState: "previous")
    d.updated(); d.advance(32_000)
    expect(rpcRequests(d) == ['{"id":1,"method":"Switch.SetConfig","params":{"id":0,"config":{"initial_state":"restore_last"}}}'], "only the first setting: ${rpcRequests(d)}")
    chunk(d, '{"id":7,"src":"shellyplugusg4-aabbcc000001","r')
    expect(d.state.rpcPending != null, "a reply to id 7 is not ours: ${d.state}")
    chunk(d, '{"id":1,"src":"shellyplugusg4-aabbcc000001","error":{"code":-103,"mes')
    expect(d.lastValue("lastSettingsWrite") == "power-on state previous: refused (error -103)", "${d.events}")
    expect(d.logsMatching("WARN Test Plug: power-on state previous: refused").size() == 1 && d.state.plugSettings == null, "${d.logLines}")
    d.advance(2_000)
    expect(rpcRequests(d)[1] == '{"id":2,"method":"Switch.SetConfig","params":{"id":0,"config":{"power_limit":1500}}}', "then the next setting: ${rpcRequests(d)}")
    deliver(d, [raw: "catchall", profileId: "C001", clusterId: "FC01", clusterInt: 0xFC01, sourceEndpoint: "EF", destinationEndpoint: "01",
                options: "0040", messageType: "00", dni: "7257", isClusterSpecific: false, isManufacturerSpecific: true, manufacturerId: "1490",
                command: "0B", direction: "01", data: ["02", "84"]])
    expect(d.lastValue("lastSettingsWrite") == "power limit 1500 W: refused (the plug answered unsupported manufacturer-specific command)", "${d.events}")
    d.advance(60_000)
    expect(rpcRequests(d).size() == 2, "refused settings wait for the next Save: ${rpcRequests(d)}")
    def e = newDriver(powerLimitWatts: 1200)
    e.updated(); e.advance(30_000 + 25_000)
    expect(e.lastValue("lastSettingsWrite") == null && rpcRequests(e).size() == 2, "silence: asked once more first: ${rpcRequests(e)}")
    e.advance(25_000)
    expect(e.lastValue("lastSettingsWrite") == "power limit 1200 W: unconfirmed (no readable answer in 2 tries; Save Preferences sends it again)" && e.state.plugSettings == null, "${e.events}")
    expect(e.sentCommands().count { it.contains("000200} {0xC001}") } >= 2, "it polled for the reply: ${e.hubCommands}")
}

check("plug settings: a second apply while a write is in flight waits for it") {
    def d = newDriver(powerLimitWatts: 1500)
    d.updated(); d.advance(30_000)
    d.settings.powerLimitWatts = 1400
    d.applyPlugSettings()
    expect(rpcRequests(d).size() == 1 && d.dueAt.applyPlugSettings == d.clockMs + 30_000, "still one request, retried in 30 s: ${rpcRequests(d)} ${d.dueAt}")
}

check("plug settings: the limit is clamped to 100..1800 W and never set below what the plug is drawing") {
    [[50, 100], [5000, 1800]].each { List io ->
        def d = newDriver(powerLimitWatts: io[0])
        d.updated(); d.advance(30_000)
        expect(rpcRequests(d) == ['{"id":1,"method":"Switch.SetConfig","params":{"id":0,"config":{"power_limit":' + io[1] + '}}}'], "${io}: ${rpcRequests(d)}")
    }
    def h = newDriver(powerLimitWatts: 1200)
    giveUnits(h, UNITS_020000FF)
    deliver(h, frame("0006", "0000", "10", "01"))
    deliver(h, frame("0B04", "050B", "29", hex(1150, 4)))
    h.updated(); h.advance(30_000)
    expect(rpcRequests(h).isEmpty(), "not sent under a 1150 W load: ${rpcRequests(h)}")
    expect(h.lastValue("lastSettingsWrite") == "power limit 1200 W: not sent (the plug is drawing 1150.0 W and would switch itself off)", "${h.events}")
}

check("bluetooth: on enables BLE and BLE RPC through BLE.SetConfig, confirmed by the reply; the relay is not touched") {
    def d = newDriver()
    d.setBluetooth("on")
    expect(rpcRequests(d) == ['{"id":1,"method":"BLE.SetConfig","params":{"config":{"enable":true,"rpc":{"enable":true}}}}'], "${rpcRequests(d)}")
    chunk(d, '{"id":1,"src":"shellyplugusg4-aabbcc000001","r')
    expect(d.lastValue("bluetooth") == "on" && d.lastValue("lastSettingsWrite") == "Bluetooth on: accepted", "${d.events}")
    expect(d.zigbee.switchCommands.isEmpty() && !d.sentCommands().any { it.contains("0x0006 0x0") && it.startsWith("he cmd") }, "the relay is not touched")
    d.advance(5_000)
    expect(rpcRequests(d).size() == 1, "no settings follow when none are set: ${rpcRequests(d)}")
}

check("bluetooth: off sends enable false; a refusal is reported; a write in flight blocks it") {
    def d = newDriver()
    d.setBluetooth("off")
    expect(rpcRequests(d) == ['{"id":1,"method":"BLE.SetConfig","params":{"config":{"enable":false}}}'], "${rpcRequests(d)}")
    d.setBluetooth("on")
    expect(rpcRequests(d).size() == 1 && d.lastValue("lastSettingsWrite") == "Bluetooth on: not sent (another write is in progress; try again in a minute)", "${d.events}")
    chunk(d, '{"id":1,"src":"shellyplugusg4-aabbcc000001","error":{"code":-103,"mes')
    expect(d.lastValue("lastSettingsWrite") == "Bluetooth off: refused (error -103)" && d.lastValue("bluetooth") == null, "${d.events}")
    d.setBluetooth("on")
    d.advance(50_000)
    expect(d.lastValue("lastSettingsWrite") == "Bluetooth on: unconfirmed (no readable answer in 2 tries; run setBluetooth again)", "${d.lastValue('lastSettingsWrite')}")
    expect(d.state.settingsSkip == ["bluetooth"], "a key is skipped once, however often it fails: ${d.state.settingsSkip}")
}

check("plug settings: a silent first try is asked once more, and the second answer counts") {
    // 2026-10-15: Bluetooth on to 4613 got no readable answer, the same request a minute later was accepted in 3 s.
    def d = newDriver()
    d.setBluetooth("on")
    d.advance(25_000)
    List<String> req = rpcRequests(d)
    expect(req.size() == 2 && req[1] == '{"id":2,"method":"BLE.SetConfig","params":{"config":{"enable":true,"rpc":{"enable":true}}}}', "same request, new id: ${req}")
    chunk(d, '{"id":1,"src":"shellyplugusg4-aabbcc000001","r')
    expect(d.state.rpcPending != null, "a late answer to the first try is not ours any more")
    chunk(d, '{"id":2,"src":"shellyplugusg4-aabbcc000001","r')
    expect(d.lastValue("lastSettingsWrite") == "Bluetooth on: accepted" && d.lastValue("bluetooth") == "on", "${d.events}")
}

check("plug settings: each TxData chunk waits for the plug to acknowledge the write before it") {
    // 2026-10-15 10:25: chunks sent 150 ms apart reached 4612 scrambled; it answered error -103 for "config".
    def d = newDriver()
    d.onHubCommand = null
    d.setBluetooth("on")
    String json = '{"id":1,"method":"BLE.SetConfig","params":{"config":{"enable":true,"rpc":{"enable":true}}}}'
    Closure fc01 = { d.sentCommands().findAll { it.contains("0xFC01") } }
    expect(fc01().size() == 1 && fc01()[0].contains("02010023" + String.format("%02X", json.length()) + "000000"), "TxCtl alone: ${fc01()}")
    d.advance(5_000)
    expect(fc01().size() == 1, "nothing more without an acknowledgement, no polling yet: ${fc01()}")
    [1, 2, 3].each { int n ->
        deliver(d, rpcWriteAck)
        expect(fc01().size() == n + 1 && fc01()[n].contains("02000042"), "chunk ${n} after acknowledgement ${n}: ${fc01()}")
    }
    expect(rpcRequests(d) == [json], "${rpcRequests(d)}")
    d.advance(5_000)
    expect(fc01().size() == 3 + 1, "no poll before the last chunk is acknowledged: ${fc01()}")
    deliver(d, rpcWriteAck)
    d.advance(2_000)
    expect(fc01().size() == 4 + 2 && fc01()[4].endsWith("000200} {0xC001}"), "then it reads RxCtl: ${fc01()}")
    Long due = d.dueAt.rpcTimeout
    deliver(d, rpcWriteAck)
    expect(fc01().size() == 6 && d.dueAt.rpcTimeout == due, "a duplicate acknowledgement sends nothing, moves nothing: ${fc01()} ${d.dueAt}")
}

check("plug settings: a stalled request (an acknowledgement lost) is asked again after 25 s") {
    def d = newDriver()
    d.onHubCommand = null
    d.setBluetooth("on")
    deliver(d, rpcWriteAck)
    d.advance(25_000)
    expect(rpcRequests(d).size() == 2 && rpcRequests(d)[1] == "", "a fresh TxCtl for id 2: ${d.sentCommands()}")
    expect(d.state.rpcPending.id == 2 && d.state.rpcPending.sent == 0, "${d.state.rpcPending}")
}

check("recorded: 4612's error -103, its opening cut short at \"e, is refused, not left unjudged") {
    // 2026-10-15 10:25 from plug 4612 (fw 2.0.1): RxCtl 112; RxData the opening cut to 50 bytes ending in ","e,
    // then ":{"code":-103,"message":"Missing or bad argum. The plug's own id in src is replaced by a made-up one.
    def d = newDriver()
    d.setBluetooth("on")
    d.advance(25_000)                                                       // the first try went unanswered
    deliver(d, rpcReply('{"id":2,"src":"shellyplugusg4-aabbcc000001","e', 50))
    expect(d.lastValue("lastSettingsWrite") == "Bluetooth on: refused (error, its code cut off)", "the opening alone is enough: ${d.events}")
    def e = newDriver()
    e.setBluetooth("on")
    e.advance(25_000)
    deliver(e, rpcReply('":{"code":-103,"message":"Missing or bad argum', 50))
    deliver(e, rpcReply('{"id":2,"src":"shellyplugusg4-aabbcc000001","e', 50))
    expect(e.lastValue("lastSettingsWrite") == "Bluetooth on: refused (error -103)", "with the code ahead of it: ${e.events}")
}

// --------------------------------------------------------------------------- Wi-Fi (WOR-781)

/** A catchall on FC02, Shelly's Wi-Fi setup cluster, as the plugs send them from endpoint 0xEF. */
def wifiFrame = { String command, List data ->
    [raw: "catchall", profileId: "C001", clusterId: "FC02", clusterInt: 0xFC02, sourceEndpoint: "EF", destinationEndpoint: "01",
     options: "0040", messageType: "00", dni: "7257", isClusterSpecific: false, isManufacturerSpecific: true, manufacturerId: "1490",
     command: command, direction: "01", data: data]
}
def wifiAck = { String status = "00" -> wifiFrame("04", [status]) }
def textBytes = { String s -> s.getBytes("UTF-8").collect { String.format("%02X", it & 0xFF) } }
def wifiStatusReply = { String status, String ip, String network = null ->
    wifiFrame("01", ["00", "00", "00", "42", String.format("%02X", status.length())] + textBytes(status) +
                    ["01", "00", "00", "42", String.format("%02X", ip.length())] + textBytes(ip) +
                    (network == null ? [] : ["05", "00", "00", "42", String.format("%02X", network.length())] + textBytes(network)))
}
/** Every FC02 frame sent so far, as [ZCL command, body]. */
def wifiSent = { drv ->
    drv.sentCommands().findAll { it.contains(" 0xFC02 ") }.collect { String c ->
        def m = c =~ /\{049014[0-9A-F]{2}([0-9A-F]{2})([0-9A-F]*)\}/
        m.find() ? [m.group(1), m.group(2)] : null
    }
}

check("wifi: setWifi sends enable and the network, then the password, then Action 1, each after an acknowledgement") {
    def d = newDriver()
    d.setWifi("Home Net", "correct horse 42")
    expect(wifiSent(d) == [["02", "0400" + "10" + "01" + "0500" + "42" + "08" + hexOfText("Home Net")]], "first write alone, no DHCP: ${wifiSent(d)}")
    expect(d.sentCommands().every { !it.contains(" 0xFC02 ") || it.startsWith("he raw 0x7257 1 0xEF 0xFC02 {049014") && it.endsWith("} {0xC001}") }, "endpoint EF, Shelly's profile and manufacturer code")
    d.advance(5_000)
    expect(wifiSent(d).size() == 1, "nothing more without an acknowledgement")
    deliver(d, wifiAck())
    expect(wifiSent(d)[1] == ["02", "0600" + "42" + "10" + hexOfText("correct horse 42")], "then the password: ${wifiSent(d)}")
    deliver(d, wifiAck())
    expect(wifiSent(d)[2] == ["02", "0200" + "20" + "01"], "then Action 1: ${wifiSent(d)}")
    deliver(d, wifiAck())
    expect(d.lastValue("lastSettingsWrite") == "Wi-Fi Home Net: accepted (wifiStatus and wifiIp show when it has joined)", "${d.events}")
    expect(d.state.wifiPending == null && d.dueAt.wifiTimeout == null && driverClass.WIFI_FRAMES.isEmpty(), "${d.state} ${d.dueAt}")
    expect(d.zigbee.switchCommands.isEmpty() && !d.sentCommands().any { it.contains("0x0006 0x0") && it.startsWith("he cmd") }, "the relay is not touched")
}

check("wifi: the password reaches the plug and nothing else: no log line, event or state holds it") {
    def d = newDriver(logEnable: true)
    String pw = "Zq7!secret pw"
    String hexPw = hexOfText(pw)
    List<String> states = []
    d.setWifi("Home Net", pw)
    states << groovy.json.JsonOutput.toJson(d.state)                    // while the password frame waits
    3.times { deliver(d, wifiAck()); states << groovy.json.JsonOutput.toJson(d.state) }
    d.advance(60_000)
    expect(d.sentCommands().any { it.contains(hexPw) }, "positive control: the password did go to the plug")
    String seen = d.logLines.join("\n") + "\n" + states.join("\n") + "\n" + groovy.json.JsonOutput.toJson(d.state) + "\n" + d.events.collect { "${it.value} ${it.descriptionText}" }.join("\n")
    expect(!seen.contains(pw) && !seen.toUpperCase().contains(hexPw), "the password shows up outside the frame sent to the plug")
}

check("wifi: an open network sends no password; a bad name or password is not sent; a second setWifi waits") {
    def d = newDriver()
    d.setWifi("Cafe", "")
    deliver(d, wifiAck())
    expect(wifiSent(d)[1] == ["02", "0200" + "20" + "01"], "Action 1 right after the network: ${wifiSent(d)}")
    d.setWifi("Other", "longenough1")
    expect(wifiSent(d).size() == 2 && d.lastValue("lastSettingsWrite") == "Wi-Fi Other: not sent (another Wi-Fi write is in progress; try again in a minute)", "${d.events}")
    deliver(d, wifiAck())
    expect(d.lastValue("lastSettingsWrite").startsWith("Wi-Fi Cafe: accepted"), "the first one still completes: ${d.events}")
    def e = newDriver()
    e.setWifi("", "longenough1")
    expect(e.lastValue("lastSettingsWrite") == "Wi-Fi: not sent (the network name must be 1 to 32 bytes)", "${e.events}")
    e.setWifi("x" * 33, "longenough1")
    e.setWifi("Home", "short")
    expect(e.lastValue("lastSettingsWrite") == "Wi-Fi Home: not sent (a password has 8 to 63 characters; leave it empty for an open network)", "${e.events}")
    e.setWifi("Home", "p" * 64)
    expect(wifiSent(e).isEmpty() && e.state.wifiPending == null && e.eventsNamed("lastSettingsWrite").size() == 4, "nothing sent: ${wifiSent(e)}")
}

check("recorded: 4613 refused a DHCP write as read-only; a refusal names the attribute and ends the attempt") {
    // 2026-10-15 11:24 from plug 4613 on 1.3.0, whose first write still carried DHCP (0x0003)
    def d = newDriver()
    d.setWifi("Home Net", "correct horse 42")
    deliver(d, wifiFrame("04", ["88", "03", "00"]))
    expect(d.lastValue("lastSettingsWrite") == "Wi-Fi Home Net: refused (attribute 0003: read-only attribute)", "${d.events}")
    expect(d.state.wifiPending == null && driverClass.WIFI_FRAMES.isEmpty() && wifiSent(d).size() == 1, "nothing more sent: ${wifiSent(d)}")
}

check("wifi: a refused write or no acknowledgement ends the attempt and drops its frames") {
    def d = newDriver()
    d.setWifi("Home Net", "correct horse 42")
    deliver(d, wifiFrame("04", ["87", "06", "00"]))                    // INVALID_VALUE for attribute 0006
    expect(d.lastValue("lastSettingsWrite") == "Wi-Fi Home Net: refused (attribute 0006: invalid value)", "${d.events}")
    expect(d.state.wifiPending == null && driverClass.WIFI_FRAMES.isEmpty() && wifiSent(d).size() == 1, "${d.state}")
    def e = newDriver()
    e.setWifi("Home Net", "correct horse 42")
    deliver(e, wifiAck())
    e.advance(30_000)
    expect(e.lastValue("lastSettingsWrite") == "Wi-Fi Home Net: unconfirmed (not every write was acknowledged in 30 s; run setWifi again)", "${e.events}")
    expect(e.state.wifiPending == null && driverClass.WIFI_FRAMES.isEmpty(), "${e.state}")
    deliver(e, wifiAck())
    expect(wifiSent(e).size() == 2, "a late acknowledgement sends nothing: ${wifiSent(e)}")
}

check("wifi: Status and IP are read back after Action 1 until the plug has an address, at most 4 times") {
    def d = newDriver()
    d.setWifi("Home Net", "correct horse 42")
    3.times { deliver(d, wifiAck()) }
    d.advance(10_000)
    List s = wifiSent(d)
    expect(s.size() == 5 && s[3] == ["02", "0200" + "20" + "00"] && s[4] == ["00", "0000" + "0100" + "0500"], "Action 0, then a read of Status, IP and SSID: ${s}")
    deliver(d, wifiStatusReply("connecting", ""))
    expect(d.lastValue("wifiStatus") == "connecting" && d.lastValue("wifiIp") == "none" && d.dueAt.wifiRead != null, "${d.events} ${d.dueAt}")
    d.advance(15_000)
    expect(wifiSent(d).size() == 7, "read again 15 s later: ${wifiSent(d)}")
    deliver(d, wifiStatusReply("got ip", "192.0.2.44"))                // RFC 5737 documentation address
    expect(d.lastValue("wifiStatus") == "got ip" && d.lastValue("wifiIp") == "192.0.2.44", "${d.events}")
    expect(d.dueAt.wifiRead == null && d.state.wifiReadsLeft == null, "no more reads once it has an address: ${d.dueAt}")
    def e = newDriver()
    e.setWifi("Home Net", "")
    2.times { deliver(e, wifiAck()) }
    e.advance(120_000)
    expect(wifiSent(e).count { it[0] == "00" } == 4 && e.dueAt.wifiRead == null, "gives up after 4 reads: ${wifiSent(e)}")
}

check("recorded: 4613 (fw 2.0.0) answers Status connecting, IP 0.0.0.0: no address yet, so the reads go on") {
    // 2026-10-15 11:22 from plug 4613 on 1.3.0, as the driver decoded it; its SSID attribute was not read yet.
    def d = newDriver()
    d.setWifi("Home Net", "")
    2.times { deliver(d, wifiAck()) }
    d.advance(10_000)
    deliver(d, wifiStatusReply("connecting", "0.0.0.0", ""))
    expect(d.lastValue("wifiStatus") == "connecting" && d.lastValue("wifiIp") == "none" && d.lastValue("wifiNetwork") == "none", "${d.events}")
    expect(d.dueAt.wifiRead != null, "still reading: ${d.dueAt}")
    deliver(d, wifiStatusReply("got ip", "192.0.2.44", "Home Net"))       // RFC 5737 documentation address
    expect(d.lastValue("wifiIp") == "192.0.2.44" && d.lastValue("wifiNetwork") == "Home Net" && d.dueAt.wifiRead == null, "${d.events} ${d.dueAt}")
}

check("wifi: refresh reads Status and IP while the cluster answers, without Action 0 during a setWifi; unsupported is remembered") {
    def d = newDriver()
    List<String> fc02 = d.refresh().findAll { it.contains(" 0xFC02 ") }
    expect(fc02.size() == 2 && fc02[0].contains("02" + "0200" + "20" + "00}") && fc02[1].contains("00" + "0000" + "0100" + "0500}"), "Action 0 then the read: ${fc02}")
    d.setWifi("Home Net", "correct horse 42")
    fc02 = d.refresh().findAll { it.contains(" 0xFC02 ") }
    expect(fc02.size() == 1 && fc02[0].contains("0000" + "0100" + "0500}"), "only the read while setWifi is staging: ${fc02}")
    def e = newDriver()
    deliver(e, wifiFrame("01", ["00", "00", "86", "01", "00", "86"]))
    expect(e.state.wifiSupported == false && !e.refresh().any { it.contains(" 0xFC02 ") }, "not read again: ${e.state}")
    List<String> c = e.configure()
    expect(e.state.wifiSupported == null && c.any { it.contains(" 0xFC02 ") }, "Configure asks again: ${c}")
}

check("recorded: 4612's 81-byte success to request id 14, its opening cut on the quote before result, is accepted") {
    // 2026-10-15 14:02 from plug 4612 (fw 2.0.1): RxCtl 81; RxData 46 of the first 50 bytes, ending in ,"; then the
    // 31-byte tail lt":{"restart_required":false}}. One more digit in the id than on 2026-10-15 09:36 moves the cut
    // one character earlier, from the r of result onto the quote. The plug's own id in src is replaced by a made-up one.
    def d = newDriver(powerLimitWatts: 1800)
    d.state.rpcId = 13
    d.updated(); d.advance(32_000)
    expect(rpcRequests(d)[0].startsWith('{"id":14,'), "${rpcRequests(d)}")
    deliver(d, rpcReply('{"id":14,"src":"shellyplugusg4-aabbcc000001","', 50))
    expect(d.state.rpcPending != null && d.lastValue("lastSettingsWrite") == null, "the opening alone names no outcome: ${d.events}")
    deliver(d, rpcReply('lt":{"restart_required":false}}', 31))
    expect(d.lastValue("lastSettingsWrite") == "power limit 1800 W: accepted", "${d.events}")
    def e = newDriver(powerLimitWatts: 1800)                                // an error cut the same way
    e.state.rpcId = 13
    e.updated(); e.advance(32_000)
    deliver(e, rpcReply('{"id":14,"src":"shellyplugusg4-aabbcc000001","', 50))
    deliver(e, rpcReply('r":{"code":-103,"message":"Missing or bad argum', 50))
    expect(e.lastValue("lastSettingsWrite") == "power limit 1800 W: refused (error -103)", "${e.events}")
}

check("plug settings: the RPC id wraps from 99 to 1; Configure forgets what the plug accepted") {
    def d = newDriver(powerLimitWatts: 1500)
    d.state.rpcId = 99
    d.updated(); d.advance(30_000)
    expect(rpcRequests(d) == ['{"id":1,"method":"Switch.SetConfig","params":{"id":0,"config":{"power_limit":1500}}}'], "${rpcRequests(d)}")
    chunk(d, '{"id":1,"src":"shellyplugusg4-aabbcc000001","r')
    expect(d.state.plugSettings == [power_limit: 1500], "${d.state}")
    d.configure()
    expect(d.state.plugSettings == null && d.state.startUpOnOff == null, "a reset plug keeps nothing: ${d.state}")
}

check("trip: 4613's sequence of 2026-10-13 19:10 (on, 1986 W, off 2 s later by the plug) counts as a trip") {
    def d = newDriver()
    giveUnits(d, UNITS_020000FF)
    d.on()
    d.advance(500)
    deliver(d, frame("0006", "0000", "10", "01"))                                             // on [digital]
    d.advance(1_300)
    deliver(d, frame("0B04", "0505", "21", hex(11440, 4), [more("0508", "21", hex(17349, 4)), more("050B", "29", hex(1986, 4))]))
    d.advance(1_100)
    deliver(d, frame("0006", "0000", "10", "00"))                                             // off, not from the hub
    expect(d.eventsNamed("switch").last().type == "physical", "${d.eventsNamed('switch')}")
    expect(d.lastValue("tripCount") == 1, "one trip: ${d.events}")
    expect(d.lastValue("lastTrip") == "switched itself off 2 s after it was switched on, at up to 1986 W (limit 1800 W): likely its overcurrent or overpower protection",
           "${d.lastValue('lastTrip')}")
    expect(d.logsMatching("WARN Test Plug: switched itself off").size() == 1, "${d.logLines}")
    d.on(); d.advance(500); deliver(d, frame("0006", "0000", "10", "01"))
    d.advance(2_000); deliver(d, frame("0006", "0000", "10", "00"))
    expect(d.lastValue("tripCount") == 2 && d.eventsNamed("lastTrip").size() == 2, "the second trip counts too: ${d.events}")
}

check("trip: a button press long after on at a light load is not a trip; a hub-sent off never is") {
    def d = newDriver()
    giveUnits(d, UNITS_020000FF)
    deliver(d, frame("0006", "0000", "10", "01"))
    deliver(d, frame("0B04", "050B", "29", hex(300, 4)))
    d.advance(600_000)
    deliver(d, frame("0006", "0000", "10", "00"))                                             // physical, 10 min later, 300 W
    expect(d.lastValue("tripCount") == null, "no trip: ${d.events}")
    d.on(); d.advance(1_000); deliver(d, frame("0006", "0000", "10", "01"))
    deliver(d, frame("0B04", "050B", "29", hex(1750, 4)))
    d.off(); d.advance(1_000); deliver(d, frame("0006", "0000", "10", "00"))                  // digital, even right after on
    expect(d.lastValue("tripCount") == null, "a commanded off is not a trip: ${d.events}")
}

check("trip: an off at 90% of the limit counts even long after on, and the limit follows the preference") {
    def d = newDriver(powerLimitWatts: 1500)
    giveUnits(d, UNITS_020000FF)
    deliver(d, frame("0006", "0000", "10", "01"))
    deliver(d, frame("0B04", "050B", "29", hex(1400, 4)))
    d.advance(3_600_000)
    deliver(d, frame("0006", "0000", "10", "00"))
    expect(d.lastValue("tripCount") == 1 && d.lastValue("lastTrip").startsWith("switched itself off under load, at up to 1400 W (limit 1500 W)"), "${d.events}")
}

check("power factor: int8 hundredths, negative too; unsupported is remembered and not read again") {
    def d = newDriver()
    d.configure()
    expect(d.zigbee.reads.any { it.cluster == 0x0B04 && it.attrs == [0x0510] }, "probed on Configure: ${d.zigbee.reads}")
    deliver(d, frame("0B04", "0510", "28", "62", null, "01"))
    expect(d.lastValue("powerFactor").toString() == "0.98" && d.state.powerFactorSupported == true, "${d.events}")
    deliver(d, frame("0B04", "0510", "28", "9C", null, "01"))
    expect(d.lastValue("powerFactor").toString() == "-1.00", "${d.lastValue('powerFactor')}")
    giveUnits(d, UNITS_020000FF)
    d.applyReporting()
    expect(d.zigbee.reportingFor(0x0B04, 0x0510)?.type == 0x28 && d.zigbee.reportingFor(0x0B04, 0x0510).change == 5, "reported once supported: ${d.zigbee.reporting}")
    expect(d.refresh().any { it.contains("0x0B04 0x0510") }, "and refreshed")
    def e = newDriver()
    deliver(e, catchall("0B04", "01", ["10", "05", "86"]))
    expect(e.state.powerFactorSupported == false && e.logsMatching("does not offer 0B04/0510").size() == 1, "${e.state} ${e.logLines}")
    expect(!e.refresh().any { it.contains("0x0B04 0x0510") }, "not read again")
}

// --------------------------------------------------------------------------- refresh, logging

check("refresh: reads switch, the four electrical values, energy and build; units only while missing") {
    def d = newDriver()
    List<String> cmds = d.refresh()
    expect(d.zigbee.reads.any { it.cluster == 0x0B04 && it.attrs == [0x0505, 0x0508, 0x050B, 0x0300] }, "${d.zigbee.reads}")
    expect(d.zigbee.reads.any { it.cluster == 0x0006 } && d.zigbee.reads.any { it.cluster == 0x0702 && it.attrs == [0x0000] }, "${d.zigbee.reads}")
    expect(cmds.any { it.contains("0x0B04 0x0600") }, "units read while unknown: ${cmds}")
    giveUnits(d, UNITS_020000FF)
    expect(!d.refresh().any { it.contains("0x0B04 0x0600") }, "not once known")
    expect(d.zigbee.switchCommands.isEmpty(), "no relay command")
}

check("logging: debug only when enabled; descriptionText follows txtEnable; logsOff turns debug off") {
    def d = newDriver(txtEnable: false)
    giveUnits(d, UNITS_020000FF)
    deliver(d, frame("0B04", "050B", "29", hex(1028, 4)))
    expect(d.logLines.findAll { it.startsWith("INFO") || it.startsWith("DEBUG") }.isEmpty(), "quiet: ${d.logLines}")
    d.settings.logEnable = true
    d.settings.txtEnable = true
    d.updated()
    expect(d.dueAt.logsOff == d.clockMs + 1_800_000, "logsOff in 30 min: ${d.dueAt}")
    deliver(d, frame("0B04", "050B", "29", hex(1029, 4)))
    expect(!d.logsMatching("DEBUG Test Plug: parse:").isEmpty() && d.logsMatching("INFO Test Plug power is 1029.0 W").size() == 1, "${d.logLines}")
    deliver(d, frame("0B04", "050B", "29", hex(1029, 4)))
    expect(d.logsMatching("INFO Test Plug power is 1029.0 W").size() == 1, "an unchanged reading is not logged again: ${d.logLines}")
    d.logsOff()
    expect(d.device.updatedSettings.logEnable == [value: "false", type: "bool"], "${d.device.updatedSettings}")
}

check("parse: a message the hub could not map, or one for another cluster, is ignored quietly") {
    def d = newDriver()
    d.parse("never registered")
    deliver(d, frame("0004", "0000", "21", "0000"))
    deliver(d, catchall("0B04", "FF", ["00"]))
    expect(d.events.findAll { it.name != "healthStatus" }.isEmpty() && d.logLines.findAll { it.startsWith("WARN") }.isEmpty(), "${d.events} ${d.logLines}")
}

// --------------------------------------------------------------------------- summary

println ""
println "  harness : ${HubitatDriverStub.simpleName} (no hub, no plug)"
println "  driver  : ${driverFile.name}"
println "  result  : ${checks - failures}/${checks} checks passed"
if (failures) {
    println ""
    failureDetail.each { println "  - ${it}" }
}
System.exit(failures ? 1 : 0)
