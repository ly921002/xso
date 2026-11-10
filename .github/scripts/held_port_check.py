"""Holds a curated port, then asserts the scanner stops reporting it free.

Run from the repository root in CI: python3 .github/scripts/held_port_check.py <jar> <port>
"""
import socket
import subprocess
import sys

jar, port = sys.argv[1], int(sys.argv[2])

sock = socket.socket()
sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
sock.bind(("", port))
sock.listen(1)
try:
    out = subprocess.run(
        ["java", "-cp", jar, "com.example.essentialsx.portprobe.PortScanner"],
        capture_output=True, text=True, timeout=180).stdout
finally:
    sock.close()

if f"free: {port}" in out:
    print(f"FAIL: {port} was held by this test but reported free", file=sys.stderr)
    sys.exit(1)
print(f"OK: {port} correctly excluded while held")
