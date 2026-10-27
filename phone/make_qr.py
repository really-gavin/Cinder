#!/usr/bin/env python3
"""Make a Cinder phone setup QR code.

A factory-reset Android phone (8 or newer) reads this code from its welcome
screen: tap the same spot 6 times, connect to Wi-Fi, scan. The phone then
downloads the Cinder app, lets it manage the phone, and starts.

    # public: turns a phone into a miner (rewards go to the card typed on the phone)
    python phone/make_qr.py miner

    # private: turns a phone into a node, joined to your Wi-Fi
    python phone/make_qr.py node --wifi "Home" --wifi-password "..." --public-url https://node.yourdomain.com

Keep node codes with a Wi-Fi password or tunnel token to yourself.
Needs: pip install segno
"""
import argparse
import json
import os

import segno

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
APK_URL = "https://raw.githubusercontent.com/really-gavin/Cinder/main/website/phone/cinder-phone.apk"
SITE = "https://really-gavin.github.io/Cinder/"


def setup_json(mode, apk_url, checksum, site, node=None, public_url=None, tunnel_token=None,
               peers=None, card=None, wifi=None, wifi_password=None, wifi_security="WPA"):
    extras = {"mode": mode, "site": site}
    for k, v in (("node", node), ("public_url", public_url), ("tunnel_token", tunnel_token),
                 ("peers", peers), ("card", card)):
        if v:
            extras[k] = v
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
    ap.add_argument("--out", help="file name without extension (default: setup-<mode>)")
    ap.add_argument("--apk-url", default=APK_URL)
    ap.add_argument("--checksum", help="defaults to website/phone/checksum.txt")
    ap.add_argument("--site", default=SITE, help="website whose config.js names the network's node")
    ap.add_argument("--node", help="node for a miner to use (otherwise it reads the website's config.js)")
    ap.add_argument("--card", help="card ID a miner pays rewards to (otherwise typed on the phone)")
    ap.add_argument("--public-url", help="node: its public https address")
    ap.add_argument("--tunnel-token", help="node: Cloudflare tunnel token (keep this code private)")
    ap.add_argument("--peers", help="node: other nodes, comma separated")
    ap.add_argument("--wifi", help="Wi-Fi name the phone joins during setup")
    ap.add_argument("--wifi-password")
    a = ap.parse_args()

    checksum = a.checksum or open(os.path.join(REPO, "website", "phone", "checksum.txt")).read().strip()
    d = setup_json(a.mode, a.apk_url, checksum, a.site, a.node, a.public_url, a.tunnel_token,
                   a.peers, a.card, a.wifi, a.wifi_password)
    out = a.out or f"setup-{a.mode}"
    text = json.dumps(d, separators=(",", ":"))
    with open(out + ".json", "w") as f:
        json.dump(d, f, indent=2)
    qr = segno.make(text, error="m")
    qr.save(out + ".png", scale=8, border=4, dark="#1f1e1b", light="#ffffff")
    qr.save(out + ".svg", scale=8, border=4, dark="#1f1e1b", light="#ffffff")
    print(f"wrote {out}.png, {out}.svg and {out}.json")
    if a.wifi_password or a.tunnel_token:
        print("this code contains a password or token: don't post it publicly")


if __name__ == "__main__":
    main()
