import io
import json
import ssl
import sys
import urllib.request
import zipfile

TOKEN = sys.argv[1]
REPO = "ly921002/xso"
job_ids = [int(x) for x in sys.argv[2:]]

ctx = ssl.create_default_context()
ctx.check_hostname = True
ctx.verify_mode = ssl.CERT_REQUIRED

HDRS = {"Authorization": "token " + TOKEN, "User-Agent": "dsh-agent"}

for jid in job_ids:
    url = "https://api.github.com/repos/%s/actions/jobs/%d/logs" % (REPO, jid)
    req = urllib.request.Request(url, headers=HDRS)
    try:
        raw = urllib.request.urlopen(req, context=ctx, timeout=180).read()
    except Exception as e:
        print("job %d: cannot fetch log: %r" % (jid, e))
        continue
    print("\n" + "=" * 78)
    print("JOB %d  (%d bytes)" % (jid, len(raw)))
    print("=" * 78)
    try:
        zf = zipfile.ZipFile(io.BytesIO(raw))
    except Exception:
        print("not a zip, raw head:")
        print(raw[:2000].decode("utf-8", "replace"))
        continue
    for name in zf.namelist():
        text = zf.read(name).decode("utf-8", "replace")
        # show only the tail where the error normally is
        lines = text.splitlines()
        keep = lines[-70:]
        print("--- %s (last %d lines) ---" % (name, len(keep)))
        for ln in keep:
            print(ln)
