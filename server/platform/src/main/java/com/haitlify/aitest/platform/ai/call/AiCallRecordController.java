package com.haitlify.aitest.platform.ai.call;

import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!jira-readonly")
@RequestMapping("/api/ai/call-records")
class AiCallRecordController {

    private final AiCallRecordService aiCallRecordService;

    AiCallRecordController(AiCallRecordService aiCallRecordService) {
        this.aiCallRecordService = aiCallRecordService;
    }

    @GetMapping
    AiCallRecordListResponse listRecords(@RequestParam(required = false) String runId) {
        return aiCallRecordService.listRecords(runId);
    }

    @GetMapping("/{recordId}")
    AiCallRecordResponse getRecord(@PathVariable String recordId) {
        return aiCallRecordService.getRecord(recordId);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    Map<String, String> handleNotFound(IllegalArgumentException exception) {
        return Map.of("message", exception.getMessage());
    }
}
