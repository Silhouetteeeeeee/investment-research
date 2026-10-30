#!/usr/bin/env bash
# Consistent SQLite snapshot with a rolling 7-day retention.
set -euo pipefail
# Backups contain conversations; keep them unreadable to other users.
umask 077

DATA_DIR="${DATA_DIR:-./var}"
DB="$DATA_DIR/app.db"
BACKUP_DIR="$DATA_DIR/backups"
KEEP=7

if [ ! -f "$DB" ]; then
  echo "backup: no database at $DB" >&2
  exit 1
fi

mkdir -p "$BACKUP_DIR"
target="$BACKUP_DIR/app-$(date +%Y%m%d).db"
# Write to .tmp and rename, so a killed run never leaves a truncated backup under
# the final name; .timeout keeps a live writer from failing the copy outright.
tmp="$target.tmp"
sqlite3 -cmd ".timeout 5000" "$DB" ".backup '$tmp'"
mv -f -- "$tmp" "$target"

# Names sort by day (app-YYYYMMDD.db); drop everything past the newest KEEP files.
# -print0/-z keep a DATA_DIR containing spaces from being word-split into a wrong rm.
find "$BACKUP_DIR" -maxdepth 1 -name 'app-*.db' -print0 \
  | sort -zr | tail -z -n +$((KEEP + 1)) | xargs -r0 rm -f --
echo "backup: $target"
