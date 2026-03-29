# Deployment Guide

## Overview
- Trigger: `push` to `main` or manual `workflow_dispatch`
- Workflow file: `.github/workflows/deploy.yml`
- Remote host: `ubuntu@api.qroad.info`
- Remote app dir: `/home/ubuntu/BE`
- Runtime log: `/var/log/be/be.log`

## Deployment Flow
1. GitHub Actions starts on `main` push.
2. AWS credentials are configured in the runner.
3. Runner public IP is added to EC2 security group port 22.
4. Workflow connects to the server with SSH private key.
5. `deploy.sh` runs on server:
   - fetch/pull latest `main`
   - stop old process (graceful stop, then force kill if needed)
   - build with Gradle (`clean build -x test`)
   - restart app with `nohup java -jar ...`
6. Runner IP is revoked from security group (`if: always()`).

## Required Secrets
- `AWS_ACCESS_KEY_ID`
- `AWS_SECRET_ACCESS_KEY`
- `AWS_SECURITY_GROUP_ID`
- `SSH_PRIVATE_KEY`
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
  - Server app log: `/var/log/be/be.log`

## Manual Deployment (Server)
Run these commands on the server:

```bash
cd /home/ubuntu/BE
chmod +x .github/workflows/deploy.sh
./.github/workflows/deploy.sh
```

Important:
- Manual deployment requires required environment variables to be exported in the shell before running.
- The workflow exports these automatically during CI/CD, but manual shell does not.

## Quick Verification
```bash
ps -ef | grep be-0.0.1-SNAPSHOT.jar | grep -v grep
tail -n 100 /var/log/be/be.log
```
