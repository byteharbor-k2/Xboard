#!/usr/bin/env sh
set -eu

BUILD_ROOT="${XBOARD_BUILD_ROOT:-/opt/xboard-build}"
IMAGE_REPO="${XBOARD_IMAGE_REPO:-xboard-custom}"
BRANCH="${XBOARD_BRANCH:-master}"

cd "$BUILD_ROOT"

git fetch origin "$BRANCH"
git checkout "$BRANCH"
git pull --ff-only origin "$BRANCH"

SHORT_SHA="$(git rev-parse --short HEAD)"
DATE_TAG="$(date -u +%Y%m%d)-$SHORT_SHA"
IMAGE="$IMAGE_REPO:$DATE_TAG"

docker build \
  --pull \
  --label "org.opencontainers.image.source=https://github.com/byteharbor-k2/Xboard" \
  --label "org.opencontainers.image.revision=$(git rev-parse HEAD)" \
  -t "$IMAGE" \
  -t "$IMAGE_REPO:latest" \
  .

printf '%s\n' "$IMAGE"
