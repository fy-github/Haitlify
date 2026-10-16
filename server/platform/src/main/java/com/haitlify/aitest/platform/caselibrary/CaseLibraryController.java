package com.haitlify.aitest.platform.caselibrary;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!jira-readonly")
@RequestMapping("/api/projects/{projectId}/interface-cases")
class CaseLibraryController {

    private final CaseLibraryService caseLibraryService;

    CaseLibraryController(CaseLibraryService caseLibraryService) {
        this.caseLibraryService = caseLibraryService;
    }

    @GetMapping
    InterfaceCaseListResponse list(@PathVariable Long projectId) {
        return caseLibraryService.list(projectId);
    }

    @PostMapping
    SaveInterfaceCasesResponse save(
        @PathVariable Long projectId,
        @RequestBody SaveInterfaceCasesRequest request
    ) {
        return caseLibraryService.save(projectId, request);
    }

    @PostMapping("/delete")
    DeleteInterfaceCasesResponse delete(
        @PathVariable Long projectId,
        @RequestBody DeleteInterfaceCasesRequest request
    ) {
        return caseLibraryService.delete(projectId, request);
    }
}
