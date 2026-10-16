# Haitlify Backend

`server/platform` is the Java Spring Boot main platform backend for Haitlify.

## Legacy Agent transition

The existing Agent implementation is deprecated but remains fully available until the
Hermes replacement reaches feature parity and passes acceptance testing. This rebrand
does not remove or disable Agent APIs, workflows, rules, or persistence.

The Java package root is `com.haitlify.aitest.platform`; Maven coordinates are
`com.haitlify.aitest:platform`. Existing database names and applied Flyway migrations
remain unchanged to preserve deployed data and migration checksums.

Jira examples use the reserved `example.test` domain. Configure `APP_JIRA_BASE_URL`
and `APP_JIRA_PROJECT_JQL_HAITLIFY_API` for your own deployment.

## Current scope

- Health endpoint at `GET /api/system/health`
- Project registry APIs at `GET /api/projects` and `POST /api/projects`
- Case template APIs at `GET /api/projects/{projectId}/case-template/interface` and `PUT /api/projects/{projectId}/case-template/interface`
- Interface case library APIs at `GET /api/projects/{projectId}/interface-cases`, `POST /api/projects/{projectId}/interface-cases`, and `POST /api/projects/{projectId}/interface-cases/delete`
- Asynchronous Web automation proxy at `POST /api/web-automation/runs`, `GET /api/web-automation/runs/{runId}`, pause/resume/stop control routes, and `GET /api/web-automation/capabilities`
- Flyway migrations for the platform foundation, template config, and interface case library tables
- Portable environment-driven configuration for local, test, and later Linux container runtime

## API behavior notes

- Interface template responses include `updatedAt` when a project-specific template has been saved.
- Built-in default templates return `updatedAt: null`.
- Interface case library items preserve each case `savedAt` timestamp for local-first sync and conflict detection.
- Case save requests upsert by `libraryId`; ordering is derived from `metadata.runOrder`.
- Web automation create, query, and control requests are proxied to the independent Playwright runner. Create returns `202` immediately; total execution duration is not tied to the Java HTTP request timeout. If the runner is disabled the backend returns `501`; transport and Runner errors preserve actionable status details.
- `loopCount` is forwarded to the runner so API automation mock/local execution and real Web execution share the same repeat-run behavior.

## Local requirements

- Java 21
- Docker Desktop or another Docker runtime for MySQL

## Local dependencies

The repository root `docker-compose.yml` provides shared local services:

- MySQL 8.4 on `127.0.0.1:3306`
- Redis 7.4 on `127.0.0.1:6379`
- MinIO on `127.0.0.1:9000` with console on `127.0.0.1:9001`
- Web runner on `127.0.0.1:19323` (host port; container internal `9323`)

Only MySQL is required by the current backend runtime. Redis and MinIO are staged for later platform modules.
The Web runner is required only when enabling real Web automation execution in V2.

## Commands

```bash
docker compose up -d mysql
docker compose up -d web-runner
chmod +x mvnw
./mvnw test
./mvnw spring-boot:run
```

The app starts on `http://127.0.0.1:18080` by default (non-common port to avoid host conflicts; the Docker container binds `8080` internally via `APP_PORT`).
For local frontend development, the backend accepts API CORS requests from `http://localhost:*` and `http://127.0.0.1:*`, and the Vite app proxies same-origin `/api` requests to this backend.

## Tests

Current controller coverage includes:

- system health
- project create/list
- case template get/save
- interface case library list/save/delete
- Web automation runner proxy enabled, disabled, and unavailable paths

## Environment

Copy values from `.env.example` into your local environment before starting the app.

```bash
export $(grep -v '^#' .env.example | xargs)
```

Default values:

- `APP_PORT=18080`
- `APP_VERSION=0.1.0-SNAPSHOT`
- `APP_DB_URL=jdbc:mysql://127.0.0.1:3306/ai_test_platform?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai`
- `APP_DB_USERNAME=root`
- `APP_DB_PASSWORD=root`
- `APP_WEB_AUTOMATION_RUNNER_ENABLED=false`
- `APP_WEB_AUTOMATION_RUNNER_BASE_URL=http://127.0.0.1:19323`
- `APP_WEB_AUTOMATION_RUNNER_TIMEOUT_MS=120000` (short Runner command transport timeout, not total run duration)
