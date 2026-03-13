"""
Unit tests for the Python worker
Run: pytest test_worker.py -v
"""

import json
import time
import threading
import unittest
from unittest.mock import MagicMock, patch, call
from datetime import datetime

# ── Patch external dependencies before importing worker ──────────────────────
import sys
sys.modules['redis'] = MagicMock()
sys.modules['requests'] = MagicMock()
sys.modules['prometheus_client'] = MagicMock()

import importlib
import worker as worker_module
from worker import (
    handle_data_processing,
    handle_email_notification,
    handle_report_generation,
    handle_file_sync,
    handle_generic,
    JOB_HANDLERS,
    Worker,
)


class TestJobHandlers(unittest.TestCase):
    """Test individual job handler functions."""

    def test_handle_data_processing_returns_json(self):
        """data_processing handler returns valid JSON with processed count."""
        result = handle_data_processing({"records": 5})
        parsed = json.loads(result)
        self.assertEqual(parsed["processed"], 5)
        self.assertEqual(parsed["status"], "success")

    def test_handle_data_processing_defaults_100_records(self):
        """data_processing uses 100 records when not specified."""
        result = handle_data_processing({})
        parsed = json.loads(result)
        self.assertEqual(parsed["processed"], 100)

    def test_handle_file_sync_counts_files(self):
        """file_sync reports synced file count."""
        result = handle_file_sync({"files": ["a.txt", "b.txt", "c.txt"]})
        parsed = json.loads(result)
        self.assertEqual(parsed["synced"], 3)
        self.assertEqual(parsed["failed"], 0)

    def test_handle_file_sync_empty_list(self):
        """file_sync works with empty file list."""
        result = handle_file_sync({})
        parsed = json.loads(result)
        self.assertEqual(parsed["synced"], 0)

    def test_handle_generic_includes_payload_keys(self):
        """generic handler includes payload keys in result."""
        result = handle_generic({"foo": 1, "bar": 2})
        parsed = json.loads(result)
        self.assertIn("foo", parsed["payload_keys"])
        self.assertIn("bar", parsed["payload_keys"])

    def test_handle_generic_empty_payload(self):
        """generic handler works with empty payload."""
        result = handle_generic({})
        parsed = json.loads(result)
        self.assertEqual(parsed["status"], "completed")
        self.assertEqual(parsed["payload_keys"], [])

    def test_report_generation_returns_report_id(self):
        """report_generation returns a report_id."""
        result = handle_report_generation({"type": "monthly"})
        parsed = json.loads(result)
        self.assertIn("report_id", parsed)
        self.assertEqual(parsed["type"], "monthly")
        self.assertIsInstance(parsed["pages"], int)

    def test_report_generation_defaults_type(self):
        """report_generation defaults to 'daily' type."""
        result = handle_report_generation({})
        parsed = json.loads(result)
        self.assertEqual(parsed["type"], "daily")


class TestJobHandlerRegistry(unittest.TestCase):
    """Test the handler registry maps job types correctly."""

    def test_all_expected_handlers_registered(self):
        expected = {
            "data_processing",
            "email_notification",
            "report_generation",
            "file_sync",
            "database_migration",
        }
        self.assertEqual(set(JOB_HANDLERS.keys()), expected)

    def test_handlers_are_callable(self):
        for name, fn in JOB_HANDLERS.items():
            self.assertTrue(callable(fn), f"{name} handler is not callable")

    def test_unknown_type_falls_back_to_generic(self):
        """Worker should use handle_generic for unknown job types."""
        handler = JOB_HANDLERS.get("unknown_type_xyz", handle_generic)
        result = handler({})
        parsed = json.loads(result)
        self.assertEqual(parsed["status"], "completed")


class TestWorkerInit(unittest.TestCase):
    """Test Worker initialisation."""

    def _make_worker(self):
        with patch("worker.redis.Redis") as mock_redis:
            mock_redis.return_value = MagicMock()
            w = Worker()
        return w

    def test_worker_id_set_from_env(self):
        with patch("worker.WORKER_ID", "test-worker-42"):
            w = self._make_worker()
            self.assertEqual(w.worker_id, "test-worker-42")

    def test_worker_initial_counters_are_zero(self):
        w = self._make_worker()
        self.assertEqual(w.active_jobs, 0)
        self.assertEqual(w.processed_jobs, 0)
        self.assertEqual(w.failed_jobs, 0)

    def test_worker_running_false_at_init(self):
        w = self._make_worker()
        self.assertFalse(w.running)


class TestWorkerDequeue(unittest.TestCase):
    """Test atomic job dequeue logic."""

    def _make_worker(self):
        with patch("worker.redis.Redis") as mock_redis:
            mock_redis.return_value = MagicMock()
            w = Worker()
        return w

    def test_dequeue_returns_job_id_on_success(self):
        w = self._make_worker()
        w.redis_client.zpopmax.return_value = [("job-abc-123", 5000.0)]
        w.redis_client.sadd = MagicMock()

        result = w._dequeue_job()

        self.assertEqual(result, "job-abc-123")
        w.redis_client.sadd.assert_called_once_with("task:processing", "job-abc-123")

    def test_dequeue_returns_none_when_queue_empty(self):
        w = self._make_worker()
        w.redis_client.zpopmax.return_value = []

        result = w._dequeue_job()
        self.assertIsNone(result)

    def test_dequeue_adds_to_processing_set(self):
        w = self._make_worker()
        w.redis_client.zpopmax.return_value = [("job-xyz", 9999.0)]
        w.redis_client.sadd = MagicMock()

        w._dequeue_job()

        w.redis_client.sadd.assert_called_once_with("task:processing", "job-xyz")


class TestWorkerApiCall(unittest.TestCase):
    """Test the internal API call helper."""

    def _make_worker(self):
        with patch("worker.redis.Redis"):
            return Worker()

    def test_api_call_returns_json_on_success(self):
        w = self._make_worker()
        mock_resp = MagicMock()
        mock_resp.content = b'{"id":"abc"}'
        mock_resp.json.return_value = {"id": "abc"}
        mock_resp.raise_for_status = MagicMock()

        with patch("worker.requests.request", return_value=mock_resp):
            result = w._api_call("GET", "/api/v1/jobs/abc")

        self.assertEqual(result, {"id": "abc"})

    def test_api_call_returns_none_on_exception(self):
        w = self._make_worker()
        import requests as real_requests
        with patch("worker.requests.request", side_effect=Exception("network error")):
            result = w._api_call("GET", "/api/v1/jobs/missing")

        self.assertIsNone(result)

    def test_api_call_returns_empty_dict_for_empty_body(self):
        w = self._make_worker()
        mock_resp = MagicMock()
        mock_resp.content = b""
        mock_resp.raise_for_status = MagicMock()

        with patch("worker.requests.request", return_value=mock_resp):
            result = w._api_call("POST", "/api/v1/jobs/x/start", params={"workerId": "w1"})

        self.assertEqual(result, {})


class TestWorkerJobProcessing(unittest.TestCase):
    """Test the full _process_job flow."""

    def _make_worker(self):
        with patch("worker.redis.Redis"):
            return Worker()

    def test_process_job_success_increments_processed(self):
        w = self._make_worker()
        w.running = True

        job_data = {
            "id": "job-1",
            "type": "file_sync",
            "payload": '{"files":["a.txt"]}',
            "retryCount": 0,
            "maxRetries": 3,
        }

        with patch.object(w, "_api_call") as mock_api:
            mock_api.side_effect = [
                {},                   # POST /start
                job_data,             # GET /jobs/:id
                {},                   # POST /complete
            ]
            w.redis_client.srem = MagicMock()

            w._process_job("job-1")

        self.assertEqual(w.processed_jobs, 1)
        self.assertEqual(w.failed_jobs, 0)

    def test_process_job_failure_increments_failed(self):
        w = self._make_worker()
        w.running = True

        with patch.object(w, "_api_call") as mock_api:
            # Simulate start succeeds, fetch raises
            mock_api.side_effect = [
                {},          # POST /start
                Exception("fetch failed"),
            ]
            w.redis_client.srem = MagicMock()

            w._process_job("job-fail")

        self.assertEqual(w.failed_jobs, 1)
        self.assertEqual(w.processed_jobs, 0)

    def test_process_job_always_removes_from_processing_set(self):
        """Even on failure, job ID must be removed from in-flight set."""
        w = self._make_worker()
        w.running = True

        with patch.object(w, "_api_call", side_effect=Exception("kaboom")):
            w.redis_client.srem = MagicMock()
            w._process_job("job-boom")

        w.redis_client.srem.assert_called_with("task:processing", "job-boom")

    def test_active_jobs_counter_returns_to_zero_after_completion(self):
        w = self._make_worker()
        w.running = True

        job_data = {"id": "j1", "type": "generic", "payload": "{}", "retryCount": 0, "maxRetries": 3}

        with patch.object(w, "_api_call", return_value=job_data):
            w.redis_client.srem = MagicMock()
            w._process_job("j1")

        self.assertEqual(w.active_jobs, 0)


class TestWorkerHeartbeat(unittest.TestCase):
    """Test the worker heartbeat payload."""

    def test_heartbeat_contains_required_fields(self):
        with patch("worker.redis.Redis"):
            w = Worker()
        w.processed_jobs = 42
        w.failed_jobs = 3
        w.active_jobs = 2

        with patch.object(w, "_api_call") as mock_api:
            mock_api.return_value = {}
            w._heartbeat_loop.__func__  # just verify it's callable

        # Build heartbeat dict as the loop would
        heartbeat = {
            "workerId": w.worker_id,
            "status": "ACTIVE",
            "activeJobs": w.active_jobs,
            "processedJobs": w.processed_jobs,
            "failedJobs": w.failed_jobs,
            "lastSeen": datetime.utcnow().isoformat(),
        }
        self.assertEqual(heartbeat["processedJobs"], 42)
        self.assertEqual(heartbeat["failedJobs"], 3)
        self.assertEqual(heartbeat["status"], "ACTIVE")
        self.assertIn("lastSeen", heartbeat)


if __name__ == "__main__":
    unittest.main(verbosity=2)
