# Xboard DevOps TODO

This document is the source of truth for the local development, image build, and production deployment workflow for `byteharbor-k2/Xboard`.

## Current Direction

We will use the image-based workflow:

```text
Local source code
  -> GitHub repository byteharbor-k2/Xboard
  -> GitHub Actions builds Docker image
  -> GHCR stores versioned image
  -> Evoxt production VPS pulls and runs that image
```

This follows the image-based deployment direction chosen for long-term Xboard customization.

The earlier "mount override" workflow is useful for quick experiments, but it becomes hard to reason about once custom changes spread across routes, controllers, services, themes, plugins, or payment logic. For long-term production, the container image should be the deployable unit.

## Core Concepts

### Git Repository

The GitHub repository contains source code and deployment templates. It is the history of what changed and why.

Local development happens in:

```text
/Users/howienew/Workspace/Xboard
```

Remote repository:

```text
git@github.com:byteharbor-k2/Xboard.git
```

### Docker Image

A Docker image is a packaged, immutable snapshot of the application code plus runtime dependencies. It should be reproducible from a specific Git commit.

Target image:

```text
ghcr.io/byteharbor-k2/xboard:<tag>
```

Do not treat `latest` as the only production version. Production should normally run a fixed tag such as:

```text
ghcr.io/byteharbor-k2/xboard:20260607-a1b2c3d
ghcr.io/byteharbor-k2/xboard:sha-a1b2c3d
```

### Container

A container is a running process created from an image. If the image is the application package, the container is the live instance.

Production should not build source code directly. It should pull an already-built image and run it.

### Data and Config

Data and config must live outside the image, otherwise upgrades and rollbacks become dangerous.

Production state belongs under:

```text
/opt/xboard-prod/
  compose.yaml
  .env
  data/
  storage/
  plugins/
  theme/
  nginx/
  backups/
```

Important production state:

```text
.env
data/database.sqlite
storage/
plugins/
theme/
redis-data volume
```

## Target Environments

### Local

Purpose: source code editing and functional testing.

Path:

```text
/Users/howienew/Workspace/Xboard
```

Local can use bind mounts for fast iteration, but those mounts are not the production deployment model.

### Staging

Purpose: test the production-style image before real users see it.

Recommended path on Evoxt:

```text
/opt/xboard-staging
```

Recommended domain later:

```text
staging.app.sinx.it.com
```

Staging should use a separate SQLite database and separate ports.

### Production

Purpose: real user traffic.

Recommended path on Evoxt:

```text
/opt/xboard-prod
```

Production domains:

```text
app.sinx.it.com
dashboard.app.sinx.it.com
```

## Implementation TODO

### 0. Temporary Local Build Path

- [x] Add `scripts/build-local.sh` for building the custom image directly on Evoxt.
- [x] Add `scripts/deploy-local.sh` for deploying a locally built image without GHCR.
- [ ] Use this path until the GitHub billing lock is resolved.

Why: GitHub Actions is currently blocked by an account-level billing lock, so the VPS will temporarily act as the build machine. The production runtime model stays the same: run a tagged image and keep data outside the image.

### 1. Normalize Docker Build

- [x] Review current `Dockerfile`.
- [x] Make the Docker image build from the checked-out GitHub Actions workspace, not by cloning a branch again inside the Dockerfile.
- [x] Keep `composer.lock` in the Docker build context for reproducible dependency installation.
- [x] Exclude `.git`, `.github`, `.env`, local caches, and local runtime data from the Docker build context.
- [x] Ensure Git submodules such as `public/assets/admin` are checked out by GitHub Actions.

Why: the image should correspond to one exact commit. Building by cloning a branch inside Docker makes the build depend on mutable branch state and network behavior.

### 2. Normalize GitHub Actions

- [x] Keep the workflow under `.github/workflows/docker-publish.yml`.
- [x] Build on push to `master`.
- [x] Keep manual `workflow_dispatch` for emergency rebuilds.
- [x] Push to:

```text
ghcr.io/byteharbor-k2/xboard
```

- [x] Generate tags:

```text
latest       only for master convenience
prod         current production candidate
sha-xxxxxxx  immutable commit tag
YYYYMMDD-xxxxxxx readable release tag
```

- [x] Build at least `linux/amd64` because the Evoxt VPS is x86_64.
- [x] Add `linux/arm64` only if multi-arch is needed.

Why: GitHub Actions becomes the controlled build machine. GHCR becomes the image registry. The VPS no longer needs source code builds.

### 3. Prepare Evoxt Production Directory

- [ ] Create `/opt/xboard-prod`.
- [x] Create `compose.yaml`.
- [ ] Create `.env`.
- [ ] Create `data/`.
- [ ] Create `storage/logs`.
- [ ] Create `storage/theme`.
- [ ] Create `plugins`.
- [ ] Create `theme`.
- [ ] Create `backups`.

Why: production needs a stable filesystem layout that separates application image from persistent state.

### 4. Prepare Production Compose

Production compose should reference a versioned image:

```yaml
services:
  xboard:
    image: ghcr.io/byteharbor-k2/xboard:CHANGE_ME
    restart: unless-stopped
    ports:
      - "127.0.0.1:7001:7001"
      - "8076:8076"
    volumes:
      - ./.env:/www/.env
      - ./data:/www/.docker/.data
      - ./storage/logs:/www/storage/logs
      - ./storage/theme:/www/storage/theme
      - ./plugins:/www/plugins
      - ./theme/Freedom:/www/theme/Freedom
      - ./theme/Freedom:/www/public/theme/Freedom
      - redis-data:/data
    environment:
      - ENABLE_CADDY=false
      - ENABLE_WEB=true
      - ENABLE_HORIZON=true
      - ENABLE_REDIS=true
      - ENABLE_WS_SERVER=true
      - docker=true

volumes:
  redis-data:
```

Why: Nginx should stay on the host and proxy to `127.0.0.1:7001`. Xboard application code comes from the image. Runtime data comes from mounts.

### 5. Add Backup Script

- [x] Add `scripts/backup.sh`.
- [x] Use SQLite `.backup` when possible.
- [x] Include `.env`, `data/`, `storage/`, `plugins/`, and `theme/`.
- [x] Keep at least 7-14 days of backups.

Why: every production deployment must be reversible.

### 6. Add Deploy Script

- [x] Add `scripts/deploy.sh`.
- [x] Require an explicit image tag.
- [x] Run backup before deploy.
- [x] Pull image.
- [x] Start container.
- [x] Run migrations if needed.
- [x] Clear Laravel caches if needed.
- [x] Print container status and recent logs.

Why: deployment should be repeatable and boring. Manual one-off commands are hard to audit and easy to forget.

### 7. Add Rollback Script

- [x] Add `scripts/rollback.sh`.
- [x] Store the previous image tag before each deploy.
- [x] Allow returning to the previous tag quickly.
- [x] Do not overwrite data automatically during rollback unless explicitly requested.

Why: image rollback and data rollback are different operations. Most bad deploys only need image rollback.

### 8. Migrate AWS Production to Evoxt

- [ ] Pre-sync `/opt/Xboard`.
- [ ] Pre-sync `/var/www/branding`.
- [ ] Pre-sync `/var/www/web`.
- [ ] Pre-sync Nginx config.
- [ ] Pre-sync SSL files.
- [ ] Prepare cloudflared service.
- [ ] Test Evoxt with forced host resolution.
- [ ] Stop AWS Xboard during final SQLite sync.
- [ ] Final sync SQLite database and WAL files.
- [ ] Start Evoxt Xboard.
- [ ] Move Cloudflare tunnel traffic to Evoxt.
- [ ] Keep AWS ready for rollback for at least 48 hours.

Why: current production uses SQLite. A final short write freeze is needed to avoid losing writes.

## Later TODO

### Payment Pricing Refactor

- [ ] Redesign fiat and crypto payment pricing.
- [ ] Make fees transparent.
- [ ] Avoid hidden differences between payment methods.

### Upstream Update Strategy

- [ ] Do not blindly merge upstream.
- [ ] Review upstream changes manually.
- [ ] Cherry-pick or port useful commits.
- [ ] Keep business-specific customizations controlled in this fork.

## Operating Rules

- Production does not build images.
- Production does not receive ad hoc `docker cp` application edits.
- Every production image must map to a Git commit.
- `.env` and user data must not enter the image.
- Back up before every production deploy.
- Use fixed image tags for production.
- Keep AWS production available until Evoxt has been stable for at least 48 hours.
