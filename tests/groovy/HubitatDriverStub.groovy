/**
 * Minimal stand-in for the Hubitat driver runtime, used as the scriptBaseClass when the driver is
 * parsed, so its own methods run off-hub and their behaviour can be asserted: the events it sends, the
 * Zigbee commands it returns or hands to sendHubCommand, what it schedules and what it logs.
 *
 * The driver's `metadata { }` block runs against a recorder, so a test can read the declared
 * capabilities, attributes, fingerprint and preferences.
 *
 * Nothing in this file talks to a hub, a device or a network.
 */
abstract class HubitatDriverStub extends Script {

    // --- recorded runtime state -----------------------------------------------------
    Map<String, Object> state = [:]
    Map<String, Object> settings = [:]
    List<Map> events = []                    // every sendEvent payload, in order, with isStateChange
    List<String> logLines = []
    List<List<String>> hubCommands = []      // every sendHubCommand batch
    Map<String, Long> dueAt = [:]            // runIn handler -> absolute due time
    Map<String, Integer> periodic = [:]      // runEveryNMinutes handler -> minutes
    MetadataRecorder metadataRecord
    Closure onHubCommand                     // a simulated device answering what was sent, or null
    FakeDevice device = new FakeDevice()
    FakeZigbee zigbee = new FakeZigbee()

    /** Millisecond clock. Tests move it; nothing here reads the real time. */
    Long clockMs = 1_760_000_000_000L

    // --- property resolution --------------------------------------------------------
    // A driver reads its preferences as bare properties (logEnable, txtEnable). Anything that is not a
    // real field of this class resolves against `settings`; an unset preference reads as null.
    private static final Set<String> FIELDS = (
        HubitatDriverStub.declaredFields*.name + HubitatDriverStub.superclass.declaredFields*.name
    ).toSet()

    @Override
    Object getProperty(String name) {
        if (FIELDS.contains(name)) return super.getProperty(name)
        if (settings.containsKey(name)) return settings[name]
        try {
            return super.getProperty(name)
        } catch (MissingPropertyException ignored) {
            return null
        }
    }

    @Override
    void setProperty(String name, Object value) {
        if (FIELDS.contains(name)) {
            super.setProperty(name, value)
            return
        }
        settings[name] = value
    }

    // --- Hubitat DSL ----------------------------------------------------------------
    def metadata(Closure c) {
        metadataRecord = new MetadataRecorder()
        c.delegate = metadataRecord
        c.resolveStrategy = Closure.DELEGATE_FIRST
        c.call()
        null
    }

    // --- device API -----------------------------------------------------------------
    /** Records the event and updates the device's current value, as the hub does. */
    def sendEvent(Map m) {
        Map e = new LinkedHashMap(m)
        e.isStateChange = device.current[m.name as String]?.toString() != m.value?.toString()
        events << e
        device.current[m.name as String] = m.value
        null
    }

    def sendHubCommand(hubitat.device.HubMultiAction action) {
        hubCommands << action.cmds
        if (onHubCommand != null) onHubCommand.call(action.cmds)
        null
    }

    // --- scheduling -----------------------------------------------------------------
    def runIn(Number seconds, String handler, Map opts = [:]) {
        dueAt[handler] = clockMs + ((seconds as BigDecimal) * 1000G).longValue()
        null
    }
    def runEvery15Minutes(String handler) { periodic[handler] = 15; null }
    def unschedule() { dueAt.clear(); periodic.clear(); null }
    def unschedule(String handler) { dueAt.remove(handler); periodic.remove(handler); null }

    /** Move the clock to `t`, firing every runIn job that falls due on the way, in due-time order. */
    void runUntil(Long t) {
        int guard = 0
        while (guard++ < 10_000) {
            def due = dueAt.findAll { it.value <= t }
            if (!due) break
            def next = due.min { it.value }
            clockMs = Math.max(clockMs, next.value)
            dueAt.remove(next.key)
            this.invokeMethod(next.key, [] as Object[])
            persistState()
        }
        clockMs = Math.max(clockMs, t)
    }

    void advance(long ms) { runUntil(clockMs + ms) }

    /**
     * The hub stores `state` as JSON after every execution, so a Long may come back as an Integer and a
     * nested map as a fresh map. The harness calls this after each delivered message and each fired job.
     */
    void persistState() {
        state = (Map) new groovy.json.JsonSlurper().parseText(groovy.json.JsonOutput.toJson(state))
    }

    // --- logging --------------------------------------------------------------------
    def getLog() {
        def self = this
        return [
            info : { Object m -> self.logLines << "INFO ${m}".toString() },
            warn : { Object m -> self.logLines << "WARN ${m}".toString() },
            error: { Object m -> self.logLines << "ERROR ${m}".toString() },
            debug: { Object m -> self.logLines << "DEBUG ${m}".toString() },
            trace: { Object m -> self.logLines << "TRACE ${m}".toString() },
        ]
    }

    Long now() { clockMs }

    // --- test helpers ---------------------------------------------------------------
    /** Events named `name`, oldest first. */
    List<Map> eventsNamed(String name) { events.findAll { it.name == name } }

    /** The latest value sent for `name`, or null. */
    def lastValue(String name) { eventsNamed(name) ? eventsNamed(name).last().value : null }

    List<String> logsMatching(String needle) { logLines.findAll { it.contains(needle) } }

    /** Every command the driver sent through sendHubCommand, flattened. */
    List<String> sentCommands() { hubCommands.flatten() as List<String> }
}

/** Records what the driver's metadata block declares. */
class MetadataRecorder {
    Map definition = [:]
    List<String> capabilities = []
    Map<String, List> attributes = [:]
    List<String> commands = []
    List<Map> fingerprints = []
    List<Map> inputs = []

    def definition(Map m, Closure c) {
        definition = new LinkedHashMap(m)
        c.delegate = this
        c.resolveStrategy = Closure.DELEGATE_FIRST
        c.call()
    }
    def capability(String name) { capabilities << name }
    def attribute(String name, String type, List values = null) { attributes[name] = [type, values] }
    def command(String name, List args = null) { commands << name }
    def fingerprint(Map m) { fingerprints << new LinkedHashMap(m) }
    def preferences(Closure c) {
        c.delegate = this
        c.resolveStrategy = Closure.DELEGATE_FIRST
        c.call()
    }
    def section(String title, Closure c) { preferences(c) }
    def input(Map m) { inputs << new LinkedHashMap(m) }
    Map inputNamed(String name) { inputs.find { it.name == name } }
}

/** The driver's `device`: identity, data values, current states, and the settings calls it makes. */
class FakeDevice {
    String displayName = "Test Plug"
    String deviceNetworkId = "7257"
    String endpointId = "01"
    Map<String, String> data = [manufacturer: "Shelly", model: "Plug US", softwareBuild: "020000FF"]
    Map<String, Object> current = [:]
    List<String> removedSettings = []
    List<String> deletedStates = []
    Map<String, Map> updatedSettings = [:]
    /** Wired by the test harness to the stub's settings map, as removeSetting deletes the saved value on the hub. */
    Closure onRemoveSetting

    String getDataValue(String key) { data[key] }
    void updateDataValue(String key, String value) { data[key] = value }
    def currentValue(String attr) { current[attr] }
    void removeSetting(String name) {
        removedSettings << name
        onRemoveSetting?.call(name)
    }
    void updateSetting(String name, Map value) { updatedSettings[name] = value }
    void deleteCurrentState(String name) {
        deletedStates << name
        current.remove(name)
    }
}

/**
 * The driver's `zigbee` helper. Command builders return strings shaped like the hub's ("he rattr",
 * "zdo bind", "he cr", "he cmd") and record each call, so a test can assert on either.
 * parseDescriptionAsMap returns the map a test registered for a description string.
 */
class FakeZigbee {
    Map<String, Map> descriptions = [:]
    List<Map> reads = []
    List<Map> reporting = []
    List<String> switchCommands = []

    Map parseDescriptionAsMap(String description) {
        Map m = descriptions[description]
        return m == null ? null : (Map) new groovy.json.JsonSlurper().parseText(groovy.json.JsonOutput.toJson(m))
    }

    List<String> readAttribute(Integer cluster, Integer attr, Map params = [:], int delay = 200) {
        return readAttribute(cluster, [attr], params, delay)
    }

    List<String> readAttribute(Integer cluster, List attrs, Map params = [:], int delay = 200) {
        reads << [cluster: cluster, attrs: attrs.collect { it as Integer }, params: params]
        String ids = attrs.collect { String.format("0x%04X", it as Integer) }.join(" ")
        return ["he rattr 0x7257 0x01 ${String.format('0x%04X', cluster)} ${ids} {}".toString(), "delay ${delay}".toString()]
    }

    List<String> configureReporting(Integer cluster, Integer attr, Integer type, Integer min, Integer max, Integer change = null, Map params = [:], int delay = 200) {
        reporting << [cluster: cluster, attr: attr, type: type, min: min, max: max, change: change, params: params]
        String c = String.format("0x%04X", cluster)
        return [
            "zdo bind 0x7257 0x01 0x01 ${c} {AABBCCFFFE000001} {}".toString(), "delay ${delay}".toString(),
            "he cr 0x7257 0x01 ${c} ${String.format('0x%04X', attr)} ${String.format('0x%02X', type)} ${min} ${max} {${change}} {}".toString(), "delay ${delay}".toString(),
        ]
    }

    List<String> on() {
        switchCommands << "on"
        return ["he cmd 0x7257 0x01 0x0006 0x01 {}", "delay 200"]
    }

    List<String> off() {
        switchCommands << "off"
        return ["he cmd 0x7257 0x01 0x0006 0x00 {}", "delay 200"]
    }

    List<Map> clusterCommands = []
    List<Map> writes = []

    List<String> writeAttribute(Integer cluster, Integer attr, Integer type, Integer value, Map params = [:], int delay = 200) {
        writes << [cluster: cluster, attr: attr, type: type, value: value]
        return ["he wattr 0x7257 0x01 ${String.format('0x%04X', cluster)} ${String.format('0x%04X', attr)} ${String.format('0x%02X', type)} {${String.format('%02X', value)}}".toString(), "delay 200"]
    }

    List<String> command(Integer cluster, Integer cmd, String... payload) {
        clusterCommands << [cluster: cluster, command: cmd, payload: payload.join("")]
        return ["he cmd 0x7257 0x01 ${String.format('0x%04X', cluster)} ${String.format('0x%02X', cmd)} {${payload.join('')}}".toString(), "delay 200"]
    }


    /** The reporting call for a cluster/attribute pair, or null. */
    Map reportingFor(int cluster, int attr) { reporting.reverse().find { it.cluster == cluster && it.attr == attr } }
}
