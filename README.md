# TaskFlow — Distributed Task Queue & Worker System

[![CI/CD](https://github.com/YOUR_USERNAME/distributed-task-queue/actions/workflows/ci.yml/badge.svg)](https://github.com/YOUR_USERNAME/distributed-task-queue/actions)
[![Java](https://img.shields.io/badge/Java-17-orange)](https://adoptium.net/)
[![Python](https://img.shields.io/badge/Python-3.11-blue)](https://python.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-brightgreen)](https://spring.io/projects/spring-boot)

A production-grade distributed task queue and worker system demonstrating async job processing at scale — the same architectural pattern powering Apple IS&T internal tooling, SAP workflow engines, and enterprise automation platforms.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         CLIENT / DASHBOARD                           │
│                    React SPA (port 3000)                             │
│              Job submission · Live status · Worker health            │
└────────────────────────────┬────────────────────────────────────────┘
                             │ HTTP REST
                             ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      SPRING BOOT API (port 8080)                     │
│                                                                      │
│   POST /api/v1/jobs     ─── Submit job                               │
│   GET  /api/v1/jobs/:id ─── Query status                            │
│   DELETE /api/v1/jobs/:id── Cancel job                              │
│   POST /api/v1/jobs/:id/requeue ── Requeue failed job               │
│   GET  /api/v1/jobs/stats ── Aggregate metrics                      │
│   GET  /actuator/prometheus── Prometheus metrics                    │
│                                                                      │
│   JobService ─ JobSchedulerService ─ Prometheus Metrics              │
└──────────┬──────────────────────────┬──────────────────────────────┘
           │ ZADD (priority score)     │ R/W job state
           ▼                           ▼
┌──────────────────┐       ┌─────────────────────────┐
│  REDIS (port 6379)│       │  POSTGRESQL (port 5432)  │
│                  │       │                          │
│  task:queue      │       │  jobs table              │
│  (sorted set)    │       │  ├─ id, type, payload    │
│                  │       │  ├─ status, priority      │
│  task:dlq        │       │  ├─ retry_count           │
│  (dead letter)   │       │  ├─ worker_id             │
│                  │       │  ├─ result, error         │
│  task:processing │       │  └─ timestamps            │
│  (in-flight set) │       │                          │
│                  │       └─────────────────────────┘
│  worker:heartbeat│
│  (per-worker TTL)│
└──────────┬───────┘
           │ ZPOPMAX (atomic dequeue)
           ▼
┌─────────────────────────────────────────────────────────────────────┐
│                   PYTHON WORKERS (worker-1, worker-2)                │
│                                                                      │
│  ThreadPoolExecutor(max_workers=CONCURRENCY)                         │
│                                                                      │
│  Poll loop → ZPOPMAX → Fetch job details → Execute handler           │
│          → PATCH /jobs/:id/start                                     │
│          → handler(payload)  ─── success → PATCH /complete          │
│                              ─── failure → PATCH /fail               │
│                                         ── retry < max → re-enqueue  │
│                                         ── retry >= max → DLQ        │
│                                                                      │
│  Handlers: data_processing · email_notification · report_generation  │
│            file_sync · database_migration                            │
│                                                                      │
│  Heartbeat thread → POST /api/v1/jobs/heartbeat (every 15s)         │
│  Prometheus metrics → :9090/metrics                                  │
└─────────────────────────────────────────────────────────────────────┘

┌────────────────────────┐    ┌─────────────────────────────────────┐
│  PROMETHEUS (port 9090) │───▶│  GRAFANA (port 3001)                 │
│  Scrapes API + workers  │    │  Pre-provisioned dashboards          │
└────────────────────────┘    └─────────────────────────────────────┘
```

### Priority Queue Design

Jobs are stored in a Redis Sorted Set (`ZADD task:queue`). The score formula:

```
score = priority_weight × 1000 − (unix_timestamp / 1_000_000)
```

| Priority | Weight | Behaviour |
|----------|--------|-----------|
| CRITICAL | 20     | Always dequeued first |
| HIGH     | 10     | Before normal/low |
| NORMAL   | 5      | Default |
| LOW      | 1      | Background work |

Within the same priority, older jobs are dequeued first (FIFO).

### Retry / Dead Letter Flow

```
FAILED (retryCount < maxRetries)
  └─► JobSchedulerService.retryFailedJobs() (every 30s)
        └─► re-enqueue with exponential backoff
              └─► Worker re-processes

FAILED (retryCount >= maxRetries)
  └─► Status → DEAD_LETTER
        └─► Job ID pushed to Redis task:dlq list
              └─► Manual requeue via POST /jobs/:id/requeue
```

---

## Tech Stack

| Component | Technology | Purpose |
|-----------|-----------|---------|
| API | Java 17 + Spring Boot 3.2 | REST layer, job state management, scheduling |
| Queue | Redis 7 (Sorted Set) | Priority queue, in-flight tracking, worker heartbeats |
| Database | PostgreSQL 16 | Durable job state, queries, analytics |
| Workers | Python 3.11 | Job execution, concurrent processing |
| Dashboard | React 18 + Recharts | Real-time job monitoring UI |
| Metrics | Prometheus + Grafana | Observability stack |
| Container | Docker Compose | Local orchestration |
| CI/CD | GitHub Actions | Build, test, push images |

---

## Quick Start

### Prerequisites
- Docker 24+ and Docker Compose v2
- Git

### 1. Clone and start
```bash
git clone https://github.com/YOUR_USERNAME/distributed-task-queue.git
cd distributed-task-queue

# Start full stack
docker compose up --build

# Or start in background
docker compose up -d --build
```

### 2. Access services

| Service | URL | Credentials |
|---------|-----|-------------|
| Dashboard | http://localhost:3000 | — |
| API | http://localhost:8080 | — |
| API Health | http://localhost:8080/actuator/health | — |
| Prometheus | http://localhost:9090 | — |
| Grafana | http://localhost:3001 | admin / admin |

### 3. Submit your first job
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

---

## API Reference

### Submit a Job
```
POST /api/v1/jobs
```
```json
{
  "type": "data_processing",
  "payload": "{\"records\": 500}",
  "priority": "NORMAL",
  "maxRetries": 3,
  "scheduledAt": null
}
```

**Job types:** `data_processing` · `email_notification` · `report_generation` · `file_sync` · `database_migration`

**Priorities:** `LOW` · `NORMAL` · `HIGH` · `CRITICAL`

### Get Job Status
```
GET /api/v1/jobs/{jobId}
```

### List Jobs (paginated)
```
GET /api/v1/jobs?status=RUNNING&type=data_processing&page=0&size=20&sortBy=createdAt&sortDir=DESC
```

### Cancel a Job
```
DELETE /api/v1/jobs/{jobId}
```

### Requeue Failed Job
```
POST /api/v1/jobs/{jobId}/requeue
```

### Statistics
```
GET /api/v1/jobs/stats
```
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

### Active Workers
```
GET /api/v1/jobs/workers
```

---

## Scaling Workers

Scale horizontally by adding more worker containers:

```bash
docker compose up -d --scale worker-1=5
```

Or add named workers in `docker-compose.yml` with unique `WORKER_ID` values.

Each worker:
- Runs `CONCURRENCY` threads (default: 5) — total throughput = workers × concurrency
- Atomically dequeues via Redis `ZPOPMAX` (no duplicate processing)
- Reports heartbeat every 15s (visible in Dashboard → Workers)

---

## Local Development (without Docker)

### API
```bash
cd api
# Start postgres and redis separately (or via docker compose up postgres redis)
export DB_HOST=localhost REDIS_HOST=localhost
mvn spring-boot:run
```

### Worker
```bash
cd python-worker
python -m venv venv && source venv/bin/activate
pip install -r requirements.txt
export API_BASE_URL=http://localhost:8080 REDIS_HOST=localhost
python worker.py
```

### Dashboard
```bash
cd dashboard
npm install --legacy-peer-deps
REACT_APP_API_URL=http://localhost:8080/api/v1 npm start
```

---

## Observability

### Prometheus Metrics

The API exposes metrics at `/actuator/prometheus`:

| Metric | Description |
|--------|-------------|
| `jobs_submitted_total` | Total jobs submitted |
| `jobs_completed_total` | Total jobs completed successfully |
| `jobs_failed_total` | Total job failures |
| `jvm_memory_used_bytes` | JVM heap usage |
| `http_server_requests_seconds` | API request latency |

Workers expose at `:9090/metrics`:

| Metric | Description |
|--------|-------------|
| `worker_jobs_processed_total{status}` | Jobs by outcome |
| `worker_jobs_active{worker_id}` | Currently executing |
| `worker_job_duration_seconds{job_type}` | Processing time histogram |

### Structured Logging Format
```
2024-01-15 14:23:01 [main] INFO  JobService - [JOB_SUBMITTED] id=abc123 type=data_processing priority=HIGH
2024-01-15 14:23:03 [job-worker-1] INFO  worker - [PROCESSING] id=abc123 type=data_processing worker=worker-1
2024-01-15 14:23:06 [job-worker-1] INFO  worker - [COMPLETED] id=abc123 type=data_processing duration=3.21s
```

---

## Project Structure

```
distributed-task-queue/
├── api/                          # Spring Boot REST API
│   ├── src/main/java/com/taskqueue/
│   │   ├── controller/           # REST controllers
│   │   ├── service/              # Business logic
│   │   ├── model/                # JPA entities
│   │   ├── repository/           # Spring Data JPA
│   │   ├── dto/                  # Request/Response DTOs
│   │   └── config/               # Redis, exception handling
│   ├── src/main/resources/
│   │   └── application.yml       # Configuration
│   └── Dockerfile
│
├── python-worker/                # Python async workers
│   ├── worker.py                 # Worker with handlers + metrics
│   ├── requirements.txt
│   └── Dockerfile
│
├── dashboard/                    # React monitoring UI
│   ├── src/
│   │   ├── App.jsx               # Main dashboard component
│   │   └── index.js
│   ├── public/index.html
│   ├── nginx.conf                # Reverse proxy config
│   └── Dockerfile
│
├── infra/                        # Infrastructure configs
│   ├── init.sql                  # PostgreSQL schema
│   ├── prometheus.yml            # Metrics scrape config
│   └── grafana/provisioning/     # Auto-provisioned dashboards
│
├── .github/
│   └── workflows/
│       └── ci.yml                # GitHub Actions CI/CD
│
├── docker-compose.yml            # Full stack orchestration
├── .env.example
└── README.md
```

---

## Why This Demonstrates MNC-Readiness

| Pattern | Implementation |
|---------|---------------|
| **Async distributed processing** | Redis sorted-set queue + multi-process Python workers |
| **Worker concurrency** | ThreadPoolExecutor with configurable pool size |
| **Priority scheduling** | ZADD score-based dequeue (`ZPOPMAX`) |
| **Failure handling** | Retry counter + exponential backoff scheduler |
| **Dead letter queue** | Automatic promotion after max retries |
| **Observability** | Prometheus metrics on API + each worker |
| **Health endpoints** | Spring Actuator + Docker HEALTHCHECK |
| **Structured logging** | Tagged log events `[JOB_SUBMITTED]`, `[JOB_FAILED]` |
| **Horizontal scaling** | Stateless workers — add containers to scale |
| **Production config** | Connection pools, graceful shutdown, SIGTERM handling |
| **CI/CD** | GitHub Actions: test → lint → build → push images |

---

## License

MIT
#   C I   f i x   a t t e m p t  
 