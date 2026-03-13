# Contributing

## Development Setup

```bash
git clone https://github.com/YOUR_USERNAME/distributed-task-queue
cd distributed-task-queue
cp .env.example .env
make dev-infra          # Start postgres + redis
make dev-api            # Terminal 1: Spring Boot API
make dev-worker         # Terminal 2: Python worker
make dev-dashboard      # Terminal 3: React dashboard
```

## Running Tests

```bash
make test               # All tests
make test-api-unit      # Fast unit tests only (no Docker required)
make test-api           # All API tests (uses Testcontainers)
make test-worker        # Python worker tests
```

## Code Style

**Java:** Follow standard Spring Boot conventions. Use Lombok for boilerplate. Structured log events must use the `[EVENT_NAME]` prefix pattern.

**Python:** PEP 8, max line length 120. Run `flake8 worker.py --max-line-length=120`.

**React:** Functional components only. No class components.

## Adding a New Job Type

1. **Register a handler** in `python-worker/worker.py`:
```python
def handle_my_new_type(payload: Dict[str, Any]) -> str:
    # your logic here
    return json.dumps({"result": "done"})

JOB_HANDLERS["my_new_type"] = handle_my_new_type
```

2. **No API changes required** — the API accepts any `type` string. Workers route by type.

3. **Add a test** in `python-worker/test_worker.py`:
```python
def test_handle_my_new_type_returns_json(self):
    result = handle_my_new_type({"key": "value"})
    parsed = json.loads(result)
    self.assertEqual(parsed["result"], "done")
```

## Branch Strategy

| Branch    | Purpose |
|-----------|---------|
| `main`    | Production-ready, protected |
| `develop` | Integration branch |
| `feat/*`  | Feature branches |
| `fix/*`   | Bug fixes |
| `chore/*` | Maintenance |

## Commit Message Convention

```
feat: add database_migration job type handler
fix: prevent race condition in worker dequeue
chore: upgrade Spring Boot to 3.2.1
test: add integration test for DLQ promotion
docs: update architecture diagram
```

## Pull Request Checklist

- [ ] Tests added / updated
- [ ] `make test` passes locally
- [ ] README updated if new feature
- [ ] No secrets or credentials committed
- [ ] Structured log events follow `[EVENT_NAME]` pattern
