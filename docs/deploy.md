# Deployment Guide

## Overview
- Trigger: `push` to `main` or manual `workflow_dispatch`
- Workflow file: `.github/workflows/deploy.yml`
- Dockerfile: `Dockerfile`
- Compose file: `docker-compose.prod.yml`
- Deploy script: `scripts/deploy.sh`
- Remote host: `ubuntu@api.qroad.info`
- Remote app dir: `/home/ubuntu/BE`

## Deployment Flow
1. GitHub Actions starts on `main` push.
2. `build_and_push` job builds Docker image and pushes:
   - `ghcr.io/<owner>/<repo>:<sha12>`
   - `ghcr.io/<owner>/<repo>:latest`
3. `deploy` job opens SSH ingress for the runner IP.
4. Workflow connects to server via SSH and syncs the same git branch.
5. `scripts/deploy.sh` runs on server:
   - login to GHCR
   - commit tag image pull first
   - if commit tag pull fails, fallback to `latest`
   - `docker compose up -d app`
   - health check (`/actuator/health` by default)
   - if health check fails, rollback to previous image
6. Runner IP is revoked from security group (`if: always()`).

## Safety Improvements
- `concurrency` is enabled in GitHub Actions to avoid overlapping deployments on the same ref.
- Security group ingress add/revoke handles duplicate or missing-rule scenarios safely.
- Server does not build artifacts anymore; image is built once in CI and pulled in deployment.

## Required Secrets
- `AWS_ACCESS_KEY_ID`
- `AWS_SECRET_ACCESS_KEY`
- `AWS_SECURITY_GROUP_ID`
- `SSH_PRIVATE_KEY`
- `GHCR_USERNAME`
- `GHCR_TOKEN` (PAT with `read:packages`)
- `DB_URL`
- `DB_USERNAME`
- `DB_PASSWORD`
- `OPENAI_API_KEY`
- `AWS_S3_BUCKET`
- `AWS_REGION`
- `AWS_S3_PRESIGN_EXP_MINUTES`
- `JWT_SECRET`

## Failure Handling
- If a step fails, workflow stops and reports failure in GitHub Actions.
- Security group revoke step is forced with `if: always()`.
- Check deployment logs:
  - GitHub Actions run logs
  - Server container logs: `docker compose -f docker-compose.prod.yml logs --tail 120 app`
- Rollback:
  - deploy script keeps previous running image URI
  - if new image health check fails, script attempts rollback automatically

## Server Prerequisites
- Docker installed
- Docker Compose V2 (`docker compose`)
- `ubuntu` user can run Docker commands
- Repository is cloned at `/home/ubuntu/BE`

## Manual Deployment (Server)
Run these commands on the server:

```bash
cd /home/ubuntu/BE
chmod +x scripts/deploy.sh
# export required env vars first (IMAGE_URI, LATEST_IMAGE_URI, REGISTRY, REGISTRY_USERNAME, REGISTRY_PASSWORD, app secrets)
./scripts/deploy.sh
```

Important:
- Manual deployment requires required environment variables to be exported in the shell before running.
- The workflow exports these automatically during CI/CD, but manual shell does not.

## Quick Verification
```bash
docker compose -f docker-compose.prod.yml ps
curl -fsS http://localhost:8080/actuator/health
ls -al /log/qroad-be
```

## Log Files
- Container log directory: `/log`
- Host log directory (volume): `/log/qroad-be`
- Daily files: `YYYY-MM-DD.log` (example: `2026-03-29.log`)
