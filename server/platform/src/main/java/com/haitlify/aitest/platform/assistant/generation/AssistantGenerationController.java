package com.haitlify.aitest.platform.assistant.generation;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/assistant/generations")
class AssistantGenerationController {
    private final AssistantGenerationService service;

    AssistantGenerationController(AssistantGenerationService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    AssistantGenerationService.Receipt get(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    AssistantGenerationService.Receipt create(@RequestBody Map<String, Object> request) {
        return service.admit(request);
    }
}
