"""Stands in for javaw in tests: echoes its arguments, writes to stdout/stderr, exits with a chosen code."""
import os
import sys

print("fake java started with", len(sys.argv) - 1, "args", flush=True)
print("stderr line from game", file=sys.stderr, flush=True)
with open(os.environ["FAKE_JAVA_ARGS_FILE"], "w", encoding="utf-8") as f:
    f.write("\n".join(sys.argv[1:]))
code = int(os.environ.get("FAKE_JAVA_EXIT", "0"))
if code:
    os.makedirs("crash-reports", exist_ok=True)
    with open("crash-reports/crash-2026-01-01_00.00.00-client.txt", "w") as f:
        f.write("---- Minecraft Crash Report ----\n")
sys.exit(code)
