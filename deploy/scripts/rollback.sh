#!/usr/bin/env sh
set -eu

ROOT="${XBOARD_ROOT:-/opt/xboard-prod}"
DEPLOY_DIR="$ROOT/.deploy"

if [ ! -f "$DEPLOY_DIR/previous_image" ]; then
  echo "No previous image recorded." >&2
  exit 1
fi

IMAGE="$(cat "$DEPLOY_DIR/previous_image")"
cd "$ROOT"

printf 'XBOARD_IMAGE=%s\n' "$IMAGE" > "$DEPLOY_DIR/image.env"
printf '%s\n' "$IMAGE" > "$DEPLOY_DIR/current_image"

docker compose --env-file "$DEPLOY_DIR/image.env" -f compose.yaml pull xboard
docker compose --env-file "$DEPLOY_DIR/image.env" -f compose.yaml up -d
docker compose --env-file "$DEPLOY_DIR/image.env" -f compose.yaml ps
docker compose --env-file "$DEPLOY_DIR/image.env" -f compose.yaml logs --tail=80 xboard
