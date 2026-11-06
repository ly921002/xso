import sys, yaml, os

root = sys.argv[1]
ok = True
for f in ("build.yml", "bump-xray.yml"):
    p = os.path.join(root, ".github", "workflows", f)
    try:
        d = yaml.safe_load(open(p, encoding="utf-8"))
        jobs = list(d["jobs"].keys())
        print("OK  ", f, "jobs=", jobs)
        for name, job in d["jobs"].items():
            steps = job.get("steps", [])
            for s in steps:
                if not isinstance(s, dict):
                    print("  BAD STEP in", name, type(s))
                    ok = False
                elif "uses" not in s and "run" not in s:
                    print("  STEP WITHOUT uses/run in", name, list(s.keys()))
                    ok = False
    except Exception as e:
        print("FAIL", f, ":", e)
        ok = False
print("ALL OK" if ok else "PROBLEMS FOUND")
