# Bluehood Scanner

**[English](README.md) · [日本語](README.ja.md)**

A passive Bluetooth LE scanner for Android that surfaces the devices which keep
reappearing around you — across separate places, on separate days — and detects
trackers that have been separated from their owner.

It never talks to the network. Every byte of analysis happens on your phone.

---

## What this is

You think you're being followed, but you have nothing to show for it. Human memory
is fragile under challenge, and "I saw the same person three times" is hard for
anyone to act on.

Bluehood quietly records the radio signals that were actually near you, and surfaces
only the patterns that coincidence struggles to explain.

- **See your neighborhood** — a live list of what nearby devices leak. Named devices
  show their real name; silent ones resolve to a vendor.
- **Detect a follow** — only devices that reappear across *independent* places and
  times score at all. A fellow passenger on one train ride sinks to zero.
- **Spot hidden trackers** — AirTag, Tile, SmartTag and similar are identified by
  their separated-from-owner advertisement signature.
- **Hunt down a tag** — physically locate a tag judged to be planted on you, by relative
  signal strength. It finds your own belongings; it does not follow anyone.
- **Offline maps** — OpenStreetMap via osmdroid, no Google Play Services. Pre-cache
  tiles and the app works with no network at all — airplane mode, or blocked by a
  firewall.

## What it refuses to do

A tool like this turns into a weapon very easily. These limits are enforced in the
code, not just in the docs — and they are locked by unit tests.

| | |
|---|---|
| **Never stores the other party's location** | There is no column for it in the database. The only coordinates ever written are your own. |
| **Never identifies a person** | Trackers are classified by *type* only. Phones are never de-anonymised down to an individual. |
| **Has no active-tracking module** | It could be built. It wasn't. A single phone can only ever log what was near *you* — and everything past that line is where the law is. The Hunt tab is not an exception: it targets a device already judged to be following you, and all it has is the signal strength at your own hand. It holds no position for the other party. |
| **Never phones home** | `INTERNET` is used for map tiles and nothing else. Cut the app off from the network entirely — airplane mode, or a local-VPN firewall — and every core feature still works. |

The evidence export is a hash-chained, self-centred co-presence log. It exists so you
can hand it to the police — not so you can run your own investigation. Pursuing or
confronting someone yourself damages both your safety and your legal position.

---

## How it works

BLE MAC addresses rotate roughly every 15 minutes. That is not the end of the story.

**Payload fingerprinting.** Instead of the MAC, we hash the service UUIDs, company
IDs, TX power and the stable vendor-specific prefix of each advertisement. The MAC
rotates; the fingerprint survives. Vendors differ in how much of their payload is
stable, so the prefix length is per-vendor (Apple 2 bytes, Microsoft 1, Google 0).

**Place identification.** "Is this somewhere else?" is answered first by the set of
surrounding WiFi access points. That works without a GPS fix, so places can be counted
indoors, and it does not split one room into three locations the way a drifting indoor
fix does — routers don't move and BSSIDs don't rotate. Coordinates (200 m) are the
fallback when no WiFi is visible. The app **never requests a WiFi scan**; it reads the
cache the OS already holds, because an active scan transmits probe requests carrying
your own MAC, which would defeat the purpose of the tool. BSSIDs are stored hashed with
a per-device salt, since a raw BSSID can be turned back into coordinates through public
geolocation databases. Seeing a device repeatedly in one place earns nothing; only a
reappearance after a 30-minute gap counts as a separate occasion.

**Deliberately strict scoring.** The first two places score zero. A colleague who
shares your commute is not a stalker. Only additional places, non-contiguous time and
multi-day persistence push a device past the threshold of 5.

**Proximity gate.** Tracker follow-detection requires RSSI ≥ −65 dBm — the range of a
bag or a pocket. A stranger's AirTag passing on the street never triggers it, so
widening the detection net does not widen false alarms.

**Movement is required.** Following means *you moved and something came with you*. If
you have not travelled at least 200 m, the app reports **undecidable** rather than
"nothing found". A low score because we cannot tell must never look like a low score
because you are safe.

### Lessons from real-device testing

Two false-positive classes were found on hardware and are now locked by tests:

- **Geohash cells are not a distance measure.** Counting distinct places by geohash
  cell produced *three* places while the phone never left a 103 m square — indoor GPS
  jitter straddled cell boundaries. Places are now counted by actual distance.
- **Dwell time is not movement.** "Near for 15 minutes" alone marked a tracker as
  following, which fires just by sitting at home. Movement is now mandatory.

---

## Honest limits

- **PHY-layer fingerprinting is impossible on a phone.** Identifying a radio by its
  waveform needs dedicated hardware such as an SDR. A fully anonymised device whose
  payload also changes cannot be re-identified across rotations with certainty.
- **Location services must be on.** Without `neverForLocation`, Android silently
  discards *all* BLE scan results when the location master toggle is off. The app
  detects this and says so rather than pretending to scan.
- **"Approximate location" breaks it.** Android drops scan results for apps holding
  only `ACCESS_COARSE_LOCATION`. Grant precise location.
- **The hash chain is not proof of authenticity.** It proves internal consistency
  only. Anyone with this app can construct an arbitrary sequence with a valid chain.
  Do not describe the export as tamper-proof until an external timestamp authority
  (RFC 3161) is involved.

---

## Install

Download the APK from [Releases](../../releases).

The build is self-signed, so Android will warn about an unknown source. **That is
expected** — which is exactly why every release publishes both the APK hash and the
signing-certificate hash. See [RELEASE_NOTES.md](RELEASE_NOTES.md) for the current
values and for how to check them.

The APK hash changes with every build. The signing certificate must not:

```
797bf4fe07c8b352090b2914491149c6826870429831828d5aca1505c88d8092
```

If a release shows a different certificate, it did not come from this project. Do not
install it, and open an issue.

Requires **Android 13+** and Bluetooth LE. Stock Android is the target: the app uses
no Google Play Services and no ROM- or vendor-specific API, so it does not need a
hardened OS. Development builds are tested on a Pixel.

If your vendor ROM stops background services aggressively — Samsung, Xiaomi and OPPO
all do — exempt Bluehood from battery optimisation. A stopped foreground service means
no scanning at all.

After installing, three things must be enabled or the app will tell you it cannot
scan: Bluetooth, location services (the system-wide toggle), and precise location
permission.

## Build

```bash
./gradlew assembleDebug          # development build
./gradlew testDebugUnitTest      # detection-logic tests
./gradlew assembleRelease        # requires keystore.properties (not in this repo)
```

Release builds are minified with R8. The ProGuard rules keep `osmdroid.config`
(it derives SharedPreferences keys from field names via reflection, so obfuscating
it silently breaks the map) and the Room entities.

## Related

- [bluehood-ios](https://github.com/pastetosmoke/bluehood-ios) — iOS companion.
  A different product by necessity: iOS forbids undirected background BLE scanning
  and withholds raw advertisement data, so the iOS build is a manual sweep tool.

## License

[Mozilla Public License 2.0](LICENSE).

File-level copyleft: modifications to these files must be published, but new files
added alongside them may stay proprietary. Commercial use and app-store distribution
are both fine.

**A license cannot prevent misuse.** It governs distribution terms, not behaviour.
The guarantee that this stays a counter-surveillance tool rather than a surveillance
tool lives in the design — no column for the other party's location, no individual
identification, no active-tracking module — not in this file.
