package com.haitlify.aitest.platform.document;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/requirement-documents")
class RequirementDocumentController {

    private final RequirementDocumentService requirementDocumentService;

    RequirementDocumentController(RequirementDocumentService requirementDocumentService) {
        this.requirementDocumentService = requirementDocumentService;
    }

    @PostMapping(path = "/parse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    RequirementDocumentParseResponse parse(@RequestPart("file") MultipartFile file) {
        return requirementDocumentService.parse(file);
    }
}
