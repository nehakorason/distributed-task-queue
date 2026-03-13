# TaskFlow — Distributed Task Queue System

[![CI/CD](https://github.com/nehakorason/distributed-task-queue/actions/workflows/ci.yml/badge.svg)](https://github.com/nehakorason/distributed-task-queue/actions)
[![Java](https://img.shields.io/badge/Java-17-orange)](https://adoptium.net/)
[![Python](https://img.shields.io/badge/Python-3.11-blue)](https://python.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-brightgreen)](https://spring.io/projects/spring-boot)
[![Redis](https://img.shields.io/badge/Redis-7-red)](https://redis.io)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue)](https://postgresql.org)
[![License](https://img.shields.io/badge/License-MIT-lightgrey)](LICENSE)

> A production-grade distributed task queue built with Java, Python, Redis, and PostgreSQL — demonstrating the async job processing architecture used in enterprise platforms at companies like Apple IS&T and SAP.

---

## Overview

TaskFlow is a full-stack distributed system that handles job submission, priority-based scheduling, concurrent execution, automatic retry, and dead letter recovery — all observable in real time through a React dashboard and Prometheus/Grafana metrics.

**What happens when you submit a job:**

```
POST /api/v1/jobs
       │
       ▼
Spring Boot API validates + persists to PostgreSQL
       │
       ▼
Job enqueued in Redis Sorted Set (scored by priority + timestamp)
       │
       ▼
Python Worker atomically dequeues via ZPOPMAX
       │
       ├─ success  →  PATCH /jobs/{id}/complete
       └─ failure  →  retry (up to maxRetries) → DEAD_LETTER
```

---

## Tech Stack

| Component | Technology | Role |
|-----------|-----------|------|
| API | Java 17 + Spring Boot 3.2 | REST layer, job lifecycle, retry scheduler |
| Queue | Redis 7 Sorted Set | Priority queue, in-flight tracking, DLQ |
| Database | PostgreSQL 16 | Durable job state, analytics queries |
| Workers | Python 3.11 + ThreadPoolExecutor | Concurrent job execution |
| Dashboard | React 18 + Recharts | Real-time monitoring UI |
| Metrics | Prometheus + Grafana | Observability, alerting |
| CI/CD | GitHub Actions + GHCR | Test → lint → build → push images |

---

## Key Features

- **Priority queue** — CRITICAL / HIGH / NORMAL / LOW jobs dequeued by weighted score
- **Concurrent workers** — configurable thread pool per worker process; scale by adding containers
- **Atomic dequeue** — Redis `ZPOPMAX` guarantees no duplicate job execution across workers
- **Auto-retry** — failed jobs re-enqueued on a 30s scheduler cycle up to `maxRetries`
- **Dead letter queue** — exhausted jobs promoted to DLQ, recoverable via API
- **Scheduled jobs** — submit with `scheduledAt` for future execution
- **Full observability** — Prometheus metrics on API and every worker, pre-wired Grafana dashboards
- **Graceful shutdown** — SIGTERM handling in workers, connection pool teardown
- **Health checks** — Spring Actuator endpoints + Docker HEALTHCHECK on every service

---

## Quick Start

**Prerequisites:** Docker 24+ · Docker Compose v2

```bash
git clone https://github.com/nehakorason/distributed-task-queue.git
cd distributed-task-queue
docker compose up --build
```

| Service | URL | Login |
|---------|-----|-------|
| React Dashboard | http://localhost:3000 | — |
| Spring Boot API | http://localhost:8080 | — |
| API Health | http://localhost:8080/actuator/health | — |
| Prometheus | http://localhost:9090 | — |
| Grafana | http://localhost:3001 | admin / admin |

---

## API Reference

### Submit a Job
```bash
curl -X POST http://localhost:8080/api/v1/jobs \
  -H "Content-Type: application/json" \
  -d '{
    "type": "data_processing",
    "payload": "{\"records\": 1000}",
    "priority": "HIGH",
    "maxRetries": 3
  }'
```

**Job types:** `data_processing` · `email_notification` · `report_generation` · `file_sync` · `database_migration`

**Priorities:** `LOW` · `NORMAL` · `HIGH` · `CRITICAL`

### Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/jobs` | Submit a new job |
| `GET` | `/api/v1/jobs/{id}` | Get job by ID |
| `GET` | `/api/v1/jobs` | List jobs — filterable by status, type, page |
| `DELETE` | `/api/v1/jobs/{id}` | Cancel a pending/queued job |
| `POST` | `/api/v1/jobs/{id}/requeue` | Requeue a dead-letter job |
| `GET` | `/api/v1/jobs/stats` | Aggregate stats (counts, success rate, avg time) |
| `GET` | `/api/v1/jobs/workers` | Active worker heartbeats |

### Stats Response
```json
{
  "totalJobs": 1204,
  "runningJobs": 8,
  "completedJobs": 1150,
  "failedJobs": 34,
  "deadLetterJobs": 2,
  "successRate": 97.1,
  "avgCompletionTimeSeconds": 3.42
}
```

---

## Priority Queue Design

Jobs are stored in a Redis Sorted Set (`task:queue`). Score formula:

```
score = priority_weight × 1000 − (unix_timestamp / 1_000_000)
```

| Priority | Weight | Behaviour |
|----------|--------|-----------|
| CRITICAL | 20 | Always dequeued first |
| HIGH | 10 | Before NORMAL and LOW |
| NORMAL | 5 | Default |
| LOW | 1 | Background / best-effort |

Within the same priority level, jobs are processed FIFO.

---

## Retry & Dead Letter Flow

```
Job fails
 ├── retryCount < maxRetries
 │     └── status = FAILED
 │         JobSchedulerService re-enqueues every 30s
 │
 └── retryCount >= maxRetries
       └── status = DEAD_LETTER
           pushed to Redis task:dlq list
           recoverable via POST /api/v1/jobs/{id}/requeue
```

---

## Scaling

Workers are fully stateless. Scale horizontally with a single command:

```bash
docker compose up -d --scale worker-1=5
```

Each worker runs `CONCURRENCY` threads (default: 5), giving total throughput of `workers × threads` concurrent jobs. The atomic `ZPOPMAX` dequeue ensures no two workers ever process the same job.

---

## Local Development

### API
```bash
cd api
docker compose up -d postgres redis   # infrastructure only
mvn spring-boot:run
```

### Worker
```bash
cd python-worker
python -m venv venv && source venv/bin/activate
pip install -r requirements.txt
API_BASE_URL=http://localhost:8080 REDIS_HOST=localhost python worker.py
```

### Dashboard
```bash
cd dashboard
npm install --legacy-peer-deps
REACT_APP_API_URL=http://localhost:8080/api/v1 npm start
```

### Makefile shortcuts
```bash
make up          # Start full stack
make down        # Stop and clean up
make test        # Run all tests
make load-test   # Burst traffic simulation
make stats       # Live job stats
make health      # API health check
```

---

## Observability

### Prometheus Metrics

**API** (`/actuator/prometheus`):

| Metric | Description |
|--------|-------------|
| `jobs_submitted_total` | Total jobs submitted |
| `jobs_completed_total` | Total jobs completed |
| `jobs_failed_total` | Total failures |
| `http_server_requests_seconds` | Request latency histogram |
| `jvm_memory_used_bytes` | JVM heap usage |

**Workers** (`:9090/metrics`):

| Metric | Description |
|--------|-------------|
| `worker_jobs_processed_total{status}` | Jobs by outcome |
| `worker_jobs_active{worker_id}` | Currently executing |
| `worker_job_duration_seconds{job_type}` | Processing time histogram |

### Structured Log Format
```
[JOB_SUBMITTED]  id=abc123 type=data_processing priority=HIGH
[JOB_QUEUED]     id=abc123 score=-1763415.35
[PROCESSING]     id=abc123 worker=worker-1 thread=pool-1-thread-3
[COMPLETED]      id=abc123 duration=3.21s
[JOB_FAILED]     id=abc123 retries=1/3 error="connection timeout"
[JOB_DEAD_LETTER] id=abc123 retries=3/3
```

---

## Project Structure

```
distributed-task-queue/
├── api/                          # Spring Boot REST API
│   ├── src/main/java/com/taskqueue/
│   │   ├── controller/           # JobController — REST endpoints
│   │   ├── service/              # JobService, JobSchedulerService
│   │   ├── model/                # Job entity, JobStatus, JobPriority enums
│   │   ├── repository/           # Spring Data JPA
│   │   ├── dto/                  # SubmitRequest, JobResponse, StatsResponse
│   │   └── config/               # RedisConfig, GlobalExceptionHandler
│   ├── src/test/                 # Unit + controller tests (Mockito + MockMvc)
│   └── Dockerfile
├── python-worker/
│   ├── worker.py                 # Worker loop, handlers, Prometheus metrics
│   ├── test_worker.py            # pytest unit tests
│   └── Dockerfile
├── dashboard/
│   ├── src/App.jsx               # React dashboard — jobs, stats, workers tabs
│   └── Dockerfile
├── infra/
│   ├── init.sql                  # PostgreSQL schema + indexes + triggers
│   ├── prometheus.yml            # Scrape config
│   └── grafana/provisioning/     # Auto-provisioned datasource + dashboards
├── .github/workflows/ci.yml      # CI: test → lint → build → push to GHCR
├── docker-compose.yml
├── Makefile
└── load_test.py                  # Load testing script with rich output
```

---

## CI/CD Pipeline

Every push to `main` runs four jobs in parallel:

1. **API — Build & Test** — Maven compile + Mockito unit tests + MockMvc controller tests against real Postgres and Redis service containers
2. **Worker — Lint & Test** — flake8 lint + pytest unit tests
3. **Dashboard — Build** — `npm install` + `react-scripts build`
4. **Docker — Build & Push** — builds all three images and pushes to GitHub Container Registry

Images available at:
```
ghcr.io/nehakorason/distributed-task-queue-api:latest
ghcr.io/nehakorason/distributed-task-queue-worker:latest
ghcr.io/nehakorason/distributed-task-queue-dashboard:latest
```

---

## Why This Architecture

This system mirrors patterns found in production job processing infrastructure:

| Design Decision | Rationale |
|----------------|-----------|
| Redis Sorted Set for queue | O(log N) insert + O(1) atomic pop; built-in priority without additional infrastructure |
| Separate API and workers | Independent scaling; workers can be added without API restarts |
| PostgreSQL for job state | ACID guarantees on status transitions; queryable history |
| Heartbeat TTL in Redis | Worker failure detection without a separate health-check service |
| Prometheus on every process | Unified metrics across polyglot services (Java + Python) |

---

## License

MIT
