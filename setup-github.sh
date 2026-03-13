#!/usr/bin/env bash
# setup-github.sh
# Run this ONCE after cloning to initialize your GitHub repository
# Usage: ./setup-github.sh YOUR_GITHUB_USERNAME

set -e

GITHUB_USERNAME="${1:-YOUR_USERNAME}"
REPO_NAME="distributed-task-queue"

echo "╔════════════════════════════════════════════════╗"
echo "║  TaskFlow — GitHub Repository Setup            ║"
echo "╚════════════════════════════════════════════════╝"
echo ""

# Check git is installed
if ! command -v git &>/dev/null; then
  echo "✗ git not found. Install git first."
  exit 1
fi

# Check gh CLI (optional but nice)
if command -v gh &>/dev/null; then
  echo "▶ Creating GitHub repo via gh CLI..."
  gh repo create "$REPO_NAME" --public --description "Distributed Task Queue & Worker System — Spring Boot + Python + Redis + PostgreSQL + React" || true
fi

echo ""
echo "▶ Initializing git repository..."
git init
git add .
git commit -m "feat: initial commit — distributed task queue system

- Spring Boot REST API with job submit/cancel/query/requeue
- Redis sorted-set priority queue (CRITICAL > HIGH > NORMAL > LOW)
- Python workers with ThreadPoolExecutor and retry/backoff logic
- Dead letter queue for exhausted retries
- React dashboard with live job status and worker health
- Prometheus metrics on API + all workers
- GitHub Actions CI: test → lint → build → push GHCR images
- Docker Compose full-stack orchestration
- PostgreSQL with optimized indexes for job state"

echo ""
echo "▶ Setting remote origin..."
git remote add origin "https://github.com/${GITHUB_USERNAME}/${REPO_NAME}.git" 2>/dev/null || \
  git remote set-url origin "https://github.com/${GITHUB_USERNAME}/${REPO_NAME}.git"

echo ""
echo "▶ Pushing to GitHub..."
git branch -M main
git push -u origin main

echo ""
echo "╔════════════════════════════════════════════════════════╗"
echo "║  ✓ Done! Your repo is live:                            ║"
echo "║  https://github.com/${GITHUB_USERNAME}/${REPO_NAME}   ║"
echo "╚════════════════════════════════════════════════════════╝"
echo ""
echo "Next steps:"
echo "  1. docker compose up --build"
echo "  2. Open http://localhost:3000 (dashboard)"
echo "  3. Open http://localhost:8080/actuator/health (API)"
echo "  4. Open http://localhost:3001 (Grafana, admin/admin)"
