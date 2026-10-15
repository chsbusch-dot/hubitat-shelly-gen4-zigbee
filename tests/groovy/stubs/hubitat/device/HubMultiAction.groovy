package hubitat.device

/** Stand-in for the hub's HubMultiAction: keeps the commands so a test can read what was sent. */
class HubMultiAction {
    List<String> cmds
    Protocol protocol

    HubMultiAction(List cmds, Protocol protocol) {
        this.cmds = cmds.collect { it.toString() }
        this.protocol = protocol
    }
}
