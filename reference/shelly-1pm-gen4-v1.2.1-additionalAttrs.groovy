/**
 * Shelly 1PM Gen 4 Zigbee Driver para Hubitat Elevation
 *
 * Este driver permite controlar e monitorar o dispositivo Shelly 1PM Gen 4 através do protocolo Zigbee.
 * O dispositivo é um relé com medição de energia que suporta as seguintes funcionalidades:
 * - Ligar/desligar (switch)
 * - Medição de potência (power em W)
 * - Medição de tensão (voltage em V)
 * - Medição de frequência AC (ac_frequency em Hz)
 * - Medição de corrente (current em A)
 * - Medição de energia produzida (produced_energy em kWh)
 *
 * Baseado na documentação do Zigbee2MQTT: https://www.zigbee2mqtt.io/devices/S4SW-001P16EU.html
 * Modelo: S4SW-001P16EU
 * Fabricante: Shelly
 * Descrição: 1PM Gen 4
 *
 * Copyright 2025
 * Licenciado sob a Licença Apache, Versão 2.0
 * version 1.0.0 2025-04-17 Joao - Initial version
 * version 1.1.0 2025-04-02 kkossev - Small improvements
 * version 1.1.1 2025-05-19 Joao - Translation to English
 * version 1.2.0 2025-08-21 kkossev - Shelly RPC cluster tests (Hubitat platform have a bug preventing the RPC cluster from working correctly)
 * version 1.2.1 2025-08-22 kkossev - added wifiInfo; added runRpcStateMachine()
 * version 1.2.1-additionalAttrs 2026-10-11 Chris Busch - reads every attribute of a bundled report (local change, not upstream)
  */

static String version()   { '1.2.1-additionalAttrs' }
static String timeStamp() { '2025/08/22 5:35 PM' }

import groovy.transform.Field
import groovy.json.JsonOutput
import hubitat.zigbee.zcl.DataType

/**
 * Driver definition with capabilities and attributes
 */
metadata {
    definition(
        name: "Shelly 1PM Gen 4 Zigbee Driver",
        namespace: "hubitat",
        author: "Joao+Manus, kkossev+Claude :)",
        importUrl: "https://raw.githubusercontent.com/joaomf/hubitat/refs/heads/master/Shelly%201PM%20Gen%204%20Zigbee%20Driver.groovy"
    ) {
        // Basic capabilities
        capability "Actuator"        // Allows the device to be controlled
        capability "Switch"          // On/off support
        capability "Refresh"         // Allows you to manually update the values
        capability "Configuration"   // Allows you to configure the device
        
        // Capabilities for energy measurement
        capability "PowerMeter"        // For power measurement (W)
        capability "VoltageMeasurement" // For voltage measurement (V)
        capability "CurrentMeter"      // For current measurement (A)
        capability "EnergyMeter"       // For energy measurement (kWh)
        
        // Custom attributes
        attribute "acFrequency", "number" // For AC frequency (Hz)
        attribute "producedEnergy", "number" // For energy produced (kWh)
        attribute "inputMode", "string" // Current input mode
        attribute "rpcStatus", "string" // RPC communication status
        attribute "rpcResponse", "string" // Last assembled RPC JSON response
        
        // Custom commands for input mode configuration
        //command "setInputMode", [[name: "mode", type: "ENUM", constraints: ["momentary", "follow", "flip", "detached", "cycle", "activate"]]]
        //command "getInputMode"
        
        // Simple RPC test command
        //command "testSimpleRpcLong"
        command "runRpcStateMachine", [[name: "jsonRequest", type: "STRING", description: "JSON-RPC request (optional, defaults to GetDeviceInfo)"]]
        command "wifiInfo"
       
        // Device fingerprint
        fingerprint profileId: "0104", 
                    inClusters: "0000,0003,0004,0005,0006,0B04,0702", 
                    outClusters: "0019", 
                    manufacturer: "Shelly", 
                    model: "1PM", 
                    deviceJoinName: "Shelly 1PM Gen 4"
    }
    
    /**
     * User configurable preferences
     */
    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logs", defaultValue: false
        input name: "txtEnable", type: "bool", title: "Enable text logs", defaultValue: true
        
        // Report settings
        input name: "reportingInterval", type: "number", title: "Report interval (seconds)", defaultValue: 60, range: "10..3600"
        
        // Calibration settings
        input name: "powerCalibration", type: "decimal", title: "Power calibration (%)", defaultValue: 0
        input name: "voltageCalibration", type: "decimal", title: "Voltage calibration (%)", defaultValue: 0
        input name: "currentCalibration", type: "decimal", title: "Current calibration (%)", defaultValue: 0
        input name: "acFrequencyCalibration", type: "decimal", title: "AC frequency calibration (offset)", defaultValue: 0
        
    // ...existing code...
    }
}

// Constants for Zigbee clusters
@Field static final Integer CLUSTER_BASIC = 0x0000
@Field static final Integer CLUSTER_ON_OFF = 0x0006
@Field static final Integer CLUSTER_SIMPLE_METERING = 0x0702
@Field static final Integer CLUSTER_ELECTRICAL_MEASUREMENT = 0x0B04
// Shelly RPC cluster/transport
@Field static final Integer CLUSTER_RPC = 0xFC01
@Field static final Integer EP_RPC = 0xEF
@Field static final Integer PROFILE_RPC = 0xC001
@Field static final Integer MFG_SHELLY = 0x1490

// Shelly RPC attributes
@Field static final Integer ATTR_TX_CTL = 0x0001
@Field static final Integer ATTR_RX_CTL = 0x0002
@Field static final Integer ATTR_TX_DATA_BASE = 0x0000
@Field static final Integer ATTR_RX_DATA_BASE = 0x0000

// Constants for attributes
@Field static final Integer ATTR_ON_OFF = 0x0000
@Field static final Integer ATTR_PRESENT_VALUE = 0x0055
@Field static final Integer ATTR_ACTIVE_POWER = 0x050B
@Field static final Integer ATTR_RMS_VOLTAGE = 0x0505
@Field static final Integer ATTR_RMS_CURRENT = 0x0508
@Field static final Integer ATTR_AC_FREQUENCY = 0x0300
@Field static final Integer ATTR_ENERGY_DELIVERED = 0x0000
@Field static final Integer ATTR_ENERGY_PRODUCED = 0x0001
@Field static final Integer ATTR_POWER_FACTOR = 0x0510

// Constants for dividers
@Field static final Integer DIVISOR_POWER = 100
@Field static final Integer DIVISOR_VOLTAGE = 100
@Field static final Integer DIVISOR_CURRENT = 100
@Field static final Integer DIVISOR_FREQUENCY = 100
@Field static final Integer DIVISOR_ENERGY = 1000000

/**
 * Method called when the device is installed
 */
def installed() {
    logDebug "Installed"
    initialize()
}

/**
 * Method called when device settings are updated
 */
def updated() {
    logDebug "Updated"
    initialize()
}

/**
 * Initializes the device and configures the reports
 */
def initialize() {
    logDebug "Initializing"
    unschedule()
    
    if (logEnable) {
        runIn(14400, "logsOff")  // Disables debug logs after 4 hours
    }
    
    // Configure reports
    configure()
}

/**
 * Disables debugging logs
 */
def logsOff() {
    log.warn "Debug logs disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

/**
 * Configures the device's periodic reports
 * @return List of Zigbee commands for configuration
 */
def configure() {
    logDebug "Configuring periodic reports"
    
    def interval = reportingInterval ? reportingInterval : 60
    def minInterval = 10
    def maxInterval = interval as Integer //Explicitly converting to Integer
    
    def cmds = []
    
    // Configure report for on/off state
    cmds += zigbee.configureReporting(CLUSTER_ON_OFF, ATTR_ON_OFF, DataType.BOOLEAN, 0, maxInterval, 1)
    
    // Configure report for power
    cmds += zigbee.configureReporting(CLUSTER_ELECTRICAL_MEASUREMENT, ATTR_ACTIVE_POWER, DataType.INT16, minInterval, maxInterval, 1)
    
    // Configure report for voltage
    cmds += zigbee.configureReporting(CLUSTER_ELECTRICAL_MEASUREMENT, ATTR_RMS_VOLTAGE, DataType.UINT16, minInterval, maxInterval, 1)
    
    // Set up report for current
    cmds += zigbee.configureReporting(CLUSTER_ELECTRICAL_MEASUREMENT, ATTR_RMS_CURRENT, DataType.UINT16, minInterval, maxInterval, 1)
    
    // Configure report for AC frequency
    cmds += zigbee.configureReporting(CLUSTER_ELECTRICAL_MEASUREMENT, ATTR_AC_FREQUENCY, DataType.UINT16, minInterval, maxInterval, 1)
    
    // Set up report for energy
    cmds += zigbee.configureReporting(CLUSTER_SIMPLE_METERING, ATTR_ENERGY_DELIVERED, DataType.UINT48, minInterval, maxInterval, 1)
    cmds += zigbee.configureReporting(CLUSTER_SIMPLE_METERING, ATTR_ENERGY_PRODUCED, DataType.UINT48, minInterval, maxInterval, 1)
    
    logDebug "Sending configuration commands: ${cmds}"
    return cmds
}

/**
 * Manually updates device values
 * @return List of Zigbee commands for reading attributes
 */
def refresh() {
    logDebug "Updating values"
    
    def cmds = []
    
    // Read on/off status
    cmds += zigbee.readAttribute(CLUSTER_ON_OFF, ATTR_ON_OFF)
    
    // Read power
    cmds += zigbee.readAttribute(CLUSTER_ELECTRICAL_MEASUREMENT, ATTR_ACTIVE_POWER)
    
    // Read voltage
    cmds += zigbee.readAttribute(CLUSTER_ELECTRICAL_MEASUREMENT, ATTR_RMS_VOLTAGE)
    
    // Read current
    cmds += zigbee.readAttribute(CLUSTER_ELECTRICAL_MEASUREMENT, ATTR_RMS_CURRENT)
    
    // Read AC frequency
    cmds += zigbee.readAttribute(CLUSTER_ELECTRICAL_MEASUREMENT, ATTR_AC_FREQUENCY)
    
    // Read energy
    cmds += zigbee.readAttribute(CLUSTER_SIMPLE_METERING, ATTR_ENERGY_DELIVERED)
    cmds += zigbee.readAttribute(CLUSTER_SIMPLE_METERING, ATTR_ENERGY_PRODUCED)
    
    logDebug "Sending update commands: ${cmds}"
    return cmds
}

/**
 * Turn on the device
 * @return Zigbee command to turn on
 */
def on() {
    logDebug "Turning on"
    return zigbee.on()
}

/**
 * Turn off the device
 * @return Zigbee command to turn off
 */
def off() {
    logDebug "Turning off"
    return zigbee.off()
}

/**
 * Processes Zigbee messages received from the device
 * @param Description String containing the description of the Zigbee message
 * @return null
 */
def parse(String description) {
    logDebug "Received: ${description}"
    
    def descMap = zigbee.parseDescriptionAsMap(description)
    logDebug "Description map: ${descMap}"
    
    if (descMap.cluster == "0006" && descMap.attrId == "0000") {
        // Processando estado on/off
        processSwitchState(descMap)
    } else if (descMap.cluster == "0B04") {
        // Processando medições elétricas
        processElectricalMeasurement(descMap)
        // The device bundles several attributes into one report (e.g. current + active power);
        // without this the bundled values were dropped and power froze. (Local patch 2026-10-11)
        descMap.additionalAttrs?.each { Map a -> processElectricalMeasurement([attrId: a.attrId, value: a.value, encoding: a.encoding]) }
    } else if (descMap.cluster == "0702") {
        // Processando medições de energia
        processEnergyMeasurement(descMap)
        descMap.additionalAttrs?.each { Map a -> processEnergyMeasurement([attrId: a.attrId, value: a.value, encoding: a.encoding]) }
    } else if ((descMap.cluster in ["FC01", "fc01"]) || (descMap.clusterId in ["FC01", "fc01"]) || (descMap.clusterInt == CLUSTER_RPC)) {
        processRpcCluster(descMap)
    } else if ((descMap.cluster in ["FC02", "fc02"]) || (descMap.clusterId in ["FC02", "fc02"]) || (descMap.clusterInt == 0xFC02)) {
        processWifiCluster(descMap)
    }
    
    return null
}


/**
 * Processes the state of the switch (on/off)
 * @param descMap Map containing the attribute data
 */
private void processSwitchState(Map descMap) {
    if (descMap.value == null) return
    
    def value = descMap.value == "01" ? "on" : "off"
    logInfo "Switch state: ${value}"
    
    sendEvent(name: "switch", value: value)

}

/**
 * Processes electrical measurements (power, voltage, current, frequency)
 * @param descMap Map containing the attribute data
 */
private void processElectricalMeasurement(Map descMap) {
    if (descMap.value == null) return
    
    String attrId = descMap.attrId
    Integer rawValue = Integer.parseInt(descMap.value, 16)
    if (rawValue == null) return // Ignores null values
    
    switch (attrId) {
        case "050B": // Active power
            BigDecimal powerDouble = new BigDecimal(rawValue).divide(new BigDecimal(DIVISOR_POWER), 2, BigDecimal.ROUND_HALF_UP)
            if (powerCalibration) {
                powerDouble = powerDouble.multiply(new BigDecimal(1 + (powerCalibration / 100)))
            }
            String powerString = String.format("%.2f", powerDouble)
            logInfo "Power: ${powerString} W"
            sendEvent(name: "power", value: powerString, unit: "W")
            break
            
        case "0505": // RMS voltage
            BigDecimal voltageDouble = new BigDecimal(rawValue).divide(new BigDecimal(DIVISOR_VOLTAGE), 1, BigDecimal.ROUND_HALF_UP)
            if (voltageCalibration) {
                voltageDouble = voltageDouble.multiply(new BigDecimal(1 + (voltageCalibration / 100)))
            }
            String voltageString = String.format("%.1f", voltageDouble)
            logInfo "Voltage: ${voltageString} V"
            sendEvent(name: "voltage", value: voltageString, unit: "V")
            break
            
        case "0508": // RMS current
            BigDecimal currentDouble = new BigDecimal(rawValue).divide(new BigDecimal(DIVISOR_CURRENT), 3, BigDecimal.ROUND_HALF_UP)
            if (currentCalibration) {
                currentDouble = currentDouble.multiply(new BigDecimal(1 + (currentCalibration / 100)))
            }
            String currentString = String.format("%.3f", currentDouble)
            logInfo "Current: ${currentString} A"
            sendEvent(name: "amperage", value: currentString, unit: "A")
            break
            
        case "0300": // AC Frequency
            BigDecimal frequencyDouble = new BigDecimal(rawValue).divide(new BigDecimal(DIVISOR_FREQUENCY), 2, BigDecimal.ROUND_HALF_UP)
            if (acFrequencyCalibration) {
                frequencyDouble = frequencyDouble.add(new BigDecimal(acFrequencyCalibration))
            }
            String frequencyString = String.format("%.2f", frequencyDouble)
            logInfo "AC Frequency: ${frequencyString} Hz"
            sendEvent(name: "acFrequency", value: frequencyString, unit: "Hz")
            break
    }
}

/**
 * Processes energy measurements (consumed and produced)
 * @param descMap Map containing the attribute data
 */
private void processEnergyMeasurement(Map descMap) {
    if (descMap.value == null) return
    
    String attrId = descMap.attrId
    Long rawValue = Long.parseLong(descMap.value, 16)
    if (rawValue == null) return // Ignores null values
    
    switch (attrId) {
        case "0000": // Energy delivered (consumed)
            BigDecimal energyDouble = new BigDecimal(rawValue).divide(new BigDecimal(DIVISOR_ENERGY), 3, BigDecimal.ROUND_HALF_UP)
            String energyString = String.format("%.3f", energyDouble)
            logInfo "Energy: ${energyString} kWh"
            sendEvent(name: "energy", value: energyString, unit: "kWh")
            break
            
        case "0001": // Energy produced
            BigDecimal producedEnergyDouble = new BigDecimal(rawValue).divide(new BigDecimal(DIVISOR_ENERGY), 3, BigDecimal.ROUND_HALF_UP)
            String producedEnergyString = String.format("%.3f", producedEnergyDouble)
            logInfo "Produced Energy: ${producedEnergyString} kWh"
            sendEvent(name: "producedEnergy", value: producedEnergyString, unit: "kWh")
            break
    }
}

/**
 * Registers debugging message if enabled
 * @param msg Message to be registered
 */
private void logDebug(String msg) {
    if (logEnable) {
        log.debug "${device.displayName}: ${msg}"
    }
}

/**
 * Register informative message if enabled
 * @param msg Message to be registered
 */
private void logInfo(String msg) {
    if (txtEnable) {
        log.info "${device.displayName}: ${msg}"
    }
}

/**
 * Register error message
 * @param msg Message to be registered
 */
private void logError(String msg) {
    log.error "${device.displayName}: ${msg}"
}

/**
 * Register warning message
 * @param msg Message to be registered
 */
private void logWarn(String msg) {
    log.warn "${device.displayName}: ${msg}"
}

// Always use default Shelly manufacturer code
private Integer getRpcMfgCode() {
    return MFG_SHELLY
}

// Little-endian hex helpers (accept boxed Integer to avoid runtime MissingMethodException)
private static String hexLE16(Integer v) {
    int iv = (v == null) ? 0 : v.intValue()
    return String.format('%02X%02X', iv & 0xFF, (iv >> 8) & 0xFF)
}

private static String hexLE16(int v) {
    return String.format('%02X%02X', v & 0xFF, (v >> 8) & 0xFF)
}

private static String hexLE32(int v) {
    return String.format('%02X%02X%02X%02X', v & 0xFF, (v >> 8) & 0xFF, (v >> 16) & 0xFF, (v >> 24) & 0xFF)
}

/**
 * ZCL sequence number helper.
 * Keeps a small counter in state.zclSeq and returns next value 0..255.
 */
private int nextZclSeqNum() {
    try {
        Integer cur = (state?.zclSeq instanceof Integer) ? (Integer) state.zclSeq : null
        if (cur == null) cur = 0
        cur = (cur + 1) & 0xFF
        state.zclSeq = cur
        return cur
    } catch (Exception e) {
        state.zclSeq = 1
        return 1
    }
}

/**
 * Command: WiFi Info
 * Reads common attributes from the Shelly WiFiSetupCluster (0xFC02)
 * and logs values at info level.
 */
def wifiInfo() {
    logInfo "🔎 Reading Shelly WiFiSetupCluster (0xFC02) attributes..."
    def attrs = [
        0x0000, // SSID
        0x0001, // BSSID
        0x0002, // Channel
        0x0003, // RSSI
        0x0004, // Security
        0x0005, // IP
        0x0006, // Mask
        0x0007, // Gateway
        0x0008, // DNS
        0x0009, // MAC
        0x000A, // DHCP
        0x000B, // Status
    ]
    String dni = device.deviceNetworkId
    String ep = String.format('%02X', EP_RPC)
    String cluster = String.format('%04X', 0xFC02)
    Integer mfgInt = getRpcMfgCode()
    String profile = String.format('%04X', PROFILE_RPC)
    String fc = '04'
    String mfgLE = hexLE16(mfgInt)
    def cmds = []
    attrs.each { a ->
        String attrName = wifiAttrName(String.format('%04X', a))
        logDebug "Requesting WiFi attribute ${attrName} (0x${String.format('%04X', a)})"
        String p = fc + mfgLE + String.format('%02X', nextZclSeqNum()) + '00' + hexLE16(a)
        cmds += "he raw 0x${dni} 1 0x${ep} 0x${cluster} {${p}} {0x${profile}}"
        // small delay between reads to be polite to the radio
        cmds += "delay 80"
    }
    logInfo "Requesting ${attrs.size()} WiFi attributes: ${attrs.collect { wifiAttrName(String.format('%04X', it)) }.join(', ')}"
    sendZigbeeCommands(cmds)
}

private void processWifiCluster(Map descMap) {
    // Handle read responses for WiFiSetupCluster with catchall parsing and type-aware decoding
    try {
        if (!descMap) return
        if (descMap.command == '04') {
            // write attribute response
            logDebug "WiFiSetupCluster write response: ${descMap}"
            return
        }

        // If this is a catchall with data list, parse attribute records (attr LE, status, encoding, [len, payload])
        if (!descMap.attrId && descMap.data instanceof List) {
            List<String> d = (List<String>) descMap.data
            int i = 0
            while (i + 2 <= d.size()) {
                // Attribute ID (LE) - first two bytes
                if (i + 2 > d.size()) break
                int attr = (Integer.parseInt(d[i + 1], 16) << 8) | Integer.parseInt(d[i], 16)
                i += 2
                if (i >= d.size()) break
                int status = Integer.parseInt(d[i++], 16)
                if (status != 0x00) {
                    String statusMsg = ""
                    switch (status) {
                        case 0x86: statusMsg = "UNSUPPORTED_ATTRIBUTE"; break
                        case 0x8F: statusMsg = "UNSUPPORTED_ATTRIBUTE"; break
                        default: statusMsg = String.format("0x%02X", status); break
                    }
                    logDebug String.format("WiFiSetupCluster Read Attr attr=0x%04X status=%s", attr, statusMsg)
                    continue
                }
                if (i >= d.size()) break
                String enc = d[i++].toUpperCase()
                String name = wifiAttrName(String.format('%04X', attr))
                String pretty = ''
                
                // Process based on attribute ID using switch statement
                switch (attr) {
                    case 0x0000: // SSID
                        pretty = processWifiStringAttribute(d, i, enc, "SSID")
                        break
                    case 0x0001: // BSSID  
                        pretty = processWifiBssidAttribute(d, i, enc)
                        break
                    case 0x0002: // Channel
                        pretty = processWifiNumericAttribute(d, i, enc, "Channel")
                        break
                    case 0x0003: // RSSI
                        pretty = processWifiSignedAttribute(d, i, enc, "RSSI", "dBm")
                        break
                    case 0x0004: // Security
                        pretty = processWifiSecurityAttribute(d, i, enc)
                        break
                    case 0x0005: // IP
                        pretty = processWifiIpAttribute(d, i, enc)
                        break
                    case 0x0006: // Mask
                        pretty = processWifiIpAttribute(d, i, enc)
                        break
                    case 0x0007: // Gateway
                        pretty = processWifiIpAttribute(d, i, enc)
                        break
                    case 0x0008: // DNS
                        pretty = processWifiIpAttribute(d, i, enc)
                        break
                    case 0x0009: // MAC
                        pretty = processWifiMacAttribute(d, i, enc)
                        break
                    case 0x000A: // DHCP
                        pretty = processWifiBooleanAttribute(d, i, enc, "DHCP")
                        break
                    case 0x000B: // Status
                        pretty = processWifiStatusAttribute(d, i, enc)
                        break
                    default:
                        pretty = processWifiGenericAttribute(d, i, enc)
                        break
                }
                
                // Skip to next attribute based on encoding type
                i = skipAttributeData(d, i, enc)
                
                // Always log the result, even if empty
                logInfo "WiFiSetupCluster [${name} / 0x${String.format('%04X', attr)}] -> ${pretty}"
            }
            return
        }

        // Non-catchall path: single attrId/value present
        String attrId = descMap.attrId
        String value = descMap.value
        if (!attrId) {
            logDebug "WiFiSetupCluster message without attrId: ${descMap}"
            return
        }
        String name = wifiAttrName(attrId)
        // Try to decode simple hex values
        try {
            if (value && value.length() > 0) {
                String h = value.replaceAll('[^0-9A-Fa-f]', '')
                if (h.length() % 2 != 0) h = '0' + h
                byte[] raw = hubitat.helper.HexUtils.hexStringToByteArray(h)
                String pretty
                if (raw.length == 4) {
                    pretty = raw.collect { (it & 0xFF).toString() }.join('.')
                } else if (raw.length == 6) {
                    pretty = raw.collect { String.format('%02X', it & 0xFF) }.join(':')
                } else {
                    try { pretty = new String(raw, 'UTF-8') } catch (Exception e) { pretty = hubitat.helper.HexUtils.byteArrayToHexString(raw) }
                }
                logInfo "WiFiSetupCluster [${name} / 0x${attrId}] -> ${pretty}"
            }
        } catch (Exception e) {
            logWarn "processWifiCluster simple decode error: ${e.message} desc=${descMap}"
        }
    } catch (Exception e) {
        logWarn "processWifiCluster error: ${e.message} desc=${descMap}"
    }
}

/**
 * Maps WiFi attribute IDs to human-readable names
 */
private String wifiAttrName(String attrId) {
    switch (attrId?.toUpperCase()) {
        case '0000': return 'SSID'
        case '0001': return 'BSSID'
        case '0002': return 'Channel'
        case '0003': return 'RSSI'
        case '0004': return 'Security'
        case '0005': return 'IP'
        case '0006': return 'Mask'
        case '0007': return 'Gateway'
        case '0008': return 'DNS'
        case '0009': return 'MAC'
        case '000A': return 'DHCP'
        case '000B': return 'Status'
        default: return "Unknown_${attrId}"
    }
}

// Helper methods for processing different WiFi attribute types

private String processWifiStringAttribute(List<String> d, int startIndex, String enc, String attrName) {
    try {
        byte[] bytes = extractAttributeBytes(d, startIndex, enc)
        if (bytes.length == 0) {
            logDebug "Empty ${attrName} attribute data (zero length)"
            return "not configured"
        }
        String result = new String(bytes, 'UTF-8')
        logDebug "${attrName} decoded: '${result}' (${bytes.length} bytes)"
        return result
    } catch (Exception e) {
        logWarn "Error processing ${attrName} string attribute: ${e.message}"
        return "error"
    }
}

private String processWifiBssidAttribute(List<String> d, int startIndex, String enc) {
    try {
        byte[] bytes = extractAttributeBytes(d, startIndex, enc)
        if (bytes.length == 6) {
            return bytes.collect { String.format('%02X', it & 0xFF) }.join(':')
        } else {
            return new String(bytes, 'UTF-8')
        }
    } catch (Exception e) {
        logWarn "Error processing BSSID attribute: ${e.message}"
        return ""
    }
}

private String processWifiNumericAttribute(List<String> d, int startIndex, String enc, String attrName) {
    try {
        byte[] bytes = extractAttributeBytes(d, startIndex, enc)
        if (bytes.length == 1) {
            return String.valueOf(bytes[0] & 0xFF)
        } else if (bytes.length == 2) {
            int value = (bytes[0] & 0xFF) | ((bytes[1] & 0xFF) << 8)
            return String.valueOf(value)
        } else {
            return String.format('0x%02X', bytes[0] & 0xFF)
        }
    } catch (Exception e) {
        logWarn "Error processing ${attrName} numeric attribute: ${e.message}"
        return ""
    }
}

private String processWifiSignedAttribute(List<String> d, int startIndex, String enc, String attrName, String unit) {
    try {
        byte[] bytes = extractAttributeBytes(d, startIndex, enc)
        if (bytes.length == 1) {
            int value = (bytes[0] & 0x80) != 0 ? (bytes[0] | 0xFFFFFF00) : (bytes[0] & 0xFF)
            return "${value} ${unit}"
        } else if (bytes.length == 2) {
            int value = (bytes[0] & 0xFF) | ((bytes[1] & 0xFF) << 8)
            if ((value & 0x8000) != 0) value |= 0xFFFF0000
            return "${value} ${unit}"
        } else {
            return String.format('0x%02X', bytes[0] & 0xFF)
        }
    } catch (Exception e) {
        logWarn "Error processing ${attrName} signed attribute: ${e.message}"
        return ""
    }
}

private String processWifiSecurityAttribute(List<String> d, int startIndex, String enc) {
    try {
        byte[] bytes = extractAttributeBytes(d, startIndex, enc)
        if (bytes.length == 1) {
            int secType = bytes[0] & 0xFF
            switch (secType) {
                case 0: return "Open"
                case 1: return "WEP"
                case 2: return "WPA"
                case 3: return "WPA2"
                case 4: return "WPA3"
                default: return "Unknown (${secType})"
            }
        } else {
            return new String(bytes, 'UTF-8')
        }
    } catch (Exception e) {
        logWarn "Error processing Security attribute: ${e.message}"
        return ""
    }
}

private String processWifiIpAttribute(List<String> d, int startIndex, String enc) {
    try {
        byte[] bytes = extractAttributeBytes(d, startIndex, enc)
        if (bytes.length == 0) {
            return "not configured"
        }
        if (bytes.length == 4) {
            String result = bytes.collect { (it & 0xFF).toString() }.join('.')
            logDebug "IP decoded: '${result}' (${bytes.length} bytes)"
            return result
        } else {
            String result = new String(bytes, 'UTF-8')
            logDebug "IP as text: '${result}' (${bytes.length} bytes)"
            return result
        }
    } catch (Exception e) {
        logWarn "Error processing IP attribute: ${e.message}"
        return "error"
    }
}

private String processWifiMacAttribute(List<String> d, int startIndex, String enc) {
    try {
        byte[] bytes = extractAttributeBytes(d, startIndex, enc)
        if (bytes.length == 0) {
            return "not configured"
        }
        if (bytes.length == 6) {
            String result = bytes.collect { String.format('%02X', it & 0xFF) }.join(':')
            logDebug "MAC decoded: '${result}' (${bytes.length} bytes)"
            return result
        } else {
            String result = new String(bytes, 'UTF-8')
            logDebug "MAC as text: '${result}' (${bytes.length} bytes)"
            return result
        }
    } catch (Exception e) {
        logWarn "Error processing MAC attribute: ${e.message}"
        return "error"
    }
}

private String processWifiBooleanAttribute(List<String> d, int startIndex, String enc, String attrName) {
    try {
        byte[] bytes = extractAttributeBytes(d, startIndex, enc)
        if (bytes.length == 0) {
            return "not configured"
        }
        if (bytes.length == 1) {
            boolean enabled = (bytes[0] & 0xFF) != 0
            String result = enabled ? "Enabled" : "Disabled"
            logDebug "${attrName} decoded: '${result}' (value=${bytes[0] & 0xFF})"
            return result
        } else {
            String result = new String(bytes, 'UTF-8')
            logDebug "${attrName} as text: '${result}' (${bytes.length} bytes)"
            return result
        }
    } catch (Exception e) {
        logWarn "Error processing ${attrName} boolean attribute: ${e.message}"
        return "error"
    }
}

private String processWifiStatusAttribute(List<String> d, int startIndex, String enc) {
    try {
        byte[] bytes = extractAttributeBytes(d, startIndex, enc)
        if (bytes.length == 1) {
            int status = bytes[0] & 0xFF
            switch (status) {
                case 0: return "Disconnected"
                case 1: return "Connected"
                case 2: return "Connecting"
                case 3: return "Error"
                default: return "Unknown (${status})"
            }
        } else {
            return new String(bytes, 'UTF-8')
        }
    } catch (Exception e) {
        logWarn "Error processing Status attribute: ${e.message}"
        return ""
    }
}

private String processWifiGenericAttribute(List<String> d, int startIndex, String enc) {
    try {
        byte[] bytes = extractAttributeBytes(d, startIndex, enc)
        if (bytes.length == 0) return ""
        if (bytes.length == 4) {
            return bytes.collect { (it & 0xFF).toString() }.join('.')
        } else if (bytes.length == 6) {
            return bytes.collect { String.format('%02X', it & 0xFF) }.join(':')
        } else {
            try { 
                return new String(bytes, 'UTF-8') 
            } catch (Exception e) { 
                return hubitat.helper.HexUtils.byteArrayToHexString(bytes) 
            }
        }
    } catch (Exception e) {
        logWarn "Error processing generic attribute: ${e.message}"
        return ""
    }
}

private byte[] extractAttributeBytes(List<String> d, int startIndex, String enc) {
    int i = startIndex
    switch (enc.toUpperCase()) {
        case '41':
        case '42': // 1-byte length
            if (i >= d.size()) {
                logDebug "extractAttributeBytes: No length byte available for enc=${enc}"
                return new byte[0]
            }
            int l1 = Integer.parseInt(d[i++], 16)
            //logDebug "extractAttributeBytes: enc=${enc} declared_length=${l1} available_data=${d.subList(i, d.size())}"
            int remaining = d.size() - i
            int avail = Math.max(0, Math.min(remaining, l1))
            byte[] b1 = new byte[avail]
            for (int k = 0; k < avail; k++) { 
                b1[k] = (byte) Integer.parseInt(d[i + k], 16) 
            }
            if (avail < l1) {
                logWarn "WiFiSetupCluster short chunk: declared=${l1} got=${avail}"
            }
            if (l1 == 0) {
                logDebug "extractAttributeBytes: Zero-length attribute (device reported length=0)"
            }
            return b1
        case '43':
        case '44': // 2-byte length LE
            if (i + 1 >= d.size()) {
                logDebug "extractAttributeBytes: Not enough bytes for 2-byte length enc=${enc}"
                return new byte[0]
            }
            int l2 = Integer.parseInt(d[i], 16) | (Integer.parseInt(d[i + 1], 16) << 8)
            i += 2
            //logDebug "extractAttributeBytes: enc=${enc} declared_length=${l2} available_data=${d.subList(i, d.size())}"
            int remaining2 = d.size() - i
            int avail2 = Math.max(0, Math.min(remaining2, l2))
            byte[] b2 = new byte[avail2]
            for (int k = 0; k < avail2; k++) { 
                b2[k] = (byte) Integer.parseInt(d[i + k], 16) 
            }
            if (avail2 < l2) {
                logWarn "WiFiSetupCluster short long-chunk: declared=${l2} got=${avail2}"
            }
            if (l2 == 0) {
                logDebug "extractAttributeBytes: Zero-length attribute (device reported length=0)"
            }
            return b2
        case '10': // Single byte value
            if (i >= d.size()) {
                logDebug "extractAttributeBytes: No data byte available for enc=${enc}"
                return new byte[0]
            }
            //logDebug "extractAttributeBytes: enc=${enc} single_byte=${d[i]}"
            return [(byte) Integer.parseInt(d[i], 16)] as byte[]
        default:
            // Unknown encoding: try to read one byte if available
            //logDebug "extractAttributeBytes: Unknown encoding=${enc} trying single byte"
            if (i >= d.size()) return new byte[0]
            return [(byte) Integer.parseInt(d[i], 16)] as byte[]
    }
}

private int skipAttributeData(List<String> d, int startIndex, String enc) {
    int i = startIndex
    switch (enc.toUpperCase()) {
        case '41':
        case '42': // 1-byte length
            if (i >= d.size()) return i
            int l1 = Integer.parseInt(d[i++], 16)
            int remaining = d.size() - i
            int avail = Math.max(0, Math.min(remaining, l1))
            return i + avail
        case '43':
        case '44': // 2-byte length LE
            if (i + 1 >= d.size()) return i
            int l2 = Integer.parseInt(d[i], 16) | (Integer.parseInt(d[i + 1], 16) << 8)
            i += 2
            int remaining2 = d.size() - i
            int avail2 = Math.max(0, Math.min(remaining2, l2))
            return i + avail2
        case '10': // Single byte
            return i + 1
        default:
            // Unknown encoding: skip one byte if available
            return (i < d.size()) ? i + 1 : i
    }
}

void sendZigbeeCommands(ArrayList<String> cmd) {
    if (settings?.logEnable) { log.trace "${device.displayName } sendZigbeeCommands(cmd=$cmd)" }
    hubitat.device.HubMultiAction allActions = new hubitat.device.HubMultiAction()
    cmd.each {
            allActions.add(new hubitat.device.HubAction(it, hubitat.device.Protocol.ZIGBEE))
    }
    sendHubCommand(allActions)
}

// https://shelly-api-docs.shelly.cloud/gen2/ComponentsAndServices/Zigbee/ 
// https://shelly-api-docs.shelly.cloud/gen2/General/RPCProtocol/#notificationframe 
// https://shelly-api-docs.shelly.cloud/gen2/General/Notifications/ 
// https://shelly-api-docs.shelly.cloud/gen2/ComponentsAndServices/Switch/ 

/**
 * Minimal handler for Shelly RPC cluster (0xFC01) read responses
 */
private void processRpcCluster(Map descMap) {
    // Handle Write Attributes Response (0x04) for RPC TxData/TxCtl
    if (descMap.command == "04") {
        try {
            List<String> d = (descMap.data instanceof List) ? (List<String>) descMap.data : null
            if (d && d.size() >= 3) {
                int status = Integer.parseInt(d[0], 16)
                int attrId = (Integer.parseInt(d[2], 16) << 8) | Integer.parseInt(d[1], 16)
                String aHex = String.format('%04X', attrId)
                if (status == 0x00) {
                    logDebug "RPC Write Attr OK attr=0x${aHex}"
                } else {
                    logWarn "RPC Write Attr status=0x${String.format('%02X', status)} attr=0x${aHex}"
                }
                // If TxData (0x0000) rejected with Invalid Data Type (0x8D), retry once with 0x44
                if (attrId == 0x0000 && status == 0x8D && state?.rpcLongTried44 != true) {
                    logWarn "RPC TxData at 0x0000 rejected (0x8D). Scheduling one retry with 0x44."
                    state.rpcLongTried44 = true
                    runInMillis(150, 'rpcRetryTxDataLong44')
                }
            } else {
                logDebug "RPC Write Attr Response raw: ${descMap.data}"
            }
        } catch (Exception e) {
            logWarn "RPC Write Attr parse error: ${e.message} data=${descMap.data}"
        }
        return
    }
    // Accept both styles of read responses: standard (0x0A) and catchall profile (0x01)
    if (descMap.command != null && !(descMap.command in ["0A", "01"])) {
        logDebug "RPC cluster non-read response: cmd=${descMap.command} data=${descMap.data}"
        return
    }
    // Catchall path: parse records from descMap.data when attrId/value are not populated
    if (!descMap.attrId && descMap.data instanceof List) {
        List<String> d = (List<String>) descMap.data
        int i = 0
        while (i + 2 <= d.size()) {
            // Attribute ID (LE)
            if (i + 2 > d.size()) break
            int attr = (Integer.parseInt(d[i + 1], 16) << 8) | Integer.parseInt(d[i], 16)
            i += 2
            if (i >= d.size()) break
            int status = Integer.parseInt(d[i++], 16)
            if (status != 0x00) {
                logDebug String.format("RPC Read Attr attr=0x%04X status=0x%02X", attr, status)
                continue
            }
            if (i >= d.size()) break
            String enc = d[i++].toUpperCase()
            // Decode based on data type
            switch (enc) {
                case '23': // UINT32 (RxCtl)
                    if (i + 4 <= d.size()) {
                        long v = (Integer.parseInt(d[i],16)) | (Integer.parseInt(d[i+1],16) << 8) | (Integer.parseInt(d[i+2],16) << 16) | (Integer.parseInt(d[i+3],16) << 24)
                        i += 4
                        if (attr == 0x0002) {
                            logDebug String.format("RPC RxCtl (len/status): 0x%08X (%d)", v, v)
                            sendEvent(name: "rpcStatus", value: "rxctl:${v}")
                            // Track target length for assembling response
                            try {
                                long prev = (state?.rpcRxTargetLen ?: 0L) as long
                                if (v != prev) {
                                    state.rpcRxTargetLen = v
                                    state.rpcRxAccum = 0
                                    state.rpcRxText = ""
                                    state.rpcRxBytesRead = 0L
                                }
                            } catch (Exception ignored) {}
                        } else {
                            logDebug String.format("RPC uint32 attr=0x%04X -> 0x%08X", attr, v)
                        }
                    } else {
                        i = d.size()
                    }
                    break
                case '41':
                case '42': // 1-byte length strings
                    if (i >= d.size()) { break }
                    int l1 = Integer.parseInt(d[i++], 16)
                        if (l1 == 0) {
                            logDebug "RPC RX[${String.format('%04X', attr)}] (${enc}) zero-length chunk, treating as EOF."
                            // Finalize buffer and fire event immediately
                            if ((state?.rpcRxText ?: '').length() > 0) {
                                logInfo "RPC JSON assembled (EOF, ${state?.rpcRxAccum ?: 0} bytes) : ${state.rpcRxText}"
                                sendEvent(name: "rpcResponse", value: state.rpcRxText)
                            }
                            state.rpcRxTargetLen = 0
                            state.rpcRxAccum = 0
                            state.rpcRxText = ""
                            state.rpcRxBytesRead = 0L
                            state.rpcMachinePhase = 'done'
                            break
                        }
                    int remaining = d.size() - i;
                    if (remaining < l1) {
                        logDebug "RPC short chunk (${enc}): declared=${l1} bytes, got=${remaining}. Filling missing bytes with '?' character."
                        byte[] b1 = new byte[l1];
                        int avail1 = Math.max(0, Math.min(remaining, l1));
                        for (int k = 0; k < avail1; k++) { b1[k] = (byte) Integer.parseInt(d[i + k], 16); }
                        // Fill missing bytes with '?'
                        for (int k = avail1; k < l1; k++) { b1[k] = (byte) '¿'; }
                        i += remaining;
                        String txt1;
                        try { txt1 = new String(b1, 'UTF-8'); } catch (Exception e) { txt1 = new String(b1); }
                        long bytesRead = ((state?.rpcRxBytesRead ?: 0L) as Long).longValue();
                        long targetLen = ((state?.rpcRxTargetLen ?: 0L) as Long).longValue();
                        bytesRead = bytesRead + l1;
                        state.rpcRxBytesRead = bytesRead;
                        logDebug "Partial chunk appended: ${avail1} of ${l1} bytes (missing bytes replaced with '?', total ${bytesRead}/${targetLen})";
                        rpcAccumAppendText(txt1);
                        if (targetLen > 0 && bytesRead >= targetLen) {
                            logInfo "RPC JSON assembled (${bytesRead} bytes) : ${state.rpcRxText}";
                            sendEvent(name: "rpcResponse", value: state.rpcRxText);
                            state.rpcRxTargetLen = 0;
                            state.rpcRxAccum = 0;
                            state.rpcRxText = "";
                            state.rpcRxBytesRead = 0L;
                            state.rpcMachinePhase = 'done';
                        }
                        break;
                    } else {
                        byte[] b1 = new byte[l1];
                        for (int k = 0; k < l1; k++) { b1[k] = (byte) Integer.parseInt(d[i + k], 16); }
                        i += l1;
                        String txt1;
                        try { txt1 = new String(b1, 'UTF-8'); } catch (Exception e) { txt1 = new String(b1); }
                        // ...existing code...
                    }
                    // Defensive logging
                    long bytesRead = ((state?.rpcRxBytesRead ?: 0L) as Long).longValue()
                    long targetLen = ((state?.rpcRxTargetLen ?: 0L) as Long).longValue()
                    // Fix: ensure 'end' and 'start' are defined and not null
                    int start = 0;
                    int end = (avail1 != null) ? (int) avail1 : 0;
                    bytesRead = bytesRead + (end - start);
                    state.rpcRxBytesRead = bytesRead;
                    logDebug "RPC RX[${String.format('%04X', attr)}] (${enc}) -> [${end} bytes] (total ${bytesRead}/${targetLen})";
                    rpcAccumAppendText(txt1);
                    // Stop if reached target
                    if (targetLen > 0 && bytesRead >= targetLen) {
                        logInfo "RPC JSON assembled (${bytesRead} bytes) : ${state.rpcRxText}";
                        sendEvent(name: "rpcResponse", value: state.rpcRxText);
                        state.rpcRxTargetLen = 0;
                        state.rpcRxAccum = 0;
                        state.rpcRxText = "";
                        state.rpcRxBytesRead = 0L;
                        state.rpcMachinePhase = 'done';
                    }
                    break
                case '43':
                case '44': // 2-byte length LE strings
                    if (i + 2 > d.size()) { break }
                    int l2 = Integer.parseInt(d[i],16) | (Integer.parseInt(d[i+1],16) << 8);
                    i += 2;
                    if (l2 == 0) {
                        logDebug "RPC RX[${String.format('%04X', attr)}] (${enc}) zero-length chunk, treating as EOF."
                        // Finalize buffer and fire event immediately
                        if ((state?.rpcRxText ?: '').length() > 0) {
                            logInfo "RPC JSON assembled (EOF, ${state?.rpcRxAccum ?: 0} bytes) : ${state.rpcRxText}"
                            sendEvent(name: "rpcResponse", value: state.rpcRxText)
                        }
                        state.rpcRxTargetLen = 0;
                        state.rpcRxAccum = 0;
                        state.rpcRxText = "";
                        state.rpcRxBytesRead = 0L;
                        state.rpcMachinePhase = 'done';
                        break;
                    }
                    int remaining2 = d.size() - i;
                    if (remaining2 < l2) {
                        logDebug "RPC short chunk (${enc}): declared=${l2} bytes, got=${remaining2}. Filling missing bytes with '?' character."
                        byte[] b2 = new byte[l2];
                        int avail2 = Math.max(0, Math.min(remaining2, l2));
                        for (int k = 0; k < avail2; k++) { b2[k] = (byte) Integer.parseInt(d[i + k], 16); }
                        // Fill missing bytes with '?'
                        for (int k = avail2; k < l2; k++) { b2[k] = (byte) '¿'; }
                        i += remaining2;
                        String txt2;
                        try { txt2 = new String(b2, 'UTF-8'); } catch (Exception e) { txt2 = new String(b2); }
                        long bytesRead = ((state?.rpcRxBytesRead ?: 0L) as Long).longValue();
                        long targetLen = ((state?.rpcRxTargetLen ?: 0L) as Long).longValue();
                        bytesRead = bytesRead + l2;
                        state.rpcRxBytesRead = bytesRead;
                        logDebug "Partial chunk appended: ${avail2} of ${l2} bytes (missing bytes replaced with '¿', total ${bytesRead}/${targetLen})";
                        rpcAccumAppendText(txt2);
                        if (targetLen > 0 && bytesRead >= targetLen) {
                            logInfo "RPC JSON assembled (${bytesRead} bytes) : ${state.rpcRxText}";
                            sendEvent(name: "rpcResponse", value: state.rpcRxText);
                            state.rpcRxTargetLen = 0;
                            state.rpcRxAccum = 0;
                            state.rpcRxText = "";
                            state.rpcRxBytesRead = 0L;
                            state.rpcMachinePhase = 'done';
                        }
                        break;
                    } else {
                        byte[] b2 = new byte[l2];
                        for (int k = 0; k < l2; k++) { b2[k] = (byte) Integer.parseInt(d[i + k], 16); }
                        i += l2;
                        String txt2;
                        try { txt2 = new String(b2, 'UTF-8'); } catch (Exception e) { txt2 = new String(b2); }
                        // ...existing code...
                    }
                    long bytesRead = (state?.rpcRxBytesRead ?: 0L) as long
                    long targetLen = (state?.rpcRxTargetLen ?: 0L) as long
                    bytesRead += avail2
                    state.rpcRxBytesRead = bytesRead
                    logInfo "RPC RX[${String.format('%04X', attr)}] (${enc}) -> [${avail2} bytes] (total ${bytesRead}/${targetLen})"
                    rpcAccumAppendText(txt2)
                    if (targetLen > 0 && bytesRead >= targetLen) {
                        logInfo "RPC JSON assembled (${bytesRead} bytes) : ${state.rpcRxText}"
                        sendEvent(name: "rpcResponse", value: state.rpcRxText)
                        state.rpcRxTargetLen = 0
                        state.rpcRxAccum = 0
                        state.rpcRxText = ""
                        state.rpcRxBytesRead = 0L
                        state.rpcMachinePhase = 'done'
                    }
                    break
                default:
                    logDebug String.format("RPC attr=0x%04X enc=0x%s data(rem)=%s", attr, enc, d.subList(i, d.size()))
                    i = d.size()
                    break
            }
        }
        return
    }
    String attrId = descMap.attrId
    if (!attrId) {
        // No attrId and no data list; nothing to parse further
        logDebug "RPC cluster message without attrId: ${descMap}"
        return
    }
    switch (attrId.toUpperCase()) {
        case "0002": // RxCtl UINT32
            // descMap.value is little-endian hex
            try {
                String v = descMap.value
                if (v) {
                    long len = Long.parseLong(v, 16)
                    logDebug "RPC RxCtl (len/status): 0x${v} (${len})"
                    sendEvent(name: "rpcStatus", value: "rxctl:${len}")
                }
            } catch (Exception e) {
                logWarn "RPC RxCtl parse error: ${e.message} value=${descMap.value}"
            }
            break
        default:
            // Check if it's in the RX data chunk range 0x0080..0x008F
            try {
                int id = Integer.parseInt(attrId, 16)
                if (id >= ATTR_RX_DATA_BASE && id < ATTR_RX_DATA_BASE + 0x10) {
                    // String payloads: accept 0x41/0x43 (octet) and 0x42/0x44 (char). Value begins with length byte(s)
                    String encoding = (descMap.encoding ?: "").toUpperCase()
                    String hex = descMap.value ?: ""
                    if (!hex) {
                        logDebug "RPC RX data empty at attr ${attrId}"
                        return
                    }
                    byte[] raw = hubitat.helper.HexUtils.hexStringToByteArray(hex)
                    int offset = 0
                    int payLen = 0
                    if (encoding == "41" || encoding == "42") { // Octet/Char string, 1-byte length
                        if (raw.length == 0) { return }
                        payLen = raw[0] & 0xFF
                        offset = 1
                    } else if (encoding == "43" || encoding == "44") { // Long octet/char string, 2-byte length LE
                        if (raw.length < 2) { return }
                        payLen = (raw[0] & 0xFF) | ((raw[1] & 0xFF) << 8)
                        offset = 2
                    } else {
                        // Fallback: treat entire value as bytes
                        payLen = raw.length
                        offset = 0
                    }
                    int avail = Math.min(payLen, raw.length - offset)
                    if (avail < 0) { avail = 0 }
                    byte[] payload = new byte[avail]
                    for (int i = 0; i < avail; i++) {
                        payload[i] = raw[offset + i]
                    }
                    String text = new String(payload, 'UTF-8')
                    logInfo "RPC RX[${attrId}] (${encoding}) -> ${text}"
                }
            } catch (Exception e) {
                logWarn "RPC RX decode error: ${e.message} attrId=${attrId} val=${descMap.value} enc=${descMap.encoding}"
            }
            break
    }
}


/**
 * Test function: sends a simple RPC request using long strings
 */
 /*
def testSimpleRpcLong() {
    try {
        def jsonRequest = '{"jsonrpc":"2.0","id":1,"method":"Shelly.GetDeviceInfo"}'
        byte[] requestBytes = jsonRequest.getBytes('UTF-8')
        int n = requestBytes.length
        String hexPayload = hubitat.helper.HexUtils.byteArrayToHexString(requestBytes)
        
        // UINT32 LE
        String le32 = String.format('%02X%02X%02X%02X', n & 0xFF, (n >> 8) & 0xFF, (n >> 16) & 0xFF, (n >> 24) & 0xFF)
        // Octet string 1-byte length (device expects 0x41 for attr 0x0000)
        String len1 = String.format('%02X', n & 0xFF)

        logInfo "Sending test RPC (long): ${jsonRequest}"

        String dni = device.deviceNetworkId
        String ep = String.format('%02X', EP_RPC)
        String cluster = String.format('%04X', CLUSTER_RPC)
        Integer mfgInt = getRpcMfgCode()
        String mfg = String.format('%04X', mfgInt)
        String profile = String.format('%04X', PROFILE_RPC)

        logDebug "RPC (long) using mfg 0x${mfg}"

        def cmds = []
        String fc = '04'
        String mfgLE = hexLE16(mfgInt)
        // Write TxCtl
        String p1 = fc + mfgLE + String.format('%02X', nextZclSeqNum()) + '02' + hexLE16(ATTR_TX_CTL) + '23' + le32
        cmds += "he raw 0x${dni} 1 0x${ep} 0x${cluster} {${p1}} {0x${profile}}"
        // Give the device a brief moment to latch TxCtl before TxData
        cmds += "delay 120"
        // Write TxData as character string (0x42) to 0x0000
        String p2 = fc + mfgLE + String.format('%02X', nextZclSeqNum()) + '02' + hexLE16(ATTR_TX_DATA_BASE) + '42' + len1 + hexPayload
        cmds += "he raw 0x${dni} 1 0x${ep} 0x${cluster} {${p2}} {0x${profile}}"
        
        sendZigbeeCommands(cmds)
    } catch (Exception e) {
        logWarn "testSimpleRpcLong error: ${e.message}"
    }
}
*/

private void rpcAccumAppendText(String chunk) {
    // Accumulate RX JSON text until reaching the expected length from RxCtl, then fire rpcResponse
    try {
        if (chunk == null) return
        long target = (state?.rpcRxTargetLen ?: 0L) as long
        String prevText = (state?.rpcRxText ?: "") as String
        long prevLen = (state?.rpcRxAccum ?: 0L) as long
        String nextText = prevText + chunk
        int chunkBytes = chunk.getBytes('UTF-8').length
        long nextLen = prevLen + chunkBytes
        state.rpcRxText = nextText
        state.rpcRxAccum = nextLen
        logDebug "rpcRxText updated (${nextLen} bytes): ${state.rpcRxText}"
        if (target > 0 && nextLen >= target) {
            // Trim if overshoot (should not happen with exact lengths)
            if (nextText.length() > target) {
                nextText = nextText.substring(0, (int) target)
            }
            logInfo "RPC JSON assembled (${nextText.length()} bytes) : ${nextText}"
            sendEvent(name: "rpcResponse", value: nextText)
            // Reset for next transaction
            state.rpcRxTargetLen = 0
            state.rpcRxAccum = 0
            state.rpcRxText = ""
        }
    } catch (Exception e) {
        logWarn "rpcAccumAppendText error: ${e.message}"
    }
}

// Retry path for processRpcCluster: if 0x41 was rejected with 0x8D, try 0x44 once
void rpcRetryTxDataLong44() {
    try {
        def jsonRequest = '{"jsonrpc":"2.0","id":1,"method":"Shelly.GetDeviceInfo"}'
        byte[] requestBytes = jsonRequest.getBytes('UTF-8')
        int n = requestBytes.length
        String hexPayload = hubitat.helper.HexUtils.byteArrayToHexString(requestBytes)
        // 2-byte length LE for long string
        String len2 = String.format('%02X%02X', n & 0xFF, (n >> 8) & 0xFF)

        String dni = device.deviceNetworkId
        String ep = String.format('%02X', EP_RPC)
        String cluster = String.format('%04X', CLUSTER_RPC)
        Integer mfgInt = getRpcMfgCode()
        String profile = String.format('%04X', PROFILE_RPC)
        String fc = '04'
        String mfgLE = hexLE16(mfgInt)

        def cmds = []
        // Write TxData as Long Character String (0x44) to 0x0000
        String p2 = fc + mfgLE + String.format('%02X', nextZclSeqNum()) + '02' + hexLE16(ATTR_TX_DATA_BASE) + '44' + len2 + hexPayload
        cmds += "he raw 0x${dni} 1 0x${ep} 0x${cluster} {${p2}} {0x${profile}}"
        cmds += "delay 1000"
        // Poll RxCtl and RxData (same windows 0x0002, 0x0000..)
        for (int i = 0; i < 6; i++) {
            String rx = fc + mfgLE + String.format('%02X', nextZclSeqNum()) + '00' + hexLE16(ATTR_RX_CTL)
            cmds += "he raw 0x${dni} 1 0x${ep} 0x${cluster} {${rx}} {0x${profile}}"
            cmds += "delay 220"
        }
        for (int j = 0; j < 4; j++) {
            String rxd = fc + mfgLE + String.format('%02X', nextZclSeqNum()) + '00' + hexLE16(ATTR_RX_DATA_BASE + j)
            cmds += "he raw 0x${dni} 1 0x${ep} 0x${cluster} {${rxd}} {0x${profile}}"
            cmds += "delay 220"
        }
        sendZigbeeCommands(cmds)
    } catch (Exception e) {
        logWarn "rpcRetryTxDataLong44 error: ${e.message}"
    }
}


/**
 * Validates JSON request and returns a valid JSON string
 * @param jsonRequest Input JSON request (can be null, empty, or invalid)
 * @return Valid JSON-RPC request string
 */
private String validateJsonRequest(String jsonRequest) {
    // Default JSON request
    String defaultRequest = '{"jsonrpc":"2.0","id":1,"method":"Shelly.GetDeviceInfo"}'
    
    // If null or empty, use default
    if (!jsonRequest || jsonRequest.trim().isEmpty()) {
        logDebug "RPC SM: No JSON request provided, using default GetDeviceInfo"
        return defaultRequest
    }
    
    // Try to validate JSON syntax
    try {
        def parsed = new groovy.json.JsonSlurper().parseText(jsonRequest.trim())
        logDebug "RPC SM: JSON request validated successfully"
        return jsonRequest.trim()
    } catch (Exception e) {
        logWarn "RPC SM: Invalid JSON request '${jsonRequest}', using default. Error: ${e.message}"
        return defaultRequest
    }
}

/**
 * Fully automatic RPC state machine: sends request, polls RxCtl, reads RxData chunks, assembles response
 * @param jsonRequest JSON-RPC request string (optional)
 */
def runRpcStateMachine(jsonRequest = null) {
    logInfo "🚦 Starting automatic RPC state machine..."
    
    // Validate and set default JSON request
    String validJsonRequest = validateJsonRequest(jsonRequest)
    logDebug "RPC SM: Using JSON request: ${validJsonRequest}"
    
    // Initialize state
    state.rpcMachinePhase = 'init'
    state.rpcRxTargetLen = 0
    state.rpcRxAccum = 0
    state.rpcRxText = ''
    state.rpcMachineTries = 0
    state.rpcJsonRequest = validJsonRequest
    runRpcStateMachineStep()
}

private void runRpcStateMachineStep() {
    def phase = state.rpcMachinePhase ?: 'init'
    def tries = (state.rpcMachineTries ?: 0) as int
        if (phase == 'done') {
            logDebug "RPC SM: State machine is done. No further polling."
            return
        }
        if (tries > 40) {
            logWarn "RPC state machine: too many tries, aborting."
            state.rpcMachinePhase = 'done'
            return
        }
        state.rpcMachineTries = tries + 1
        switch (phase) {
        case 'init':
            // Send initial request (TxCtl + TxData)
            logDebug "RPC SM: Sending initial request..."
            def jsonRequest = state.rpcJsonRequest ?: '{"jsonrpc":"2.0","id":1,"method":"Shelly.GetDeviceInfo"}'
            byte[] requestBytes = jsonRequest.getBytes('UTF-8')
            int n = requestBytes.length
            String hexPayload = hubitat.helper.HexUtils.byteArrayToHexString(requestBytes)
            String le32 = String.format('%02X%02X%02X%02X', n & 0xFF, (n >> 8) & 0xFF, (n >> 16) & 0xFF, (n >> 24) & 0xFF)
            String len1 = String.format('%02X', n & 0xFF)
            String dni = device.deviceNetworkId
            String ep = String.format('%02X', EP_RPC)
            String cluster = String.format('%04X', CLUSTER_RPC)
            Integer mfgInt = getRpcMfgCode()
            String profile = String.format('%04X', PROFILE_RPC)
            String fc = '04'
            String mfgLE = hexLE16(mfgInt)
            def cmds = []
            String p1 = fc + mfgLE + String.format('%02X', nextZclSeqNum()) + '02' + hexLE16(ATTR_TX_CTL) + '23' + le32
            cmds += "he raw 0x${dni} 1 0x${ep} 0x${cluster} {${p1}} {0x${profile}}"
            cmds += "delay 120"
            String p2 = fc + mfgLE + String.format('%02X', nextZclSeqNum()) + '02' + hexLE16(ATTR_TX_DATA_BASE) + '42' + len1 + hexPayload
            cmds += "he raw 0x${dni} 1 0x${ep} 0x${cluster} {${p2}} {0x${profile}}"
            sendZigbeeCommands(cmds)
            state.rpcMachinePhase = 'waitRxCtl'
            runInMillis(400, 'runRpcStateMachineStep')
            break
        case 'waitRxCtl':
            // Poll RxCtl for response length
            logDebug "RPC SM: Polling RxCtl for response length..."
            String dni = device.deviceNetworkId
            String ep = String.format('%02X', EP_RPC)
            String cluster = String.format('%04X', CLUSTER_RPC)
            Integer mfgInt = getRpcMfgCode()
            String profile = String.format('%04X', PROFILE_RPC)
            String fc = '04'
            String mfgLE = hexLE16(mfgInt)
            String rxCtl = fc + mfgLE + String.format('%02X', nextZclSeqNum()) + '00' + hexLE16(ATTR_RX_CTL)
            def cmds = []
            cmds += "he raw 0x${dni} 1 0x${ep} 0x${cluster} {${rxCtl}} {0x${profile}}"
            sendZigbeeCommands(cmds)
            // Wait for parse to update state.rpcRxTargetLen
            runInMillis(350, 'runRpcStateMachineStep')
            // If target length is set, move to next phase
            if ((state.rpcRxTargetLen ?: 0) > 0) {
                state.rpcMachinePhase = 'readRxData'
            }
            break
        case 'readRxData':
            // Read RxData chunk
            logDebug "RPC SM: Reading RxData chunk..."
            String dni = device.deviceNetworkId
            String ep = String.format('%02X', EP_RPC)
            String cluster = String.format('%04X', CLUSTER_RPC)
            Integer mfgInt = getRpcMfgCode()
            String profile = String.format('%04X', PROFILE_RPC)
            String fc = '04'
            String mfgLE = hexLE16(mfgInt)
            String rxData = fc + mfgLE + String.format('%02X', nextZclSeqNum()) + '00' + hexLE16(ATTR_RX_DATA_BASE)
            def cmds = []
            cmds += "he raw 0x${dni} 1 0x${ep} 0x${cluster} {${rxData}} {0x${profile}}"
            sendZigbeeCommands(cmds)
            // Wait for parse to append chunk
            runInMillis(350, 'runRpcStateMachineStep')
            // If accumulated length >= target, finish
            long target = (state.rpcRxTargetLen ?: 0L) as long
            long accum = (state.rpcRxAccum ?: 0L) as long
            if (target > 0 && accum >= target) {
                state.rpcMachinePhase = 'done'
            }
            break
        case 'done':
            logDebug "RPC SM: Done. Response assembled (${state.rpcRxAccum ?: 0} bytes)."
            // Optionally reset state
            state.rpcMachinePhase = null
            state.rpcMachineTries = 0
            break
        default:
            logWarn "RPC state machine: unknown phase ${phase}"
            state.rpcMachinePhase = 'done'
            break
    }
}


