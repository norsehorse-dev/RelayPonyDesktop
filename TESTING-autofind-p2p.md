# Testing the autofind + Wi-Fi Direct work

Status of each item as of the last session. "Verified" means observed working on real hardware;
"unverified" means it compiles and is reasoned through but has never run in the situation it targets.

| Area | Status |
|---|---|
| Beacon codec (encode/decode/fuzz) | Verified — unit tests, 50k fuzzed frames |
| Beacon over a phone hotspot, phone ↔ Mac | **Verified on hardware** (no cellular data) |
| Beacon over a hotspot with cellular active | Unverified — needs a phone with a data plan |
| Fixed port 45789, both apps | Verified |
| Send by address, both directions | Verified |
| mDNS multi-interface binding | Verified on macOS + Linux container |
| Subnet sweep | Verified in a container; gateway detection is Linux-only |
| Wi-Fi Direct on Linux | **Unverified** — mock only, never run against a radio |
| .deb / AppImage / AUR packaging with the new files | Unverified |

---

## 1. LAN + hotspot discovery (done)

Phone on Receive, hotspot on, second device joined to it.

```
relaypony doctor
```

Expect the phone listed with a `[beacon]` or `[mDNS]` tag and `— reachable`. The tag is the point:
`beacon` means the new mechanism did the work, `mDNS` means the old one coped. Both are fine
outcomes; only "not listed at all" is a failure.

Also worth confirming, since it exercises the reverse direction: the phone's Send tab should list the
desktop after tapping Refresh.

## 2. Wi-Fi Direct on Linux (not started)

**Gate first.** On the target laptop, before anything else:

```
iw list | grep -A 12 "Supported interface modes"
```

Both `P2P-client` and `P2P-GO` must appear. That is exactly the condition wpa_supplicant uses to
enable P2P, so if either is missing, stop — no amount of software helps. Broadcom parts (including
most Macs) frequently fail this.

If it passes:

```
relaypony p2p check     # every precondition, with the blocker named if any fail
relaypony p2p find      # ~20s; phone should be on "Find devices" under Connect directly
relaypony p2p send <file> --peer <mac>
```

Two environment problems to expect, both called out by `p2p check`:

- **The control socket is usually root-only.** Unless the deployment set `GROUP=` in
  wpa_supplicant.conf you will need passwordless sudo or pkexec, which `p2p check` reports.
- **NetworkManager races for the group interface.** It has its own Wi-Fi-P2P support and will try to
  manage `p2p-wlan0-N` and run its own DHCP on it. Before testing:
  ```
  # /etc/NetworkManager/conf.d/99-relaypony-p2p.conf
  [keyfile]
  unmanaged-devices=interface-name:p2p-*
  ```

On failure, `p2p send` prints an ordered transcript of every command and event. That is the thing to
capture — it distinguishes "driver refused", "invitation not accepted", and "group formed but no IP".

## 3. Linux packaging (not started)

The patch adds files to `src/main/kotlin/com/relaypony/desktop/` and
`vendor/transport/com/relaypony/transport/`. Both are covered by existing source sets, so no build
config changed — but confirm `packageDeb`, the AppImage, and the AUR build all still produce a
working binary, and that `relaypony doctor` runs from the installed package rather than only from
`./gradlew run`.

Firewall, if firewalld or ufw is active:

```
firewalld:  sudo firewall-cmd --add-service=mdns --add-port=45790/udp --add-port=45789/tcp
ufw:        sudo ufw allow 5353/udp && sudo ufw allow 45790/udp && sudo ufw allow 45789/tcp
```

---

## Notes for whoever picks this up

**The two repos have diverged.** RelayPonyDesktop's `vendor/` carries a newer snapshot than
RelayPonyAndroid ships: `WireProtocol`, `Session` and `SocketTransfer` all differ, and the desktop's
`WireProtocol` has wire-v2 prep (`MAX_WIRE_VERSION`, `MW_KEY`, `parseMaxWire`) that the phone tree
does not. Anything written for one side and copied to the other has to stick to the intersection —
`BeaconDiscovery.advertise()` takes `maxWire` as a parameter for exactly this reason.

**New strings are English-only.** `values-de`, `-es`, `-fr`, `-hi`, `-ja`, `-pt-rBR` will fall back
until translated.

**The beacon publishes the device name and the public age handle** to the local broadcast domain —
the same facts the existing mDNS TXT record already published, so no new exposure. Trust is still
pinned by handle, so a forged announcement gets an attacker nothing.
