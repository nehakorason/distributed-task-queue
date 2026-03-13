.PHONY: help up down build test test-api test-worker logs clean load-test seed-jobs

# ── Config ─────────────────────────────────────────────────────────────────────
API_URL  ?= http://localhost:8080
JOBS     ?= 200
WORKERS  ?= 2

help: ## Show this help
	@awk 'BEGIN {FS = ":.*?## "} /^[a-zA-Z_-]+:.*?## / {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

# ── Docker Compose ─────────────────────────────────────────────────────────────
up: ## Start full stack (build if needed)
	docker compose up --build -d
	@echo "Waiting for API to be healthy..."
	@until curl -sf $(API_URL)/actuator/health > /dev/null; do sleep 2; done
	@echo "✓ Stack is up!"
	@echo "  Dashboard:  http://localhost:3000"
	@echo "  API:        http://localhost:8080"
	@echo "  Grafana:    http://localhost:3001  (admin/admin)"
	@echo "  Prometheus: http://localhost:9090"

down: ## Stop and remove containers
	docker compose down

clean: ## Stop containers and remove volumes (DESTRUCTIVE)
	docker compose down -v --remove-orphans

build: ## Rebuild all images without cache
	docker compose build --no-cache

logs: ## Tail logs for all services
	docker compose logs -f

logs-api: ## Tail API logs only
	docker compose logs -f api

logs-workers: ## Tail worker logs
	docker compose logs -f worker-1 worker-2

scale-workers: ## Scale workers (WORKERS=N make scale-workers)
	docker compose up -d --scale worker-1=$(WORKERS)

# ── Testing ────────────────────────────────────────────────────────────────────
test: test-api test-worker ## Run all tests

test-api: ## Run Spring Boot unit + integration tests
	cd api && mvn clean test -q
	@echo "✓ API tests passed"

test-api-unit: ## Run only unit tests (fast, no containers)
	cd api && mvn test -pl . -Dtest="JobServiceTest,JobControllerTest,JobModelTest" -q
	@echo "✓ Unit tests passed"

test-api-integration: ## Run integration tests (requires Docker for Testcontainers)
	cd api && mvn test -Dtest="JobIntegrationTest" -q
	@echo "✓ Integration tests passed"

test-worker: ## Run Python worker tests
	cd python-worker && pip install -q pytest && pytest test_worker.py -v
	@echo "✓ Worker tests passed"

test-worker-lint: ## Lint Python worker
	cd python-worker && flake8 worker.py --max-line-length=120 --ignore=E501,W503

# ── Load Testing ───────────────────────────────────────────────────────────────
load-test: ## Run load test (JOBS=N API_URL=... make load-test)
	pip install -q requests rich
	python load_test.py --url $(API_URL) --jobs $(JOBS) --concurrency 20 --duration 45

load-test-heavy: ## Heavy load test — 1000 jobs, 50 concurrent
	pip install -q requests rich
	python load_test.py --url $(API_URL) --jobs 1000 --concurrency 50 --duration 90

# ── Utilities ──────────────────────────────────────────────────────────────────
seed-jobs: ## Seed 20 sample jobs of each type
	@echo "Seeding jobs..."
	@for type in data_processing email_notification report_generation file_sync database_migration; do \
	  for priority in LOW NORMAL HIGH CRITICAL; do \
	    curl -sf -X POST $(API_URL)/api/v1/jobs \
	      -H "Content-Type: application/json" \
	      -d "{\"type\":\"$$type\",\"payload\":\"{}\",\"priority\":\"$$priority\",\"maxRetries\":3}" > /dev/null; \
	  done; \
	done
	@echo "✓ Seeded 20 jobs (5 types × 4 priorities)"

stats: ## Show current job stats
	@curl -sf $(API_URL)/api/v1/jobs/stats | python3 -m json.tool

health: ## Check API health
	@curl -sf $(API_URL)/actuator/health | python3 -m json.tool

workers: ## List active workers
	@curl -sf $(API_URL)/api/v1/jobs/workers | python3 -m json.tool

dashboard: ## Open dashboard in browser
	@open http://localhost:3000 2>/dev/null || xdg-open http://localhost:3000 2>/dev/null || echo "Open: http://localhost:3000"

# ── Local Dev ──────────────────────────────────────────────────────────────────
dev-infra: ## Start only postgres + redis (for local dev)
	docker compose up -d postgres redis
	@echo "✓ Postgres and Redis running"

dev-api: ## Run API locally (requires dev-infra)
	cd api && mvn spring-boot:run

dev-worker: ## Run worker locally (requires dev-infra + API)
	cd python-worker && pip install -r requirements.txt -q && python worker.py

dev-dashboard: ## Run dashboard locally
	cd dashboard && npm install --legacy-peer-deps && npm start
