package com.haitlify.aitest.platform.template;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!jira-readonly")
@RequestMapping("/api/projects/{projectId}/case-template")
class CaseTemplateController {

    private final CaseTemplateService caseTemplateService;

    CaseTemplateController(CaseTemplateService caseTemplateService) {
        this.caseTemplateService = caseTemplateService;
    }

    @GetMapping("/interface")
    CaseTemplateResponse getInterfaceTemplate(@PathVariable Long projectId) {
        return caseTemplateService.getInterfaceTemplate(projectId);
    }

    @PutMapping("/interface")
    CaseTemplateResponse saveInterfaceTemplate(
        @PathVariable Long projectId,
        @RequestBody SaveCaseTemplateRequest request
    ) {
        return caseTemplateService.saveInterfaceTemplate(projectId, request);
    }
}
