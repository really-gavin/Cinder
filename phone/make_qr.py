#!/usr/bin/env python3
"""Make Cinder phone setup QR codes. Each run makes two:

  setup-<mode>    full setup: factory-reset an Android phone (8 or newer), tap
                  the welcome screen 6 times, connect to Wi-Fi, scan. The phone
                  downloads Cinder, lets it manage the phone, and starts.
  install-<mode>  just the app: scan with any phone camera. Opens the Cinder
                  phone page, which installs the app and sets it up.

    # public: miner codes (rewards go to the card typed on the phone)
    python phone/make_qr.py miner

    # private: node codes; they carry your network key
    python phone/make_qr.py node --announce-key-file announce-key.json

Keep node codes to yourself. Needs: pip install segno
"""
import argparse
import base64
import json
import os

import segno

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
APK_URL = "https://raw.githubusercontent.com/really-gavin/Cinder/main/website/phone/cinder-phone.apk"
SITE = "https://really-gavin.github.io/Cinder/"


def make_extras(mode, site, node=None, public_url=None, tunnel_token=None, peers=None, card=None,
                announce_key=None, also_mine=False, also_node=False):
    extras = {"mode": mode, "site": site}
    if also_mine:
        extras["also_mine"] = "1"
    if also_node:
        extras["also_node"] = "1"
    for k, v in (("node", node), ("public_url", public_url), ("tunnel_token", tunnel_token),
                 ("peers", peers), ("card", card), ("announce_key", announce_key)):
        if v:
            extras[k] = v
    return extras


def install_link(site, extras):
    data = base64.urlsafe_b64encode(json.dumps(extras, separators=(",", ":")).encode()).decode().rstrip("=")
    return site.rstrip("/") + "/phone/#setup=" + data


def setup_json(apk_url, checksum, extras, wifi=None, wifi_password=None, wifi_security="WPA"):
    d = {
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": "app.cinder.phone/.AdminReceiver",
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": apk_url,
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM": checksum,
        "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": True,
        "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE": extras,
    }
    if wifi:
        d["android.app.extra.PROVISIONING_WIFI_SSID"] = wifi
        d["android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE"] = wifi_security if wifi_password else "NONE"
        if wifi_password:
            d["android.app.extra.PROVISIONING_WIFI_PASSWORD"] = wifi_password
    return d


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("mode", choices=["miner", "node"])
    ap.add_argument("--out", help="name used in the file names (default: the mode)")
    ap.add_argument("--apk-url", default=APK_URL)
    ap.add_argument("--checksum", help="defaults to website/phone/checksum.txt")
    ap.add_argument("--site", default=SITE, help="website whose config.js names the network's node")
    ap.add_argument("--node", help="node for a miner to use (otherwise it reads the website's config.js)")
    ap.add_argument("--card", help="card ID a miner pays rewards to (otherwise typed on the phone)")
    ap.add_argument("--public-url", help="node: its public https address")
    ap.add_argument("--tunnel-token", help="node: Cloudflare tunnel token (keep this code private)")
    ap.add_argument("--peers", help="node: other nodes, comma separated")
    ap.add_argument("--announce-key-file", help="node: JSON file with announce_private_key")
    ap.add_argument("--mine", action="store_true", help="node: also mine (use with --card)")
    ap.add_argument("--also-node", action="store_true", help="miner: also run a node")
    ap.add_argument("--wifi", help="Wi-Fi name the phone joins during setup")
    ap.add_argument("--wifi-password")
    a = ap.parse_args()

    checksum = a.checksum or open(os.path.join(REPO, "website", "phone", "checksum.txt")).read().strip()
    key = None
    if a.announce_key_file:
        key = json.load(open(a.announce_key_file))["announce_private_key"]
    elif a.mode == "node":
        raise SystemExit("a node needs --announce-key-file so miners and the website can find it")
    extras = make_extras(a.mode, a.site, a.node, a.public_url, a.tunnel_token, a.peers, a.card, key, a.mine, a.also_node)
    out = a.out or a.mode

    d = setup_json(a.apk_url, checksum, extras, a.wifi, a.wifi_password)
    with open(f"setup-{out}.json", "w") as f:
        json.dump(d, f, indent=2)
    save(json.dumps(d, separators=(",", ":")), f"setup-{out}")
    link = install_link(a.site, {k: v for k, v in extras.items() if k != "site"})
    save(link, f"install-{out}")
    print(f"wrote setup-{out} (full setup) and install-{out} (just the app): .png and .svg")
    if key or a.wifi_password or a.tunnel_token:
        print("these codes contain your network key or a password: don't post them publicly")


def save(text, name):
    qr = segno.make(text, error="m")
    qr.save(name + ".png", scale=8, border=4, dark="#1f1e1b", light="#ffffff")
    qr.save(name + ".svg", scale=8, border=4, dark="#1f1e1b", light="#ffffff")


if __name__ == "__main__":
    main()
