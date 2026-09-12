# airpods-on-android

AirPods battery on the home screen, an iOS-style card when they connect, and playback that pauses
when you take a pod out and resumes when you put it back — on an Android phone.

> The gadgets did not come along for the crash landing. The AirPods stayed on Planet iOS, and this
> is the first experiment in getting them to treat an Android phone as something other than a
> stranger.

**Series:** Guest Pass to the Walled Garden
**Status:** scaffolded and building; not yet verified against real AirPods. The in-ear bit layout
in particular is inherited from community work and has to be confirmed on this pair.

## Why I built this

Free apps already show AirPods battery and a connect card on Android. The third thing — pause
when a pod comes out, resume when it goes back — is the one they put behind a paywall, and it is
also the most interesting one technically, because it depends on a status bit Apple never
documented. So the paid feature became the experiment.

## The iOS brain

On iOS none of this is a feature; it is the pods. Battery shows up in a card, the card appears on
open, and playback follows your ears, all because Apple owns both ends of the link and speaks a
private protocol over it. An iOS developer never sees any of this as API. It is just there.

## What Android exposes

| Need | Android API | Category |
|---|---|---|
| Know when the pods connect | ACL / A2DP connection broadcasts to a manifest receiver | open standard (Bluetooth) |
| Battery, case, in-ear state | BLE advertisement, Apple manufacturer data 0x004C type 0x07 ("proximity pairing") | **undocumented Apple protocol, community-decoded** |
| Hear it | `BluetoothLeScanner` with a manufacturer-data filter, `BLUETOOTH_SCAN` (`neverForLocation`) | public API |
| Stay alive while connected | Foreground service, type `connectedDevice`, started from the Bluetooth broadcast | public API |
| Pause / resume | `AudioManager.dispatchMediaKeyEvent` — the same media key a headset button sends | public API |
| Home-screen battery | `AppWidgetProvider` + `RemoteViews` | public API |
| Connect card | `TYPE_APPLICATION_OVERLAY`, falling back to a heads-up notification | public API, user-granted |

The middle row is the whole story. Apple's beacon is meant for iPhones; Android hears the same
radio and only has to be told what the bytes mean. That knowledge comes from OpenPods, CAPod and
the people who compared bytes to behaviour, not from Apple, and it can change with a firmware
update. Every claim below is against **AirPods 4, firmware 8B39**.

## Experiment

Three features, one service:

1. **Widget.** A RemoteViews widget the monitoring service repaints whenever a beacon changes the
   numbers. No polling by the launcher.
2. **Connect card.** On the A2DP connection broadcast the service starts, waits about a second for
   the first beacon so the card has numbers, and slides a card up from the bottom. Disconnect shows
   a shorter one. Without the overlay permission the same text goes out as a heads-up notification.
3. **Ear detection.** Each beacon carries two in-ear bits. A small state machine debounces them
   (beacons flicker), pauses when a pod leaves an ear while audio is playing, remembers that *it*
   paused, and resumes only if the pod comes back within ten minutes and nothing has been played
   since. "Either pod" mirrors iOS; "Both pods" is the quieter option.

**Battery.** The service exists only while the pods are connected, when the Bluetooth radio is
already up for audio. The scan runs in low-latency mode only while something is playing — that is
when a pod coming out has to be noticed at once — and drops to balanced mode otherwise, with a
ten-second fast warm-up after connecting so the card and widget get numbers immediately. Disk is
written only when a beacon's bytes change, and a watchdog stops the service if nothing has been
heard for three minutes and no AirPods are connected for audio, so a missed disconnect broadcast
cannot leave the radio scanning all day. Measured numbers belong in *What I learned*, after a real
listening session.

The pods' BLE address is randomised, so the phone cannot ask "is this beacon mine?". The service
follows the strongest beacon of the last ten seconds and ignores anything weaker than about
-75 dBm, which in practice means the pair in your ears, not the pair on the next desk.

## Architecture

```
Bluetooth ACL / A2DP broadcast ──▶ BluetoothEvents (manifest receiver)
                                        │ start / notify
                                        ▼
                                   PodsService (FGS, connectedDevice)
                     BLE scan ─────▶ ProximityParser ──▶ PodsStore ──▶ widget, notification, UI
                                          │
                                          ▼
                                    EarDetector ──▶ MediaControl (media key events)
                                          │
                                    ConnectPopup (overlay card / heads-up)
```

```
app/src/main/java/com/ioscastaway/airpods/
├── pods/       ProximityParser, EarDetector, PodsStatus, PodsModel   (pure Kotlin, unit-tested)
├── platform/   PodsService, BluetoothEvents, MediaControl, PodsStore
├── widget/     BatteryWidgetProvider
├── popup/      ConnectPopup
└── ui/         MainActivity (setup, settings, beacon inspector), MainViewModel
```

## Setup

```bash
./gradlew installDebug
```

Open the app, grant **Nearby devices**, **Notifications**, and optionally **Display over other
apps** and unrestricted battery. Connect the AirPods; the monitor starts on its own. Long-press
the home screen to add the battery widget.

## What I learned

*(to be filled in from device runs — the first job is confirming the in-ear bits on 8B39 with the
beacon inspector)*

## iOS comparison

- **Reading pod state:** iOS gets it through a private Apple protocol it owns; Android gets it by
  listening to a public radio and decoding an undocumented message. **Same data, opposite
  provenance** — Apple's is an API to Apple and nobody else.
- **Pause on removal:** on iOS it is a property of the product. On Android it is ~150 lines and a
  foreground service. Both work; only one can break on a firmware update.
- **Home-screen battery:** iOS has the Batteries widget; Android has this. Parity in outcome.
- **The card:** iOS draws it as the system. Android lets an app draw it over other apps with the
  user's permission, or asks the notification system to interrupt. Parity, with a permission
  prompt attached.
- **What Android cannot do here (yet):** noise-control switching, spatial audio settings and
  firmware-level features live behind Apple's proprietary control channel, not the beacon.

## Limitations

- **Security / privacy.** `BLUETOOTH_SCAN` sees every advertising device nearby, not just yours;
  this app decodes only Apple proximity frames and stores only the last one. Nothing leaves the
  device. No Apple ID, no pairing keys.
- **Undocumented protocol.** The bit layout is community knowledge verified against one firmware.
  A future firmware can move or encrypt it. The beacon inspector exists so that is a ten-second
  check rather than a mystery.
- **Which pods are mine.** Strongest-beacon heuristics can pick a neighbour's pair on a crowded
  train. A future version should tie the beacon to the connected classic device via the
  Companion Device Manager.
- **OEM battery managers** can still kill a foreground service; the unrestricted-battery toggle is
  there for that.

## Verdict

Not yet earned; the pods have not been in anyone's ears with this running. This section is written
after that, not before.

---

**Reason #06 I don't regret switching to Android:**
_(reserved until the experiment earns it)_
