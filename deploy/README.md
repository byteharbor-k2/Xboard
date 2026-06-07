# Production Deployment

This directory contains templates for running the custom Xboard image in production.

Production should pull an image from GHCR and keep runtime data outside the image.

Recommended production layout:

```text
/opt/xboard-prod/
  compose.yaml
  .env
  .deploy/
    image.env
    current_image
    previous_image
  data/
  storage/
  plugins/
  theme/
  scripts/
  backups/
```

Initial setup on the VPS:

```sh
mkdir -p /opt/xboard-prod
cp deploy/compose.prod.yaml /opt/xboard-prod/compose.yaml
cp -R deploy/scripts /opt/xboard-prod/scripts
chmod +x /opt/xboard-prod/scripts/*.sh
```

Deploy a specific image:

```sh
XBOARD_ROOT=/opt/xboard-prod /opt/xboard-prod/scripts/deploy.sh ghcr.io/byteharbor-k2/xboard:sha-xxxxxxx
```

Rollback to the previous image:

```sh
XBOARD_ROOT=/opt/xboard-prod /opt/xboard-prod/scripts/rollback.sh
```
