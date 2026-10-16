package com.haitlify.aitest.platform.ai.visual;

import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Profile("!jira-readonly")
@RequestMapping("/api/ai/visual-assets")
class AiVisualAssetController {

    private final AiVisualAssetService visualAssetService;

    AiVisualAssetController(AiVisualAssetService visualAssetService) {
        this.visualAssetService = visualAssetService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    AiVisualAssetResponse upload(@RequestParam Long projectId, @RequestPart("file") MultipartFile file) {
        return visualAssetService.upload(projectId, file);
    }

    @GetMapping("/{assetId}")
    AiVisualAssetResponse getAsset(@PathVariable String assetId) {
        return visualAssetService.getAsset(assetId);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> handleBadRequest(IllegalArgumentException exception) {
        return Map.of("message", exception.getMessage());
    }
}
