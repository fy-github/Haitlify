> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

> **Status: ✅ 已落地**(2026-08-20 归档标记。功能已上生产;下方 `- [ ]` 为执行期 TDD 脚手架,实施时未逐项回勾,不代表未完成。)

# Web Case AI Executability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Repair Web case conversion/editing and guarantee AI-generated Web cases satisfy the runner's executable contract under effective project rules.

**Architecture:** Use active-kind projections in Vue, add project-aware rule resolution in Spring, register separate analysis/generation Skills, and validate plus repair LLM Web DSL before returning formal cases.

**Tech Stack:** Vue 3, TypeScript, Element Plus, Vitest, Spring Boot, Java 21, JDBC, Jackson, JUnit/MockMvc, Node Web runner.

---

### Task 1: Web Library Conversion And Editing

- [ ] Add failing frontend tests for Web project/module/count projections, row conversion, enabled edit action, and widened action column.
- [ ] Implement active-kind conversion projections and candidate validation.
- [ ] Enable Web edit and row conversion while preserving saved identity/order.
- [ ] Run focused Vitest tests and TypeScript build.

### Task 2: Project Rule Resolution

- [ ] Add failing service/controller tests for system rules, project overrides, effective merging, and version metadata.
- [ ] Extend rule persistence and API with optional `projectId` while preserving system compatibility.
- [ ] Load effective rules in analysis and generation prompts and record their versions.
- [ ] Run focused Spring tests.

### Task 3: Built-In AI Skills

- [ ] Add failing tests asserting both analysis and formal Web generation Skills are registered with explicit schemas and examples.
- [ ] Expand analysis Skill constraints and add `formal-web-case-generation`.
- [ ] Inject effective Skill content, project rules, and runner contract into prompts.
- [ ] Run Skill and prompt tests.

### Task 4: Web DSL Validation And Repair

- [ ] Add failing unit tests for valid DSL, numbered prose, missing targets, unsupported actions, missing assertions, and normalized browser/viewport.
- [ ] Implement a focused validator/parser shared by generation response parsing.
- [ ] Add one repair generation call containing validation errors; reject a second invalid response.
- [ ] Expand generated response fields and frontend mapping to full Web case metadata.
- [ ] Run backend and frontend focused tests.

### Task 5: End-To-End Verification

- [ ] Run all platform tests.
- [ ] Run all frontend tests and production build.
- [ ] Run runner tests and a contract test proving converted AI cases build valid runner steps.
- [ ] Restart changed services and verify Web, platform, and runner health.
