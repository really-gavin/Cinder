"""Starts the Cinder node inside the phone app."""
import node


def run(datadir, site, public_url, peers, port):
    argv = ["--datadir", datadir, "--site", site, "--host", "0.0.0.0", "--port", str(port)]
    if public_url:
        argv += ["--public-url", public_url]
    for p in (peers or "").split(","):
        if p.strip():
            argv += ["--peer", p.strip()]
    node.main(argv)
