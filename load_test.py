#!/usr/bin/env python3
"""
TaskFlow Load Test
==================
Submits batches of jobs concurrently and measures throughput, latency, and error rates.

Usage:
    pip install requests rich
    python load_test.py --url http://localhost:8080 --jobs 500 --concurrency 20 --duration 60

Options:
    --url         API base URL (default: http://localhost:8080)
    --jobs        Total jobs to submit (default: 200)
    --concurrency Concurrent submission threads (default: 10)
    --duration    Watch mode: monitor for N seconds after submission (default: 30)
    --mix         Job type mix: uniform | weighted (default: weighted)
"""

import argparse
import json
import random
import sys
import time
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from datetime import datetime
from typing import List, Dict

try:
    import requests
    from rich.console import Console
    from rich.table import Table
    from rich.progress import Progress, SpinnerColumn, BarColumn, TextColumn, TimeElapsedColumn
    from rich.live import Live
    from rich.panel import Panel
    from rich import box
except ImportError:
    print("Missing dependencies. Run: pip install requests rich")
    sys.exit(1)

console = Console()

# ── Job configurations ────────────────────────────────────────────────────────

JOB_TEMPLATES = [
    {"type": "data_processing",    "payload": {"records": 500},           "weight": 40},
    {"type": "email_notification", "payload": {"to": "test@example.com"}, "weight": 25},
    {"type": "report_generation",  "payload": {"type": "daily"},          "weight": 15},
    {"type": "file_sync",          "payload": {"files": ["f1.csv", "f2.csv", "f3.csv"]}, "weight": 15},
    {"type": "database_migration", "payload": {"migration": "V3__add_index"}, "weight": 5},
]

PRIORITIES = [
    ("LOW",      5),
    ("NORMAL",  50),
    ("HIGH",    35),
    ("CRITICAL", 10),
]


def weighted_choice(options):
    total = sum(w for _, w in options)
    r = random.uniform(0, total)
    upto = 0
    for item, w in options:
        upto += w
        if r <= upto:
            return item
    return options[-1][0]


# ── Result tracking ───────────────────────────────────────────────────────────

@dataclass
class LoadTestResult:
    submitted: int = 0
    submit_errors: int = 0
    latencies_ms: List[float] = field(default_factory=list)
    job_ids: List[str] = field(default_factory=list)
    status_counts: Dict[str, int] = field(default_factory=dict)
    start_time: float = field(default_factory=time.time)
    end_time: float = 0.0
    _lock: threading.Lock = field(default_factory=threading.Lock)

    def record_submit(self, job_id: str, latency_ms: float):
        with self._lock:
            self.submitted += 1
            self.latencies_ms.append(latency_ms)
            self.job_ids.append(job_id)

    def record_error(self):
        with self._lock:
            self.submit_errors += 1

    def record_status(self, status: str):
        with self._lock:
            self.status_counts[status] = self.status_counts.get(status, 0) + 1

    def p(self, percentile: float) -> float:
        if not self.latencies_ms:
            return 0.0
        sorted_lats = sorted(self.latencies_ms)
        idx = int(len(sorted_lats) * percentile / 100)
        return sorted_lats[min(idx, len(sorted_lats) - 1)]

    @property
    def total_duration(self) -> float:
        return (self.end_time or time.time()) - self.start_time

    @property
    def throughput(self) -> float:
        d = self.total_duration
        return self.submitted / d if d > 0 else 0.0


# ── Core functions ────────────────────────────────────────────────────────────

def submit_job(url: str, mix: str, result: LoadTestResult) -> None:
    if mix == "uniform":
        template = random.choice(JOB_TEMPLATES)
    else:
        weights = [(t, t["weight"]) for t in JOB_TEMPLATES]
        total = sum(w for _, w in weights)
        r = random.uniform(0, total)
        upto = 0
        template = JOB_TEMPLATES[-1]
        for t, w in weights:
            upto += w
            if r <= upto:
                template = t
                break

    priority = weighted_choice(PRIORITIES)
    max_retries = random.choice([2, 3, 3, 3, 5])

    payload = {
        "type": template["type"],
        "payload": json.dumps(template["payload"]),
        "priority": priority,
        "maxRetries": max_retries,
    }

    t0 = time.time()
    try:
        resp = requests.post(f"{url}/api/v1/jobs", json=payload, timeout=10)
        resp.raise_for_status()
        latency = (time.time() - t0) * 1000
        job = resp.json()
        result.record_submit(job["id"], latency)
    except Exception as e:
        result.record_error()


def poll_job_statuses(url: str, result: LoadTestResult, sample_size: int = 50) -> Dict[str, int]:
    sample = random.sample(result.job_ids, min(sample_size, len(result.job_ids)))
    counts = {}
    for job_id in sample:
        try:
            resp = requests.get(f"{url}/api/v1/jobs/{job_id}", timeout=5)
            if resp.ok:
                status = resp.json().get("status", "UNKNOWN")
                counts[status] = counts.get(status, 0) + 1
        except Exception:
            pass
    return counts


def fetch_stats(url: str) -> Dict:
    try:
        resp = requests.get(f"{url}/api/v1/jobs/stats", timeout=5)
        return resp.json() if resp.ok else {}
    except Exception:
        return {}


# ── Display helpers ───────────────────────────────────────────────────────────

STATUS_COLORS = {
    "PENDING":     "bright_black",
    "QUEUED":      "blue",
    "RUNNING":     "green",
    "COMPLETED":   "bright_green",
    "FAILED":      "red",
    "CANCELLED":   "magenta",
    "DEAD_LETTER": "yellow",
}


def render_summary(result: LoadTestResult, stats: Dict) -> Table:
    table = Table(box=box.SIMPLE_HEAVY, show_header=False, padding=(0, 2))
    table.add_column("Metric", style="bright_black", width=28)
    table.add_column("Value", style="bold white")

    avg = sum(result.latencies_ms) / len(result.latencies_ms) if result.latencies_ms else 0

    rows = [
        ("Jobs Submitted",        f"{result.submitted:,}"),
        ("Submit Errors",         f"[red]{result.submit_errors}[/red]" if result.submit_errors else "0"),
        ("Throughput",            f"{result.throughput:.1f} jobs/sec"),
        ("Total Duration",        f"{result.total_duration:.1f}s"),
        ("",                      ""),
        ("Latency avg",           f"{avg:.1f} ms"),
        ("Latency p50",           f"{result.p(50):.1f} ms"),
        ("Latency p95",           f"{result.p(95):.1f} ms"),
        ("Latency p99",           f"{result.p(99):.1f} ms"),
        ("",                      ""),
        ("API: Total Jobs",       f"{stats.get('totalJobs', '?'):,}" if stats else "?"),
        ("API: Running",          f"[green]{stats.get('runningJobs', '?')}[/green]" if stats else "?"),
        ("API: Completed",        f"[bright_green]{stats.get('completedJobs', '?'):,}[/bright_green]" if stats else "?"),
        ("API: Failed",           f"[red]{stats.get('failedJobs', '?')}[/red]" if stats else "?"),
        ("API: Dead Letter",      f"[yellow]{stats.get('deadLetterJobs', '?')}[/yellow]" if stats else "?"),
        ("API: Success Rate",     f"{stats.get('successRate', 0):.1f}%" if stats else "?"),
        ("API: Avg Duration",     f"{stats.get('avgCompletionTimeSeconds', 0):.2f}s" if stats else "?"),
    ]

    for metric, value in rows:
        table.add_row(metric, value)

    return table


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="TaskFlow Load Test")
    parser.add_argument("--url",         default="http://localhost:8080")
    parser.add_argument("--jobs",        type=int,   default=200)
    parser.add_argument("--concurrency", type=int,   default=10)
    parser.add_argument("--duration",    type=int,   default=30)
    parser.add_argument("--mix",         default="weighted", choices=["uniform", "weighted"])
    args = parser.parse_args()

    console.print(Panel.fit(
        f"[bold cyan]TaskFlow Load Test[/bold cyan]\n"
        f"Target:      [green]{args.url}[/green]\n"
        f"Jobs:        [yellow]{args.jobs:,}[/yellow]\n"
        f"Concurrency: [yellow]{args.concurrency}[/yellow]\n"
        f"Mix:         [yellow]{args.mix}[/yellow]",
        title="⚡ Load Test Config",
        border_style="cyan"
    ))

    # Verify API is reachable
    try:
        resp = requests.get(f"{args.url}/actuator/health", timeout=5)
        resp.raise_for_status()
        console.print(f"[green]✓ API healthy at {args.url}[/green]\n")
    except Exception as e:
        console.print(f"[red]✗ Cannot reach API at {args.url}: {e}[/red]")
        sys.exit(1)

    result = LoadTestResult()

    # ── Phase 1: Submit jobs ─────────────────────────────────────────────────
    console.print(f"[bold]Phase 1:[/bold] Submitting [yellow]{args.jobs:,}[/yellow] jobs...")

    with Progress(
        SpinnerColumn(),
        TextColumn("[progress.description]{task.description}"),
        BarColumn(),
        TextColumn("[progress.percentage]{task.percentage:>3.0f}%"),
        TextColumn("• {task.completed}/{task.total}"),
        TimeElapsedColumn(),
        console=console,
    ) as progress:
        task = progress.add_task("Submitting...", total=args.jobs)

        with ThreadPoolExecutor(max_workers=args.concurrency) as executor:
            futures = [
                executor.submit(submit_job, args.url, args.mix, result)
                for _ in range(args.jobs)
            ]
            for f in as_completed(futures):
                progress.advance(task)

    result.end_time = time.time()
    console.print(f"[green]✓ Submission complete[/green] — "
                  f"{result.submitted:,} submitted, {result.submit_errors} errors\n")

    # ── Phase 2: Monitor processing ──────────────────────────────────────────
    console.print(f"[bold]Phase 2:[/bold] Monitoring for [yellow]{args.duration}s[/yellow]...")

    deadline = time.time() + args.duration
    poll_interval = 3

    while time.time() < deadline:
        stats = fetch_stats(args.url)
        remaining = max(0, int(deadline - time.time()))

        console.print(
            f"  [{datetime.now().strftime('%H:%M:%S')}] "
            f"running=[green]{stats.get('runningJobs', '?')}[/green] "
            f"completed=[bright_green]{stats.get('completedJobs', '?'):,}[/bright_green] "
            f"failed=[red]{stats.get('failedJobs', '?')}[/red] "
            f"queued=[blue]{stats.get('queuedJobs', '?')}[/blue] "
            f"({remaining}s remaining)"
        )
        time.sleep(poll_interval)

    # ── Phase 3: Final report ─────────────────────────────────────────────────
    console.print()
    final_stats = fetch_stats(args.url)
    summary = render_summary(result, final_stats)

    console.print(Panel(
        summary,
        title="[bold cyan]📊 Load Test Results[/bold cyan]",
        border_style="cyan",
        expand=False
    ))

    # Success/failure indicator
    error_rate = result.submit_errors / max(1, args.jobs) * 100
    if error_rate > 5:
        console.print(f"\n[red]⚠ High error rate: {error_rate:.1f}%[/red]")
        sys.exit(1)
    else:
        console.print(f"\n[green]✓ Load test passed (error rate: {error_rate:.1f}%)[/green]")


if __name__ == "__main__":
    main()
