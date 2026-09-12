# airpods-on-android

AirPods battery on the home screen, an iOS-style card when they connect, and playback that pauses
when you take a pod out and resumes when you put it back — on an Android phone, over the same
channel the iPhone uses.

> The gadgets did not come along for the crash landing. The AirPods stayed on Planet iOS, and this
> is the first experiment in getting them to treat an Android phone as something other than a
> stranger.

**Series:** Guest Pass to the Walled Garden
**Status:** working on a Galaxy Z Fold 8 (One UI 9.0, Android 17) with AirPods 4 (firmware 8B39):
1 % battery for both pods and the case, ear events within about 100 ms, pause/resume verified with
YouTube. See *What I learned* for what did **not** work on the way there, which is most of the story.

## Why I built this

Free apps already show AirPods battery and a connect card on Android. The third thing — pause
when a pod comes out, resume when it goes back — is the one they put behind a paywall, and it is
also the most interesting one technically, because it depends on state Apple never documented. So
the paid feature became the experiment. It turned into a different experiment: on this pair of
AirPods the free apps' data source is silent, and the only thing that works is the iPhone's own
channel.

## The iOS brain

On iOS none of this is a feature; it is the pods. Battery shows up in a card, the card appears on
open, and playback follows your ears, all because Apple owns both ends of the link and speaks a
private protocol over it. An iOS developer never sees any of this as API. It is just there.

## What Android exposes

| Need | Android API | Category |
|---|---|---|
| Know when the pods connect | ACL / A2DP connection broadcasts to a manifest receiver; `BOOT_COMPLETED` covers pods already connected after a reboot | 1 · open standard |
| Battery, ear state — the iPhone's way | **AAP (Apple Accessory Protocol) over classic L2CAP, PSM 0x1001**, via `BluetoothDevice.createInsecureL2capSocket` | 3 · reverse-engineered (LibrePods, CAPod); the constructor is a hidden API |
| Battery, ear state — the beacon way | BLE advertisement, Apple manufacturer data 0x004C type 0x07 ("proximity pairing"), `BluetoothLeScanner` | 3 · reverse-engineered (OpenPods, CAPod); public API; **not emitted by AirPods 4 / 8B39 in any state I could produce** |
| Stay alive while connected | Foreground service, type `connectedDevice`, started from the Bluetooth broadcast | public API |
| Pause / resume | `AudioManager.dispatchMediaKeyEvent` — the same media key a headset button sends | public API |
| Home-screen battery | `AppWidgetProvider` + `RemoteViews`, rings and silhouettes drawn to bitmaps | public API |
| Connect card | `TYPE_APPLICATION_OVERLAY`, falling back to a heads-up notification | public API, user-granted |

The two middle rows are the whole story. Every Android AirPods app I could find lives on the third
row: it listens to the pods' BLE beacon and decodes it. That works when the pods send the beacon.
This pair, on this firmware, essentially does not, so the app opens the second row instead — the
accessory channel the iPhone talks on — and the beacon becomes a fallback. Every claim below is
against **AirPods 4, firmware 8B39**, on a Samsung Bluetooth stack.

## Experiment

Three features, one service, two sources:

1. **Widget.** Three cells — left pod, right pod, case — each a battery ring around a silhouette,
   repainted by the service whenever the pods report a change. No polling by the launcher.
2. **Connect card.** On the connection broadcast the service starts, opens the accessory channel,
   waits up to 2.5 s for the first numbers, and slides a card up from the bottom. Disconnect shows
   a shorter one. Without the overlay permission the same text goes out as a heads-up notification.
3. **Ear detection.** The channel pushes an ear event the moment a pod's state changes. A small
   state machine pauses when a pod leaves an ear while audio is playing, remembers that *it*
   paused, and resumes only if the pod comes back within ten minutes and nothing has been played
   since. "Either pod" mirrors iOS; "Both pods" is the quieter option. The same machine debounces
   beacons when it is running on those.

**Source selection.** While the accessory channel is open it is the only source: 1 % battery,
instant ear events, and the BLE scan is switched off, so the radio does nothing extra beyond the
audio link that is up anyway. If the channel drops the client retries with backoff (eight attempts,
0.5 s to 15 s); while it is down the scan comes back and the beacon takes over — 10 % steps, when
the firmware sends them.

## Architecture

```
Bluetooth ACL / A2DP broadcast ──▶ BluetoothEvents (manifest receiver)
                                        │ start / notify
                                        ▼
                                   PodsService (FGS, connectedDevice)
          AAP over L2CAP 0x1001 ───▶ AapClient ──▶ AapParser ─┐
                (primary)                                     ├──▶ PodsStore ──▶ widget, notification, card, UI
          BLE scan ────────────────▶ ProximityParser ─────────┘
                (fallback)                                    │
                                                        EarDetector ──▶ MediaControl (media key events)
```

```
app/src/main/java/com/ioscastaway/airpods/
├── pods/       AapParser, ProximityParser, EarDetector, PodsStatus, PodsModel   (pure Kotlin, unit-tested)
├── platform/   AapClient, PodsService, BluetoothEvents, BootReceiver, MediaControl, PodsStore, HeadsetVendorEvents
├── widget/     BatteryWidgetProvider, WidgetArt
├── popup/      ConnectPopup
└── ui/         MainActivity (setup, settings, inspector), MainViewModel
```

## Setup

```bash
./gradlew installDebug
```

Open the app, grant **Nearby devices**, **Notifications**, and optionally **Display over other
apps** and unrestricted battery. Connect the AirPods; the monitor starts on its own, and comes back
after a reboot without a tap. Long-press the home screen to add the battery widget.

## What I learned

All of this is measured on the device above, not read somewhere.

**The beacon is not there.** Scanning unfiltered at the highest duty cycle, legacy and extended
advertising, on every PHY, I collected over six thousand Apple manufacturer-data frames from this
pair and its surroundings — types 0x10 (Nearby Info), 0x12 (Find My), 0x09, 0x16 — and **zero**
type 0x07 frames, in every state I could produce: lid open, lid closed, one pod out, both out, in
ears, connected to the phone, connected to a Mac and playing, Mac Bluetooth off. Older firmware
sends 0x07 continuously; that is what the community decoders were written against.

**The free apps are on the same beacon.** The Bluetooth stack's own log shows the popular battery
app starting a ~25 s balanced-mode BLE scan at each connect, filtered on Apple's manufacturer id
with an all-zero mask, receiving 24–28 frames per scan — the same 0x10/0x12 frames I saw. Its
numbers change slowly, which means 0x07 *does* appear under some condition I have not found (a
short window at pairing time is the leading theory). It is not a live source on this firmware.

**Every other side door is shut.**

| Path | Result |
|---|---|
| HFP vendor command `+IPHONEACCEV` via `ACTION_VENDOR_SPECIFIC_HEADSET_EVENT` | never delivered |
| `BluetoothDevice.getBatteryLevel()` (system API, reflection) | `-1` (unknown) |
| `BluetoothDevice.getMetadata()` for the untethered-battery keys | `SecurityException` |
| `ACTION_BATTERY_LEVEL_CHANGED` broadcast | none |

The phone's own Settings app shows no AirPods battery either, for the same reasons.

**The public L2CAP API cannot reach the pods.** `createL2capChannel` and
`createInsecureL2capChannel` build LE CoC sockets. The stack log shows the connection going
`CONNECTING → DISCONNECTED` in the same millisecond, type `L2CAP_LE`: nothing goes on the air,
because the pods have only a classic (BR/EDR) link. AAP lives on a classic L2CAP channel, so the
public API is the wrong tool by construction, not by bug. A One UI security update (2026-08 →
2026-09) changed nothing here.

**The hidden classic constructor works on the first try.** `createInsecureL2capSocket(0x1001)`
exists on `BluetoothDevice` but is on the hidden-API blocklist; plain reflection reports
`NoSuchMethodException`. With the filter lifted for this process (LSPosed's HiddenApiBypass, pure
Java, nothing on the device changes) the socket connects in ~35 ms, the stack log shows a real
`L2CAP` connection, and the pods answer the LibrePods-documented handshake immediately with
metadata (name, model number A3053, serial, firmware build), a battery packet in 1 % steps, and
ear events for every change. Pause and resume through YouTube followed on the same run.

**Timing.** Connect broadcast → channel open → first battery packet: under two seconds, most of it
waiting for A2DP to settle. Ear event → media key: same-frame; the delay you perceive is the
player's.

## iOS comparison

- **Reading pod state:** iOS gets it through AAP, which it owns. Android gets it through AAP too —
  by opening a socket the platform has a constructor for but hides. A third-party iOS app can do
  neither: CoreBluetooth strips Apple's manufacturer data from advertisements it hands to apps,
  and there is no path to a classic L2CAP channel on a paired accessory at all. **Same protocol,
  and only one platform lets an app on it.**
- **Pause on removal:** on iOS it is a property of the product. On Android it is one L2CAP socket
  and ~150 lines. Both work; one can break on a firmware update, the other on an Android update.
- **Home-screen battery:** iOS has the Batteries widget; Android has this, at the same precision.
- **The card:** iOS draws it as the system. Android lets an app draw it over other apps with the
  user's permission, or asks the notification system to interrupt. Parity, with a prompt attached.
- **What the channel opens next:** the same socket carries noise-control switching, renaming,
  conversational awareness and press-and-hold settings — the "Apple only" controls. Not built here,
  but no longer out of reach.

## Limitations

- **Hidden API.** The classic L2CAP constructor is not public. HiddenApiBypass keeps it reachable
  on Android 9–17 today; a future release can close it, and Google Play rejects apps that use it.
  This is a sideloaded personal tool by design. Pixel devices on Android 16+ reportedly reach the
  same channel through the public API; this Samsung stack does not.
- **Undocumented protocol.** AAP packet layouts are community knowledge (LibrePods) verified
  against one firmware. Ear events report *primary/secondary*, not left/right, so the UI shows how
  many pods are in ears, not which.
- **Beacon fallback.** When the channel is down and the firmware sends no 0x07, the widget goes
  stale rather than wrong; the timestamp says so.
- **Which pods are mine.** The channel is opened on the device connected for audio, so this is no
  longer a strongest-beacon guess; the beacon fallback still is.
- **OEM battery managers** can still kill a foreground service; the unrestricted-battery toggle is
  there for that.
- **Art.** The pod and case pictures in the widget were generated by the author with ChatGPT; they
  are not Apple assets.

## Verdict

Earned. The beacon path — the one every guide describes — is dead on this firmware, and the
correct answer was to stop listening at the door and knock on it. Android let me: the socket type
exists, the platform hides it, and a debug-honest bypass opens it. iOS would not have shown me the
door.

---

**Reason #06 I don't regret switching to Android:**
_My AirPods talk to my phone over Apple's own protocol, and the phone let me be the one to answer._
