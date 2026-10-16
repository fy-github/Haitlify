package com.haitlify.aitest.platform.document;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/requirement-documents/figma")
class FigmaDesignController {

    private final FigmaDesignService figmaDesignService;

    FigmaDesignController(FigmaDesignService figmaDesignService) {
        this.figmaDesignService = figmaDesignService;
    }

    @PostMapping("/parse")
    RequirementDocumentParseResponse parse(@Valid @RequestBody FigmaDesignParseRequest request) {
        return figmaDesignService.parse(request.url());
    }
}

record FigmaDesignParseRequest(@NotBlank String url) {
}
