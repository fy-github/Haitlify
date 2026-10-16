package com.haitlify.aitest.platform.ai.config;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.ResponseEntity;
import java.util.Map;

@RestController
@Profile("!jira-readonly")
@RequestMapping("/api/ai/config")
class AiConfigController {

    private final AiConfigService aiConfigService;

    AiConfigController(AiConfigService aiConfigService) {
        this.aiConfigService = aiConfigService;
    }

    @GetMapping
    AiConfigResponse getConfig() {
        return aiConfigService.getConfig();
    }

    @PutMapping
    AiConfigResponse saveConfig(@RequestBody SaveAiConfigRequest request) {
        return aiConfigService.saveConfig(request);
    }

    @PostMapping("/test-connection")
    @ResponseStatus(HttpStatus.OK)
    AiConnectionTestResponse testConnection(@RequestBody TestAiConfigRequest request) {
        return aiConfigService.testConnection(request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Map.of("code", "invalid_request", "message", exception.getMessage()));
    }
}

