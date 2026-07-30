# Release notes — drafts

Two GitHub releases and one F-Droid changelog. The F-Droid one is already committed as
`fastlane/metadata/android/en-US/changelogs/2.txt` in the Android repo; it's reproduced at the bottom
for reference.

**Before tagging the desktop:** bump `packageVersion` in `build.gradle.kts` from `2.0.3` to `2.1.0`
(RELEASING.md step one). The Android `versionCode`/`versionName` are already bumped to 2 / "2.0".

Also worth deciding: `release.yml` passes `--generate-notes`, which builds notes from commit titles
and overrides nothing. To use the text below instead, either drop `--generate-notes` and add
`--notes-file NOTES.md`, or publish the release as-is and edit the body afterward.

---

## RelayPony (Android) — v2.0

### Devices find each other on networks where they used to give up

The headline: **transfers now work over a phone's own hotspot.** Previously the phone would share a
hotspot, the other device would join it, and neither could see the other — with no way to force it.

The cause was that Android's device discovery follows the phone's *default* network. While
tethering, that's mobile data, so the phone was announcing itself over cellular and never touching
the subnet it was actually sharing. No amount of retrying on the other device could have helped.

RelayPony now runs a second discovery mechanism alongside the original one, using broadcast rather
than multicast and sending from each network interface explicitly instead of whichever one the system
considers default. It also survives guest and hotel Wi-Fi that filters multicast.

### Discovery is no longer the only way through

Even with two mechanisms, some networks isolate clients from each other entirely. So discovery is now
a convenience rather than a requirement:

- **Send by address** — pick a paired device, type its address, send. Same encryption, same pairing
  requirement; only the way the connection is found is different.
- **The Receive screen shows this phone's address**, so there's something to type on the other side.
- **A fixed transfer port (45789)** instead of a different one every launch, so an address stays
  valid and a firewall rule stays written.

### Wi-Fi Direct explains itself

Wi-Fi Direct couldn't start while the phone was sharing a hotspot — a phone's radio can't be a
hotspot and a Wi-Fi Direct device at the same time — and it reported this as
`Discover failed (framework busy, try again)`, which pointed nowhere. It now says what's actually
wrong, and retries a transient failure instead of giving up immediately.

### Notes

- Pairings and identities are unchanged; this is not a breaking update.
- The new discovery publishes this device's name and its public key on the local network — the same
  facts the previous discovery already published, and nothing more. Files are still encrypted to a
  pinned key, so an impostor announcement gains nothing.
- New interface text is English-only for now; other languages fall back until translated.

Thanks to @Vikranttiw, whose report is the reason all of this exists.

---

## RelayPony Desktop — v2.1.0

Companion release to RelayPony 2.0 for Android. Discovery improvements on both ends, plus Wi-Fi
Direct support on Linux.

### Discovery

- **Broadcast discovery** alongside mDNS, sending from every network interface rather than one
  guessed default. This is what makes a phone hotspot work.
- **Fixed listening port (TCP 45789, UDP 45790 for discovery)**, overridable with `RELAYPONY_PORT`.
  Previously the port changed every run, which made firewall rules impossible to write.
- **mDNS now binds every interface.** It used to pick the first private address it found — often the
  wrong link on a machine with ethernet, `docker0`, `virbr0` or a VPN up — and when it found none it
  fell back to a lookup that resolves to `127.0.1.1` on Arch and Debian, silently confining the
  advertisement to loopback. Both failures were invisible; both are gone, and the interfaces actually
  in use are now reported.
- Peer entries show which mechanism found them and over which interface.

### Sending without discovery

```sh
relaypony send photo.jpg --to "Kevin's Pixel" --host 192.168.43.1
```

Also available in the app as **Send by address**. The Receive screen lists the addresses this
machine can be reached at.

### Wi-Fi Direct on Linux — experimental

Talk to an Android phone with no network in between — no router, no hotspot, no shared Wi-Fi.

```sh
relaypony p2p check     # can this machine do it at all?
relaypony p2p find      # nearby devices
relaypony p2p send file.jpg --peer <mac>
```

Driven through `wpa_supplicant`, so it needs a driver advertising `P2P-GO` and `P2P-client` and
permission to read wpa_supplicant's control socket — `p2p check` reports exactly what is missing and
how to fix it. Linux only; macOS has no public API for it and the Windows one isn't reachable from
the JVM.

**Ship state, plainly: discovery works, group formation is unproven.** On test hardware `p2p check`
passes and `p2p find` reliably lists nearby Wi-Fi Direct devices including phones, TVs and printers.
Forming a group has not yet succeeded end to end on any machine — the last attempts stalled inside
Android's P2P framework rather than on the Linux side. Expect it not to work yet, and treat
`p2p check` as the useful half.

If you try it, the failure output includes an ordered transcript of every wpa_supplicant command and
event, which is exactly what an issue report needs.

### Diagnostics

```sh
relaypony doctor
```

Reports interfaces, what discovery bound to and why anything was skipped, which peers were found and
over which mechanism, whether each is actually reachable, and a scan of the local subnet for anything
discovery missed. Ends with firewall commands for firewalld and ufw when nothing turns up.

### Upgrading

Nothing to do — identities, pairings and the transfer format are unchanged. Older builds remain
discoverable over mDNS; the broadcast mechanism needs this version on both ends.

---

## F-Droid changelog (already committed as `changelogs/2.txt`)

> Devices now find each other on networks where discovery used to fail — including over a phone's own
> hotspot, which never worked before.
>
> • New broadcast-based device discovery, running alongside the existing method
> • Send by address: reach a paired device by typing its address when discovery can't get through
> • The Receive screen now shows the address other devices can reach this phone at
> • A fixed transfer port, so firewall rules and typed addresses stay valid between runs
> • Wi-Fi Direct now explains that it can't run while your hotspot is on, instead of failing with an
>   unhelpful error
