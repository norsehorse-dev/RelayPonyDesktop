# Wi-Fi Direct on Ubuntu — debugging notes

Supersedes the earlier version of this file. Written against what the journal actually showed.

## Interface names on this machine

Get these right — they are not derivable from each other:

| | |
|---|---|
| Station (the real netdev) | `wlp0s20f3` |
| P2P control interface | `p2p-dev-wlp0s20` |

`p2p-dev-wlp0s20` is **truncated**: `p2p-dev-` plus a 9-character name exceeds the kernel's
15-character limit, so wpa_supplicant cut the `f3`. It is also not a netdev, so it never appears in
`ip link` — that's normal.

```bash
S="-p /run/wpa_supplicant -i p2p-dev-wlp0s20"; echo "$S"
```

Every `wpa_cli $S …` should print `Selected interface 'p2p-dev-wlp0s20'`. If it prints `wlp0s20f3`,
`$S` is unset in that shell and you are driving the station interface instead.

## What's been established

- Preflight passes: driver advertises `P2P-GO` and `P2P-client`, `wpa_cli` present, sudo available.
- Discovery works — the phone, a TV and a printer have all been found.
- The failures so far have two causes, not one:
  1. **The phone wasn't in the peer table** at connect time. `p2p_connect` returns a bare `FAIL` for
     an unknown peer. Android stops being discoverable roughly two minutes after "Find devices" is
     tapped.
  2. **The station was associated at `freq=5785`** — 5 GHz. Phones negotiate P2P on 2.4 GHz social
     channels (1/6/11), and single-radio hardware cannot run a group on a different channel than an
     active station connection.

---

## 1. Did the interface survive the disconnect?

`nmcli device disconnect` may have taken the P2P interface down with it — NetworkManager owns
wpa_supplicant's interface lifecycle, and the journal showed `nl80211: deinit ifname=p2p-dev-wlp0s20`
when the radio was blocked earlier.

```bash
ls -la /run/wpa_supplicant/
sudo wpa_cli $S status
sudo wpa_cli $S p2p_find 30 type=social
```

If `p2p-dev-wlp0s20` is missing from that directory, or `p2p_find` now returns `FAIL`, the interface
is gone. Bring it back:

```bash
nmcli device connect wlp0s20f3
sleep 5
ls -la /run/wpa_supplicant/
```

## 2. Get the radio onto 2.4 GHz

Disconnecting Wi-Fi is one way to free the channel, but it risks taking the P2P interface with it.
Joining a **2.4 GHz** network instead achieves the same thing and keeps NetworkManager happy — the
problem was never that a station connection exists, only that it was on a band phones won't use.

```bash
nmcli -f SSID,CHAN,FREQ,SIGNAL device wifi list
nmcli device wifi connect "<a 2.4 GHz SSID>"
sudo wpa_cli $S status | grep ^freq
```

You want `freq=` in the 2400s. Channels 1, 6 and 11 are the ones P2P negotiates on.

If there's no 2.4 GHz network available, disconnect instead and immediately re-check that the P2P
interface is still alive:

```bash
nmcli device disconnect wlp0s20f3
ls /run/wpa_supplicant/
sudo wpa_cli $S p2p_find 5
```

## 3. Fresh discovery on both sides, then connect with no gap

Clear anything left over:

```bash
sudo wpa_cli $S p2p_cancel
sudo wpa_cli $S p2p_group_remove '*'
sudo wpa_cli $S p2p_flush
```

**On the phone, right now:** RelayPony → Send → Connect directly → **Find devices**. Receive tab →
Connect directly → **Arm receive**. Screen on, app in the foreground. Hotspot **off**.

Then, without waiting:

```bash
sudo wpa_cli $S p2p_find 60 type=social
sleep 15
sudo wpa_cli $S p2p_peers
```

**Do not proceed unless `ae:30:11:50:8f:bf` is in that list.** If it isn't, re-tap Find devices on
the phone and run the find again — everything downstream depends on this.

```bash
sudo wpa_cli $S p2p_connect ae:30:11:50:8f:bf pbc go_intent=0 provdisc
```

`OK` means the request went out and the phone should prompt. An 8-digit number means it fell back to
PIN mode. `FAIL` means refused — check the journal.

## 4. Reading what happens next

Leave this running in a second terminal throughout:

```bash
sudo journalctl -u wpa_supplicant -f
```

| What you see | What it means |
|---|---|
| `P2P-PROV-DISC-PBC-RESP` | The phone answered — the dialog should be up |
| `P2P-GO-NEG-REQUEST` / `P2P-GO-NEG-SUCCESS` | Negotiation running, group forming |
| `P2P-GROUP-STARTED … client … go_ip_addr=` | Success. Note the interface name and the GO address |
| `P2P-FALLBACK-TO-GO-NEG reason=peer-not-running-GO` then silence | The phone isn't listening — it lapsed out of discovery |
| `P2P-GO-NEG-FAILURE status=…` | Negotiation reached the phone and failed; status names why |
| `FAIL-CHANNEL-UNAVAILABLE` | Band conflict — back to step 2 |
| Nothing at all after `p2p_connect` → `OK` | The peer was stale; it never reached the phone |

## Cleanup between attempts

A half-formed attempt makes the next one fail confusingly. Always:

```bash
sudo wpa_cli $S p2p_cancel
sudo wpa_cli $S p2p_group_remove '*'
sudo wpa_cli $S p2p_stop_find
```

If `p2p_find` starts returning `FAIL` when it worked before, wpa_supplicant is wedged:

```bash
sudo systemctl restart wpa_supplicant
sudo systemctl restart NetworkManager
sleep 5
ls /run/wpa_supplicant/
```

---

## When to stop

Wi-Fi Direct is the bonus feature here, not the fix anyone asked for. It's driver-, distro- and
NetworkManager-dependent, and the app already reports honestly via `p2p check` whether a given
machine can even attempt it.

The reported bug — discovery over a phone hotspot — is tested and working, and the desktop release
is sitting unshipped waiting on this. A reasonable call is to ship with Wi-Fi Direct marked
experimental and revisit when someone with different hardware reports back.

**The higher-value hour that hasn't been spent yet** is the hotspot discovery test on this same
machine, which is the actual reported scenario and has never been run phone-to-Linux:

```bash
# phone on Receive, hotspot ON, Ubuntu joined to it
./RelayPony-x86_64.AppImage doctor
```

Look for the phone tagged `[beacon]` and `— reachable`.
