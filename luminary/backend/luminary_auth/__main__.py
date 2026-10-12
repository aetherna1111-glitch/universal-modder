"""python -m luminary_auth [--host 127.0.0.1] [--port 8080]  - run the service with uvicorn."""
from __future__ import annotations

import argparse
import logging

import uvicorn


def main() -> None:
    ap = argparse.ArgumentParser(prog="luminary_auth")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8080)
    args = ap.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    # proxy_headers stays off: client IPs are taken from X-Forwarded-For only when LUMINARY_TRUST_PROXY=1.
    uvicorn.run("luminary_auth.app:create_app", factory=True, host=args.host, port=args.port,
                proxy_headers=False, server_header=False, log_level="info")


if __name__ == "__main__":
    main()
