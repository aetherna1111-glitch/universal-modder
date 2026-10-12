"""Stands in for PortableMC 5 in tests: prints machine output like `--output machine start --dry`."""
import os
import sys

args = sys.argv[1:]
mode = os.environ.get("FAKE_PMC_MODE", "ok")
with open(os.environ["FAKE_PMC_ARGS_FILE"], "w", encoding="utf-8") as f:
    f.write("\n".join(args))

print("load_version\tfabric-loader-0.16.10-1.20.1", flush=True)
print("download_resources", flush=True)
print("download\t1/4\t100/400\t0.1\t1000", flush=True)
print("download\t4/4\t400/400\t0.4\t1000", flush=True)
print("resources_downloaded", flush=True)
print("loaded_jvm\tC:\\Luminary\\jre\\bin\\javaw.exe\t17.0.9", flush=True)
if mode == "jvm_missing":
    print("error_jvm_not_found\tstatic", flush=True)
    sys.exit(1)
if mode == "slow":
    import time
    time.sleep(30)
marker = next(a.split("=", 1)[1] for a in args if a.startswith("--jvm-arg="))
uuid = args[args.index("--uuid") + 1]
name = args[args.index("--username") + 1]
cmd = ["-Djava.library.path=C:\\Users\\name\\new\\bin", marker, "-cp", "C:\\Luminary\\.minecraft\\libraries\\a.jar;b.jar",
       "net.fabricmc.loader.impl.launch.knot.KnotClient", "--username", name, "--uuid", uuid.replace("-", ""),
       "--accessToken", "", "--userType", "", "--version", "fabric-loader-0.16.10-1.20.1",
       "--quickPlayMultiplayer", "135.106.221.145:27195"]
print("jvm_args\t" + "\t".join(cmd), flush=True)
print("Arguments:", file=sys.stderr, flush=True)
sys.exit(0)
