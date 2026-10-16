> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

> **Status: ✅ 已落地**(2026-08-20 归档标记。功能已上生产;下方 `- [ ]` 为执行期 TDD 脚手架,实施时未逐项回勾,不代表未完成。)

# Backend Platform Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create the first portable Java Spring Boot backend foundation for the AI test platform.

**Architecture:** Build `server/platform` as the Java Spring Boot modular monolith and system of record. This first slice exposes health and project APIs, creates the first database migration, and keeps later modules behind package boundaries without starting Python AI services or Go runners yet.

**Tech Stack:** Java 21, Spring Boot 3.5.x, Maven, Spring Web, Spring Validation, Spring Data JDBC, Flyway, MySQL runtime, H2 tests, JUnit 5.

---

### Task 1: Project Skeleton And Build Contract

**Files:**
- Create: `server/platform/pom.xml`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/AiTestPlatformApplication.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/AiTestPlatformApplicationTests.java`

- [ ] **Step 1: Write the failing test**

Create `server/platform/src/test/java/com/haitlify/aitest/platform/AiTestPlatformApplicationTests.java`:

```java
package com.haitlify.aitest.platform;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AiTestPlatformApplicationTests {

    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server/platform && ./mvnw test -Dtest=AiTestPlatformApplicationTests`

Expected: FAIL before the Maven project and application class exist.

- [ ] **Step 3: Write minimal implementation**

Create Maven project metadata, Maven wrapper, and the Spring Boot application class.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd server/platform && ./mvnw test -Dtest=AiTestPlatformApplicationTests`

Expected: PASS when Java 21 is installed.

### Task 2: Health API

**Files:**
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/system/SystemHealthController.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/system/SystemHealthControllerTest.java`

- [ ] **Step 1: Write the failing test**

Create a MockMvc test that calls `GET /api/system/health` and expects `status=UP`, `service=ai-test-platform`, and a non-empty version.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server/platform && ./mvnw test -Dtest=SystemHealthControllerTest`

Expected: FAIL because the endpoint does not exist.

- [ ] **Step 3: Write minimal implementation**

Add `SystemHealthController` returning the stable health payload.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd server/platform && ./mvnw test -Dtest=SystemHealthControllerTest`

Expected: PASS.

### Task 3: Project API Foundation

**Files:**
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/project/Project.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/project/ProjectRepository.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/project/ProjectService.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/project/ProjectController.java`
- Create: `server/platform/src/main/resources/db/migration/V1__platform_foundation.sql`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/project/ProjectControllerTest.java`

- [ ] **Step 1: Write the failing test**

Create a MockMvc test that creates a project through `POST /api/projects`, lists projects through `GET /api/projects`, and verifies project name, code, description, and active status.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server/platform && ./mvnw test -Dtest=ProjectControllerTest`

Expected: FAIL because the project API and migration do not exist.

- [ ] **Step 3: Write minimal implementation**

Add a Spring Data JDBC aggregate for projects, service validation, REST controller, and Flyway migration.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd server/platform && ./mvnw test -Dtest=ProjectControllerTest`

Expected: PASS.

### Task 4: Portable Runtime Configuration

**Files:**
- Create: `server/platform/src/main/resources/application.yml`
- Create: `server/platform/src/test/resources/application-test.yml`
- Create: `server/platform/.env.example`
- Create: `server/platform/Dockerfile`
- Create: `docker-compose.yml`

- [ ] **Step 1: Write configuration**

Add environment-driven database settings, a test profile using H2, a backend Dockerfile, and Docker Compose services for MySQL, Redis, and MinIO.

- [ ] **Step 2: Verify configuration shape**

Run: `cd server/platform && ./mvnw test`

Expected: PASS when Java 21 is installed.

### Task 5: Documentation Update

**Files:**
- Modify: `docs/ai-test-platform/decisions.md`
- Create: `server/platform/README.md`

- [ ] **Step 1: Update docs**

Record that `server/platform` now exists as the first Spring Boot backend foundation, and document local commands plus the current Java 21 requirement.

- [ ] **Step 2: Verify docs and git state**

Run: `git status --short server/platform docs/ai-test-platform/decisions.md docker-compose.yml docs/superpowers/plans/2026-06-15-backend-platform-foundation.md`

Expected: Only the backend foundation files and docs from this slice are listed.
