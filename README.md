# Shelly Plug US Gen4 (Zigbee) for Hubitat

A Hubitat Elevation driver for the Shelly Plug US Gen4 (model S4PL-00116US) running its Zigbee
firmware. It switches the plug and reports power, energy, voltage, current and AC frequency. It also
marks the plug offline when it stops talking.

## Why another driver

Most people run these plugs on the Shelly 1PM Gen 4 Zigbee driver by joaomf and kkossev. That driver
was written for the 1PM relay. Switching works, but two things go wrong on the plug:

- **Units.** The 1PM driver divides power and current by 100. Plug firmware 020000FF sends power in
  watts and current in milliamps, so a 1000 W heater shows up as 10 W and 9 A as 90 A. Zigbee has
  standard attributes where a device states its own multiplier and divisor. This driver reads them and
  scales every reading with them, so the numbers stay right when a firmware update changes the units.
- **Bundled reports.** The plug often sends current and power in the same message. A driver that only
  reads the first value of a message leaves power frozen while current keeps updating. This driver
  reads every value in the message.

Configure also leaves the relay alone. Hubitat's built-in Generic Zigbee Outlet driver turns the outlet
off and on again while it configures, which you don't want on a plug that runs a space heater.

## Requirements

- A Hubitat Elevation hub on platform 2.3.0 or later.
- A Shelly Plug US Gen4 switched to Zigbee mode and paired with the hub.

## Install

**Hubitat Package Manager:** choose Install, then From a URL, and paste:

```
https://raw.githubusercontent.com/chsbusch-dot/hubitat-shelly-gen4-zigbee/main/packageManifest.json
```

**By hand:** in Drivers Code, choose New Driver, then Import, paste the URL below and save.

```
https://raw.githubusercontent.com/chsbusch-dot/hubitat-shelly-gen4-zigbee/main/drivers/Shelly-Plug-US-Gen4-Zigbee.groovy
```

## Set up a plug

**A plug you pair from now on** gets this driver on its own: the fingerprint matches manufacturer
`Shelly`, model `Plug US`. The hub runs Configure after pairing.

**A plug that is already paired** (on the 1PM driver, for example):

1. Open the device, set Type to *Shelly Plug US Gen4 (Zigbee)* and click Save Device.
2. Click Configure. The relay stays as it is.
3. Wait about half a minute and open the logs. With descriptionText logging on you should see a line
   like `power units from the plug: x1/1` for each reading. A warning that says the plug "has not
   reported its units" means the driver is using its built-in defaults (see [Units](#units)).

If you had used the 1PM driver's calibration settings to correct the units (a power calibration of
9900%, for example), the driver removes them during Configure and logs a warning that names each one.
Those corrections would make the new readings wrong. The 1PM driver's leftover attributes (`rpcStatus`,
`rpcResponse`, `producedEnergy`) are deleted as well.

## Settings

| Setting | Default | What it does |
|---|---|---|
| Longest time between reports | 600 s | The plug reports at least this often, even when nothing changes. The health check uses it too. |
| Report power when it changes by | 5 W | How much power has to change before the plug reports it. |
| Power, voltage, current adjustment | 0 % | Leave at 0 unless your readings are off against a meter you trust. 2 reads 2% higher. |
| AC frequency offset | 0 Hz | Added to every frequency reading. |
| After a power cut, the plug turns | keep | Off, on, or back to how it was. "Keep" leaves the plug's own setting alone. Pick "on" only if you want a heater to come back by itself. |
| Switch off above (W) | empty | The plug's own overpower cutoff, 100 to 1800 W. Empty leaves the plug's setting alone. |
| Switch off by itself after | empty | The plug's auto-off timer: it switches itself off this many seconds after each switch-on, from the hub or from its button. 0 turns the timer off. Empty leaves the plug's setting alone. |
| Switch off above (A) | empty | The plug's own overcurrent cutoff, 1 to 15 A. Empty leaves the plug's setting alone. |
| Switch off above (V) | empty | The plug's own overvoltage cutoff, 100 to 300 V. Empty leaves the plug's setting alone. |
| LED ring | keep | What the plug's LED shows: a colour that follows the power draw, one colour when on and another when off, or nothing. |
| LED colour when on, when off | keep | The two colours for the one-colour mode. |
| LED brightness | empty | 1 to 100 percent. Empty keeps the plug's setting. |
| LED at night | keep | Dims the LED, or switches it off, between two times of day (22:00 to 06:00 by default). |
| descriptionText logging | on | One log line per reading. |
| Debug logging | off | Raw Zigbee messages. Turns itself off after 30 minutes. |

Saving the settings sends the new reporting setup to the plug. The plug's own settings (power-on state,
the cutoffs, the auto-off timer, LED) go out about 30 seconds later, one at a time, and only when they
differ from what the plug last accepted (see [Plug settings](#plug-settings)).

The auto-off timer is the setting to use for a heater: with it, a plug that loses contact with the hub still
switches off by itself. Switching the plug on again starts the timer again.

## Attributes

| Attribute | Unit | Notes |
|---|---|---|
| `switch` | | `on` or `off`. Type is `digital` when it follows a command from the hub, `physical` for the button on the plug. |
| `power` | W | |
| `energy` | kWh | The plug's own counter. |
| `voltage` | V | |
| `amperage` | A | |
| `acFrequency` | Hz | |
| `healthStatus` | | `online`, `offline` or `unknown`. |
| `checkInterval` | s | How long the plug may stay silent before it counts as offline. |
| `tripCount` | | How often the plug switched itself off (see [Trips](#trips)). |
| `lastTrip` | | When and at what load it last did. |
| `lightLevel` | | `dark`, `twilight` or `bright`, from the plug's light sensor (firmware 2.0.1 and later). |
| `powerOnState` | | What the plug does after a power cut, when it reports it. |
| `lastSettingsWrite` | | The outcome of the last plug setting sent: accepted, refused, unconfirmed or not sent. |
| `bluetooth` | | `on` or `off`, once a setBluetooth command has been accepted. Configure clears it, since a reset plug forgets the setting. |
| `wifiStatus` | | The plug's Wi-Fi as the plug reports it, for example `connecting` or `got ip`. |
| `wifiIp` | | The plug's Wi-Fi address, or `none` while it has none. |
| `wifiNetwork` | | The Wi-Fi network the plug is set to join, or `none`. |
| `powerFactor` | | Only on firmware that offers it; 2.0.1 does not. |

## Commands

Besides on, off, refresh and configure:

- **identify** blinks the plug's LED for a few seconds (10 by default) so you can find it.
- **setBluetooth** turns the plug's Bluetooth on or off, together with Shelly's RPC over Bluetooth, which
  is what the Shelly app uses: in Zigbee mode it reaches the plug only that way, for example to give it
  Wi-Fi so it can update its firmware. The outcome shows in `lastSettingsWrite` and `bluetooth`.
- **setWifi** gives the plug a Wi-Fi network: type the network name and its password (leave the password
  empty for an open network). The plug needs Wi-Fi to update its firmware, in Zigbee mode too. The
  password goes to the plug only; the driver does not log it or keep it. Each of the three writes has
  30 seconds to be acknowledged. The outcome shows in `lastSettingsWrite`, then `wifiStatus` and `wifiIp`
  show whether the plug joined.
- **wifiOff** turns the plug's Wi-Fi off again, for example after a firmware update. The plug keeps the
  network name and password, so a later setWifi with the same values turns it back on. The outcome shows in
  `lastSettingsWrite`, then `wifiStatus` and `wifiIp` show the change.

None of them touches the relay.

## Firmware updates

The plug fetches firmware over Wi-Fi, also in Zigbee mode; Hubitat cannot update it over Zigbee. Give it
a network with **setWifi** (or with the Shelly app over Bluetooth), wait until `wifiIp` shows an address,
then update it from the Shelly app or from the plug's own web page at that address. The update restarts
the plug, and it comes back as set under power-on state (see [Plug settings](#plug-settings)). Press
Refresh afterwards: the driver sees the new firmware and reads the plug's units again. Run **wifiOff** when
the plug does not need Wi-Fi any more: one plug in testing kept dropping off Zigbee while its Wi-Fi was on.

## Trips

The plug protects itself: above 15 A (1800 W), or above its own power limit, it switches off. When the
plug turns itself off within ten seconds of the hub switching it on (or of a button press, if it was
drawing power by then), or while its recent load was at least 90% of the cutoff the plug confirmed (1800 W unless you set one), the driver counts a trip, logs a
warning and updates `tripCount` and `lastTrip`. The recent load is taken from the plug's power readings
since it was last switched on: the 0 W it reports once or twice after cutting the load does not hide what it
carried, and an earlier cycle's load does not count. A steady heater reports its load once, and that report can
arrive just before the switch-on it belongs to; the newest reading of the current reporting period counts then. A rule
can watch `tripCount` and stop switching the plug back on. Pressing the plug's button on and off is not a
trip, and neither is the auto-off timer: an off that arrives when the timer the driver set is due is logged
as the timer. A timer set outside the driver (in the Shelly app, say) is unknown to it, so its off under a
heavy load can count as a trip. Configure keeps the cutoff the plug confirmed; a plug that was factory reset
is back at 1800 W, so save the settings again after one.

One plug in testing ran a heater that drew about 2,000 W. It tripped two seconds after every `on`, every
ten minutes, for hours. If you see trips, the load is too big for the plug.

## Plug settings

Four groups of settings live in the plug, not in the hub: what it does after a power cut, its cutoffs
(power, current, voltage), its auto-off timer, and its LED ring. The driver sends them through Shelly's own channel over Zigbee (or, for the power-on state, the
standard Zigbee attribute when the plug offers it; firmware 2.0.1 does not). Hubitat cuts the plug's
replies short, but enough of each reply arrives to tell an accepted setting from a refused one; the
result shows in `lastSettingsWrite`. A setting that comes back refused or unconfirmed is tried again the
next time you save the settings.

The driver will not set a cutoff below what the plug is drawing at that moment, since the plug would
switch off at once. That check runs before every part of the write goes out, and it needs a power (or
current, or voltage) reading from the current reporting period, taken since the plug was last switched on;
otherwise the write waits for a Refresh and the next save. A setting you change while an earlier write is
still out is sent on its own; the earlier write is dropped.

## Reporting

Configure asks the plug to report at most every 10 seconds and at least every "Longest time between
reports". It also asks for a report whenever a reading moves by:

| Reading | Change |
|---|---|
| Power | 5 W (setting) |
| Current | 0.05 A |
| Voltage | 1 V |
| Frequency | 0.1 Hz |
| Energy | 0.01 kWh |

The switch reports right away when it changes. The thresholds are converted into the plug's own units
before they are sent, so they mean the same thing on every firmware.

## Health check

Every 15 minutes the driver checks when it last heard from the plug. After three silent reporting
periods plus a minute (31 minutes with the default 600 s) it asks the plug for its switch state. An idle
plug can stay quiet that long while still reachable, so only a question that goes unanswered for a
minute sets `healthStatus` to `offline`. The next message from the plug sets it back to `online`.

## Units

On Configure the driver reads the plug's multiplier and divisor for voltage, current, power and
frequency (Electrical Measurement cluster 0x0B04) and for energy (Metering cluster 0x0702). It reads
them again when the plug reports a new firmware build.

The plugs tested so far report these units:

| Firmware (`softwareBuild`, `firmwareMT`) | Voltage | Current | Power | Frequency | Energy |
|---|---|---|---|---|---|
| 2.0.0, 1490-1852-020000FF | 0.01 V | 1 mA | 1 W | 0.01 Hz | 1 mWh |
| 2.0.1, 1490-1852-020001FF | 0.01 V | 1 mA | 1 W | 0.01 Hz | 1 mWh |
| 2.2.0-beta1, 1490-1852-02020021 | 0.01 V | 1 mA | 1 W | 0.01 Hz | 1 mWh |

If the plug does not answer for one of them, the driver uses the same units and logs a warning that
names the missing attributes:

| Reading | Default unit |
|---|---|
| Voltage | 0.01 V |
| Current | 1 mA |
| Power | 1 W |
| Frequency | 0.01 Hz |
| Energy | 1 mWh |

If a reading then looks 10 or 100 times off, run Configure again. If that does not fix it, use the
adjustment settings and please open an issue with the plug's firmware (the `softwareBuild` and
`firmwareMT` data values) and the log lines from Configure.

## Not supported

- **Readings over Shelly's own channel.** Hubitat cuts its replies short, so only plug settings use it;
  every reading comes from standard Zigbee.
- **Other Shelly Gen4 plugs.** Only the Plug US is tested. The Power Strip 4 Gen4 has four outlets and
  needs a different driver.

## Credits

The Zigbee attribute map and the way messages are parsed follow the
[Shelly 1PM Gen 4 Zigbee Driver](https://github.com/joaomf/hubitat) by joaomf and kkossev (Apache
License 2.0). Zigbee2MQTT's definition for the S4PL-00116US showed which clusters the plug uses and that
its units should be read from the device.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
