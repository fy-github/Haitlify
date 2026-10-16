package com.haitlify.aitest.platform.ai.config;

import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * T04 能力预检端点：POST 执行五类探针（默认只读；persist=true 才把报告写入 verification_json），
 * GET 返回已存报告与新鲜度。保存/启用 profile 是独立命令（AiConfigController），本控制器不触发。
 */
@RestController
@Profile("!jira-readonly")
@RequestMapping("/api/ai/capability-check")
class AiCapabilityCheckController {

    private final AiCapabilityCheckService capabilityCheckService;

    AiCapabilityCheckController(AiCapabilityCheckService capabilityCheckService) {
        this.capabilityCheckService = capabilityCheckService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    AiCapabilityCheckService.CapabilityCheckReport run(@RequestBody CapabilityCheckRequest request) {
        return capabilityCheckService.checkProfile(request.profileId(), request.profileVersion(), request.persist());
    }

    @GetMapping
    AiCapabilityCheckService.StoredCapabilityCheck stored(
        @RequestParam String profileId,
        @RequestParam int profileVersion
    ) {
        return capabilityCheckService.storedReport(profileId, profileVersion);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> badRequest(IllegalArgumentException exception) {
        return Map.of("error", exception.getMessage());
    }

    record CapabilityCheckRequest(String profileId, int profileVersion, boolean persist) {
    }
}
