package com.haitlify.aitest.platform.ai.document;

import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Profile("!jira-readonly")
@RequestMapping("/api/ai/source-documents")
class AiSourceDocumentController {

    private final AiSourceDocumentService aiSourceDocumentService;

    AiSourceDocumentController(AiSourceDocumentService aiSourceDocumentService) {
        this.aiSourceDocumentService = aiSourceDocumentService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    AiSourceDocumentResponse upload(@RequestParam Long projectId, @RequestPart("file") MultipartFile file) {
        return aiSourceDocumentService.upload(projectId, file);
    }

    @GetMapping
    AiSourceDocumentListResponse list(@RequestParam Long projectId) {
        return aiSourceDocumentService.list(projectId);
    }

    @GetMapping("/{documentId}")
    AiSourceDocumentResponse getDocument(@PathVariable String documentId) {
        return aiSourceDocumentService.getDocument(documentId);
    }

    @GetMapping("/{documentId}/chunks")
    AiSourceChunkListResponse listChunks(@PathVariable String documentId) {
        return aiSourceDocumentService.listChunks(documentId);
    }
}
