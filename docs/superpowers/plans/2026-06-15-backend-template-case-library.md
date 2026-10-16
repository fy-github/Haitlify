> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

> **Status: ✅ 已落地**(2026-08-20 归档标记。功能已上生产;下方 `- [ ]` 为执行期 TDD 脚手架,实施时未逐项回勾,不代表未完成。)

# Backend Template And Case Library Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist project-level interface case templates and saved interface cases in the Java backend.

**Architecture:** Keep this as a narrow Spring Boot modular-monolith slice. Template APIs preserve the frontend `interfaceFields + caseTypes` shape, while case-library APIs preserve `row + metadata + libraryId/savedAt` so the frontend can later switch from localStorage to backend APIs with minimal mapping.

**Tech Stack:** Java 21, Spring Boot 3.5.x, Spring Web, Spring Data JDBC, Flyway, H2 tests.

---

### Task 1: Template Config API

**Files:**
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/template/CaseTemplateControllerTest.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/template/CaseTemplateController.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/template/CaseTemplateService.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/template/CaseTemplateRepository.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/template/CaseTemplateConfig.java`

- [ ] **Step 1: Write the failing test**

Create a MockMvc test that:
- creates a project,
- calls `GET /api/projects/{projectId}/case-template/interface` and receives default fields and case types,
- calls `PUT /api/projects/{projectId}/case-template/interface` with a renamed field,
- calls `GET` again and receives the saved override.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server/platform && ./mvnw test -Dtest=CaseTemplateControllerTest`

Expected: FAIL because the endpoint does not exist.

- [ ] **Step 3: Write minimal implementation**

Add the template aggregate, repository, service, controller, and default interface template data.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd server/platform && ./mvnw test -Dtest=CaseTemplateControllerTest`

Expected: PASS.

### Task 2: Case Library API

**Files:**
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/caselibrary/CaseLibraryControllerTest.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/caselibrary/InterfaceCaseRecord.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/caselibrary/InterfaceCaseRepository.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/caselibrary/CaseLibraryService.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/caselibrary/CaseLibraryController.java`

- [ ] **Step 1: Write the failing test**

Create a MockMvc test that:
- creates a project,
- saves one generated interface case through `POST /api/projects/{projectId}/interface-cases`,
- lists saved cases through `GET /api/projects/{projectId}/interface-cases`,
- posts the same `libraryId` again with a changed title,
- verifies the second save updates instead of duplicating.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server/platform && ./mvnw test -Dtest=CaseLibraryControllerTest`

Expected: FAIL because the endpoint does not exist.

- [ ] **Step 3: Write minimal implementation**

Add the interface case aggregate, repository, service, and controller.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd server/platform && ./mvnw test -Dtest=CaseLibraryControllerTest`

Expected: PASS.

### Task 3: Database Migration And Verification

**Files:**
- Create: `server/platform/src/main/resources/db/migration/V2__template_and_case_library.sql`
- Modify: `docs/ai-test-platform/decisions.md`

- [ ] **Step 1: Add migration**

Create tables for project template configs and saved interface cases, with unique keys for project/template kind and project/library id.

- [ ] **Step 2: Run full backend verification**

Run: `cd server/platform && ./mvnw test`

Expected: PASS.

- [ ] **Step 3: Run package verification**

Run: `cd server/platform && ./mvnw package -DskipTests`

Expected: PASS.

- [ ] **Step 4: Update decisions**

Record that template config and interface case library persistence now exist as backend API foundations.
