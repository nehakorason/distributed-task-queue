"""
Distributed Task Queue Worker
Connects to Redis, pulls jobs, executes them, reports status back to API
"""

import os
import sys
import time
import json
import signal
import logging
import uuid
import threading
import random
from datetime import datetime
from concurrent.futures import ThreadPoolExecutor
from typing import Optional, Dict, Any

import redis
import requests
from prometheus_client import Counter, Gauge, Histogram, start_http_server

# ─── Logging ──────────────────────────────────────────────────────────────────

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(threadName)s] %(levelname)-5s %(name)s - %(message)s",
    handlers=[logging.StreamHandler(sys.stdout)]
)
logger = logging.getLogger("worker")

# ─── Config ───────────────────────────────────────────────────────────────────

REDIS_HOST = os.getenv("REDIS_HOST", "localhost")
REDIS_PORT = int(os.getenv("REDIS_PORT", "6379"))
REDIS_PASSWORD = os.getenv("REDIS_PASSWORD", "") or None

API_BASE_URL = os.getenv("API_BASE_URL", "http://localhost:8080")
QUEUE_KEY = os.getenv("QUEUE_KEY", "task:queue")
PROCESSING_KEY = os.getenv("PROCESSING_KEY", "task:processing")

WORKER_ID = os.getenv("WORKER_ID", f"worker-{uuid.uuid4().hex[:8]}")
CONCURRENCY = int(os.getenv("WORKER_CONCURRENCY", "5"))
POLL_INTERVAL = float(os.getenv("POLL_INTERVAL", "1.0"))
HEARTBEAT_INTERVAL = int(os.getenv("HEARTBEAT_INTERVAL", "15"))
METRICS_PORT = int(os.getenv("METRICS_PORT", "9090"))

MAX_RETRY_DELAY = int(os.getenv("MAX_RETRY_DELAY", "60"))

# ─── Prometheus Metrics ───────────────────────────────────────────────────────

JOBS_PROCESSED = Counter("worker_jobs_processed_total", "Total jobs processed", ["status"])
JOBS_ACTIVE = Gauge("worker_jobs_active", "Currently active jobs", ["worker_id"])
JOB_DURATION = Histogram(
    "worker_job_duration_seconds",
    "Job processing duration",
    ["job_type"],
    buckets=[0.1, 0.5, 1, 2, 5, 10, 30, 60, 120]
)

# ─── Job Handlers ─────────────────────────────────────────────────────────────


def handle_data_processing(payload: Dict[str, Any]) -> str:
    """Simulate data processing workload."""
    records = payload.get("records", 100)
    logger.info(f"Processing {records} records...")
    time.sleep(random.uniform(0.5, 3.0))
    return json.dumps({"processed": records, "status": "success"})


def handle_email_notification(payload: Dict[str, Any]) -> str:
    """Simulate sending email notifications."""
    recipient = payload.get("to", "user@example.com")
    logger.info(f"Sending email to {recipient}...")
    time.sleep(random.uniform(0.1, 1.0))
    if random.random() < 0.05:  # 5% failure rate for demo
        raise RuntimeError(f"SMTP connection timeout for {recipient}")
    return json.dumps({"sent_to": recipient, "message_id": uuid.uuid4().hex})


def handle_report_generation(payload: Dict[str, Any]) -> str:
    """Simulate report generation."""
    report_type = payload.get("type", "daily")
    logger.info(f"Generating {report_type} report...")
    time.sleep(random.uniform(2.0, 8.0))
    return json.dumps({"report_id": uuid.uuid4().hex, "type": report_type, "pages": random.randint(5, 50)})


def handle_file_sync(payload: Dict[str, Any]) -> str:
    """Simulate file sync operation."""
    files = payload.get("files", [])
    logger.info(f"Syncing {len(files)} files...")
    time.sleep(random.uniform(1.0, 5.0))
    return json.dumps({"synced": len(files), "failed": 0})


def handle_database_migration(payload: Dict[str, Any]) -> str:
    """Simulate DB migration."""
    migration = payload.get("migration", "unknown")
    logger.info(f"Running migration: {migration}...")
    time.sleep(random.uniform(3.0, 10.0))
    if random.random() < 0.03:
        raise RuntimeError(f"Migration {migration} failed: constraint violation")
    return json.dumps({"migration": migration, "applied": True})


def handle_generic(payload: Dict[str, Any]) -> str:
    """Generic handler for unknown job types."""
    logger.info("Processing generic job...")
    time.sleep(random.uniform(0.2, 2.0))
    return json.dumps({"status": "completed", "payload_keys": list(payload.keys())})


JOB_HANDLERS = {
    "data_processing": handle_data_processing,
    "email_notification": handle_email_notification,
    "report_generation": handle_report_generation,
    "file_sync": handle_file_sync,
    "database_migration": handle_database_migration,
}

# ─── Worker ───────────────────────────────────────────────────────────────────


class Worker:
    def __init__(self):
        self.worker_id = WORKER_ID
        self.running = False
        self.active_jobs = 0
        self.processed_jobs = 0
        self.failed_jobs = 0
        self._lock = threading.Lock()

        self.redis_client = redis.Redis(
            host=REDIS_HOST,
            port=REDIS_PORT,
            password=REDIS_PASSWORD,
            decode_responses=True,
            socket_connect_timeout=5,
            socket_timeout=5,
        )
        self.executor = ThreadPoolExecutor(max_workers=CONCURRENCY, thread_name_prefix="job-worker")
        logger.info(f"Worker {self.worker_id} initialized | concurrency={CONCURRENCY}")

    def start(self):
        self.running = True
        logger.info(f"Worker {self.worker_id} starting...")

        # Start metrics server
        try:
            start_http_server(METRICS_PORT)
            logger.info(f"Prometheus metrics available on :{METRICS_PORT}")
        except Exception as e:
            logger.warning(f"Could not start metrics server: {e}")

        # Start heartbeat thread
        heartbeat_thread = threading.Thread(
            target=self._heartbeat_loop,
            name="heartbeat",
            daemon=True
        )
        heartbeat_thread.start()

        # Main polling loop
        self._poll_loop()

    def stop(self):
        logger.info(f"Worker {self.worker_id} shutting down gracefully...")
        self.running = False
        self.executor.shutdown(wait=True, cancel_futures=False)
        logger.info(f"Worker {self.worker_id} stopped. Processed: {self.processed_jobs}, Failed: {self.failed_jobs}")

    def _poll_loop(self):
        consecutive_empty = 0
        while self.running:
            try:
                if self.active_jobs >= CONCURRENCY:
                    time.sleep(POLL_INTERVAL)
                    continue

                job_id = self._dequeue_job()
                if job_id:
                    consecutive_empty = 0
                    self.executor.submit(self._process_job, job_id)
                else:
                    consecutive_empty += 1
                    # Backoff when queue is empty
                    sleep_time = min(POLL_INTERVAL * (1 + consecutive_empty * 0.1), 5.0)
                    time.sleep(sleep_time)

            except redis.RedisError as e:
                logger.error(f"Redis error in poll loop: {e}")
                time.sleep(5)
            except Exception as e:
                logger.error(f"Unexpected error in poll loop: {e}", exc_info=True)
                time.sleep(1)

    def _dequeue_job(self) -> Optional[str]:
        """Atomically pop highest-priority job from sorted set."""
        # zpopmax gives highest score = highest priority
        result = self.redis_client.zpopmax(QUEUE_KEY)
        if result:
            job_id, score = result[0]
            # Track as processing
            self.redis_client.sadd(PROCESSING_KEY, job_id)
            return job_id
        return None

    def _process_job(self, job_id: str):
        with self._lock:
            self.active_jobs += 1
        JOBS_ACTIVE.labels(worker_id=self.worker_id).inc()

        start_time = time.time()
        job_data = None

        try:
            # Notify API job started
            self._api_call("POST", f"/api/v1/jobs/{job_id}/start", params={"workerId": self.worker_id})

            # Fetch job details
            job_data = self._api_call("GET", f"/api/v1/jobs/{job_id}")
            if not job_data:
                logger.error(f"Job {job_id} not found in API")
                return

            job_type = job_data.get("type", "generic")
            payload_str = job_data.get("payload", "{}")

            try:
                payload = json.loads(payload_str) if payload_str else {}
            except json.JSONDecodeError:
                payload = {"raw": payload_str}

            logger.info(f"[PROCESSING] id={job_id} type={job_type} worker={self.worker_id}")

            # Route to handler
            handler = JOB_HANDLERS.get(job_type, handle_generic)

            with JOB_DURATION.labels(job_type=job_type).time():
                result = handler(payload)

            duration = time.time() - start_time
            logger.info(f"[COMPLETED] id={job_id} type={job_type} duration={duration:.2f}s")

            self._api_call("POST", f"/api/v1/jobs/{job_id}/complete", json={"result": result})
            JOBS_PROCESSED.labels(status="completed").inc()

            with self._lock:
                self.processed_jobs += 1

        except Exception as e:
            duration = time.time() - start_time
            error_msg = str(e)
            retry_count = job_data.get("retryCount", 0) if job_data else 0
            max_retries = job_data.get("maxRetries", 3) if job_data else 3

            logger.warning(f"[FAILED] id={job_id} error={error_msg} retry={retry_count}/{max_retries} duration={duration:.2f}s")

            self._api_call("POST", f"/api/v1/jobs/{job_id}/fail", json={"errorMessage": error_msg})
            JOBS_PROCESSED.labels(status="failed").inc()

            with self._lock:
                self.failed_jobs += 1

        finally:
            self.redis_client.srem(PROCESSING_KEY, job_id)
            with self._lock:
                self.active_jobs -= 1
            JOBS_ACTIVE.labels(worker_id=self.worker_id).dec()

    def _api_call(self, method: str, path: str, **kwargs) -> Optional[Dict]:
        url = f"{API_BASE_URL}{path}"
        try:
            resp = requests.request(method, url, timeout=10, **kwargs)
            resp.raise_for_status()
            if resp.content:
                return resp.json()
            return {}
        except requests.RequestException as e:
            logger.error(f"API call failed {method} {path}: {e}")
            return None

    def _heartbeat_loop(self):
        while self.running:
            try:
                heartbeat = {
                    "workerId": self.worker_id,
                    "status": "ACTIVE",
                    "activeJobs": self.active_jobs,
                    "processedJobs": self.processed_jobs,
                    "failedJobs": self.failed_jobs,
                    "lastSeen": datetime.utcnow().isoformat()
                }
                self._api_call("POST", "/api/v1/jobs/heartbeat", json=heartbeat)
                logger.debug(f"Heartbeat sent: active={self.active_jobs} processed={self.processed_jobs}")
            except Exception as e:
                logger.error(f"Heartbeat failed: {e}")
            time.sleep(HEARTBEAT_INTERVAL)


# ─── Entry Point ──────────────────────────────────────────────────────────────


def main():
    worker = Worker()

    def shutdown(signum, frame):
        logger.info(f"Received signal {signum}, shutting down...")
        worker.stop()
        sys.exit(0)

    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)

    try:
        worker.start()
    except KeyboardInterrupt:
        worker.stop()


if __name__ == "__main__":
    main()
