package com.haitlify.aitest.platform.ai.tool;

import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

@RestController
@Profile("!jira-readonly")
@RequestMapping("/api/ai/tools")
class AiToolController {

    private final AiToolRegistry aiToolRegistry;

    AiToolController(AiToolRegistry aiToolRegistry) {
        this.aiToolRegistry = aiToolRegistry;
    }

    @GetMapping
    AiToolListResponse listTools() {
        List<AiToolResponse> items = aiToolRegistry.listTools().stream()
            .map(this::toResponse)
            .toList();
        return new AiToolListResponse(items);
    }

    @GetMapping("/{toolId}")
    AiToolResponse getTool(@PathVariable String toolId) {
        return toResponse(aiToolRegistry.requireTool(toolId));
    }

    @PostMapping("/{toolId}/dry-run")
    AiToolDryRunResponse dryRun(@PathVariable String toolId, @RequestBody JsonNode request) {
        AiTool tool = aiToolRegistry.requireTool(toolId);
        return new AiToolDryRunResponse(tool.toolId(), tool.readOnly(), tool.dryRun(request));
    }

    private AiToolResponse toResponse(AiTool tool) {
        return new AiToolResponse(
            tool.toolId(),
            tool.name(),
            tool.description(),
            tool.readOnly(),
            tool.inputSchema(),
            tool.outputSchema()
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> handleBadRequest(IllegalArgumentException exception) {
        return Map.of("message", exception.getMessage());
    }
}

record AiToolListResponse(
    List<AiToolResponse> items
) {
}

record AiToolResponse(
    String toolId,
    String name,
    String description,
    boolean readOnly,
    JsonNode inputSchema,
    JsonNode outputSchema
) {
}

record AiToolDryRunResponse(
    String toolId,
    boolean readOnly,
    JsonNode result
) {
}
