package com.haitlify.aitest.platform.ai.skill;

import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!jira-readonly")
@RequestMapping("/api/ai/skills")
class AiSkillController {

    private final AiSkillService aiSkillService;

    AiSkillController(AiSkillService aiSkillService) {
        this.aiSkillService = aiSkillService;
    }

    @GetMapping
    List<AiSkillResponse> listSkills() {
        return aiSkillService.listSkills();
    }

    @GetMapping("/{skillId}")
    AiSkillResponse getSkill(@PathVariable String skillId) {
        return aiSkillService.getSkill(skillId);
    }

    @PostMapping
    AiSkillResponse createCustomSkill(@RequestBody SaveCustomAiSkillRequest request) {
        return aiSkillService.createCustomSkill(request);
    }

    @PutMapping("/{skillId}")
    AiSkillResponse updateCustomSkill(@PathVariable String skillId, @RequestBody SaveCustomAiSkillRequest request) {
        return aiSkillService.updateCustomSkill(skillId, request);
    }

    @GetMapping("/{skillId}/versions")
    AiSkillVersionListResponse listVersions(@PathVariable String skillId) {
        return aiSkillService.listVersions(skillId);
    }

    @GetMapping("/{skillId}/diff")
    AiSkillDiffResponse diffVersions(
        @PathVariable String skillId,
        @RequestParam int fromVersion,
        @RequestParam int toVersion
    ) {
        return aiSkillService.diffVersions(skillId, fromVersion, toVersion);
    }

    @PutMapping("/{skillId}/override")
    AiSkillResponse saveOverride(@PathVariable String skillId, @RequestBody SaveAiSkillOverrideRequest request) {
        return aiSkillService.saveOverride(skillId, request);
    }

    @DeleteMapping("/{skillId}/override")
    AiSkillResponse deleteOverride(@PathVariable String skillId) {
        aiSkillService.deleteOverride(skillId);
        return aiSkillService.getSkill(skillId);
    }

    /** T09 bullet 5：发布校验失败（未知工具/禁用服务/schema 形状）以 4xx 明确拒绝。 */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
    }
}
