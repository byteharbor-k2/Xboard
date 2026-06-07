#!/usr/bin/env sh
set -eu

ROOT="${XBOARD_ROOT:-/opt/xboard-prod}"
BACKUP_ROOT="${BACKUP_ROOT:-$ROOT/backups}"
TS="$(date -u +%Y%m%d-%H%M%S)"
DEST="$BACKUP_ROOT/$TS"

mkdir -p "$DEST"
cd "$ROOT"

if [ -f ".env" ]; then
  cp .env "$DEST/env"
fi

if [ -f "data/database.sqlite" ]; then
  mkdir -p "$DEST/data"
  if command -v sqlite3 >/dev/null 2>&1; then
    sqlite3 data/database.sqlite ".backup '$DEST/data/database.sqlite'"
  else
    cp data/database.sqlite "$DEST/data/database.sqlite"
    [ -f "data/database.sqlite-wal" ] && cp data/database.sqlite-wal "$DEST/data/database.sqlite-wal"
    [ -f "data/database.sqlite-shm" ] && cp data/database.sqlite-shm "$DEST/data/database.sqlite-shm"
  fi
fi

for path in storage plugins theme; do
  if [ -e "$path" ]; then
    tar -czf "$DEST/$path.tgz" "$path"
  fi
done

find "$BACKUP_ROOT" -mindepth 1 -maxdepth 1 -type d | sort | head -n -14 | xargs -r rm -rf

echo "$DEST"
