#!/usr/bin/env sh
set -eu

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 xboard-custom:<tag>" >&2
  exit 1
fi

ROOT="${XBOARD_ROOT:-/opt/xboard-prod}"
IMAGE="$1"
DEPLOY_DIR="$ROOT/.deploy"

mkdir -p "$DEPLOY_DIR"
cd "$ROOT"

if [ -f "$DEPLOY_DIR/current_image" ]; then
  cp "$DEPLOY_DIR/current_image" "$DEPLOY_DIR/previous_image"
fi

"$ROOT/scripts/backup.sh" >/dev/null

printf 'XBOARD_IMAGE=%s\n' "$IMAGE" > "$DEPLOY_DIR/image.env"
printf '%s\n' "$IMAGE" > "$DEPLOY_DIR/current_image"

docker compose --env-file "$DEPLOY_DIR/image.env" -f compose.yaml up -d
docker compose --env-file "$DEPLOY_DIR/image.env" -f compose.yaml exec -T xboard php artisan migrate --force
docker compose --env-file "$DEPLOY_DIR/image.env" -f compose.yaml exec -T xboard php artisan optimize:clear
docker compose --env-file "$DEPLOY_DIR/image.env" -f compose.yaml ps
docker compose --env-file "$DEPLOY_DIR/image.env" -f compose.yaml logs --tail=80 xboard
