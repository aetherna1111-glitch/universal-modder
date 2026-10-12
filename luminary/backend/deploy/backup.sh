#!/bin/sh
# Nightly backup: consistent SQLite copy + textures (safe while the service runs).
# cron:  15 4 * * *  luminary  /opt/luminary-auth/deploy/backup.sh
# Copy the result off the machine. The signing key (signing_key.pem) is NOT in it: back it up once,
# separately and encrypted - losing it means every client must trust a new key (see DEPLOYMENT.md).
set -eu
. /etc/luminary-auth.env
export LUMINARY_DATA_DIR LUMINARY_PUBLIC_URL
DEST=${1:-/var/backups/luminary-auth}
/opt/luminary-auth/venv/bin/python -m luminary_auth.manage backup "$DEST"
# keep 14 days
find "$DEST" -maxdepth 1 -type d -name 'luminary-*' -mtime +14 -exec rm -rf {} +
