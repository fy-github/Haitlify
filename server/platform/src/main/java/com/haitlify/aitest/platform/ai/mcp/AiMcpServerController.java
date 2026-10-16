package com.haitlify.aitest.platform.ai.mcp;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!jira-readonly")
@RequestMapping("/api/ai/mcp-servers")
class AiMcpServerController {

    private final AiMcpServerService aiMcpServerService;

    AiMcpServerController(AiMcpServerService aiMcpServerService) {
        this.aiMcpServerService = aiMcpServerService;
    }

    @GetMapping
    List<AiMcpServerResponse> listServers() {
        return aiMcpServerService.listServers();
    }

    @PostMapping
    AiMcpServerResponse createServer(@RequestBody SaveAiMcpServerRequest request) {
        return aiMcpServerService.createServer(request);
    }

    @PutMapping("/{serverId}")
    AiMcpServerResponse updateServer(@PathVariable String serverId, @RequestBody SaveAiMcpServerRequest request) {
        return aiMcpServerService.updateServer(serverId, request);
    }

    @DeleteMapping("/{serverId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteServer(@PathVariable String serverId) {
        aiMcpServerService.deleteServer(serverId);
    }
}
