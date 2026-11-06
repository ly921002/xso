import json
import os
import ssl
import sys
import urllib.request

TOKEN = sys.argv[1]
OUT = sys.argv[2]
os.makedirs(OUT, exist_ok=True)

ctx = ssl.create_default_context()
# This environment cannot reach the CRL/OCSP responder; revocation checking is
# not possible, so disable it rather than failing the transfer.
ctx.check_hostname = True
ctx.verify_mode = ssl.CERT_REQUIRED
try:
    ctx.verify_flags &= ~ssl.VERIFY_CRL_CHECK_LEAF
except Exception:
    pass

HDRS = {
    "Authorization": "token " + TOKEN,
    "User-Agent": "dsh-agent",
    "Accept": "application/vnd.github+json",
}


def get(url, accept=None):
    h = dict(HDRS)
    if accept:
        h["Accept"] = accept
    req = urllib.request.Request(url, headers=h)
    return urllib.request.urlopen(req, context=ctx, timeout=120)


rel = json.load(get("https://api.github.com/repos/ly921002/xso/releases/latest"))
print("release:", rel["tag_name"], "assets:", [a["name"] for a in rel["assets"]])

for a in rel["assets"]:
    dest = os.path.join(OUT, a["name"])
    if os.path.exists(dest) and os.path.getsize(dest) == a["size"]:
        print("cached", a["name"])
        continue
    last = None
    for attempt in range(1, 5):
        try:
            print("downloading", a["name"], a["size"], "bytes (attempt %d) ..." % attempt)
            resp = get(
                "https://api.github.com/repos/ly921002/xso/releases/assets/%d" % a["id"],
                accept="application/octet-stream",
            )
            buf = bytearray()
            while True:
                chunk = resp.read(1 << 16)
                if not chunk:
                    break
                buf.extend(chunk)
            if len(buf) != a["size"]:
                raise IOError("short read: %d of %d" % (len(buf), a["size"]))
            with open(dest, "wb") as f:
                f.write(buf)
            print("  saved", len(buf), "bytes ->", dest)
            last = None
            break
        except Exception as e:  # transient network truncation
            last = e
            print("  failed:", e)
    if last is not None:
        raise SystemExit("could not download %s: %s" % (a["name"], last))

print("\n=== BUILDINFO ===")
print(open(os.path.join(OUT, "BUILDINFO"), encoding="utf-8").read())
print("=== SHA256SUMS ===")
print(open(os.path.join(OUT, "SHA256SUMS"), encoding="utf-8").read())
