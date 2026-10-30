#!/usr/bin/env bash
# Consistent SQLite snapshot with a rolling 7-day retention.
set -euo pipefail

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
# .backup overwrites the target, so a same-day rerun refreshes it instead of duplicating.
sqlite3 "$DB" ".backup '$target'"

# Names sort by day (app-YYYYMMDD.db); drop everything past the newest KEEP files.
ls -1 "$BACKUP_DIR"/app-*.db | sort -r | tail -n +$((KEEP + 1)) | xargs -r rm -f --
echo "backup: $target"
