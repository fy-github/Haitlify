package com.haitlify.aitest.platform.ai.quota;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!jira-readonly")
@RequestMapping("/api/projects/{projectId}/ai-quota-usage")
class AiQuotaController {

    private final AiQuotaService quotaService;

    AiQuotaController(AiQuotaService quotaService) {
        this.quotaService = quotaService;
    }

    @GetMapping
    AiQuotaUsageResponse getUsage(
        @PathVariable Long projectId,
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to
    ) {
        Instant toInstant = to == null || to.isBlank() ? Instant.now() : Instant.parse(to);
        Instant fromInstant = from == null || from.isBlank() ? toInstant.minus(30, ChronoUnit.DAYS) : Instant.parse(from);
        return quotaService.getUsage(projectId, fromInstant, toInstant);
    }
}
